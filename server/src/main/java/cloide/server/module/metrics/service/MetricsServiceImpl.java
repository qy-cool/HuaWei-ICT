package cloide.server.module.metrics.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.common.vo.PageResult;
import cloide.server.module.metrics.dto.DiskSamplePayload;
import cloide.server.module.metrics.dto.MetricsReportPayload;
import cloide.server.module.metrics.dto.NicSamplePayload;
import cloide.server.module.metrics.dto.ProcessSamplePayload;
import cloide.server.module.metrics.entity.MetricsEntity;
import cloide.server.module.metrics.entity.MetricsIngestionEntity;
import cloide.server.module.metrics.mapper.MetricsMapper;
import cloide.server.module.metrics.outbox.OutboxEnvelopeFactory;
import cloide.server.module.metrics.outbox.OutboxService;
import cloide.server.module.metrics.vo.DiskSampleVo;
import cloide.server.module.metrics.vo.MetricsHistoryVo;
import cloide.server.module.metrics.vo.MetricsLatestVo;
import cloide.server.module.metrics.vo.NicSampleVo;
import cloide.server.module.metrics.vo.ProcessSampleVo;
import cloide.server.module.metrics.vo.ProcessSnapshotVo;
import cloide.server.module.metrics.vo.ServerResourcesSnapshotVo;
import cloide.server.module.server.service.ServerService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 负责固定宽表指标校验、写入、最新值和历史分页查询。 */
// 开启 Slf4j 日志：扩展资源快照被拒收时需要输出含服务器 ID 的告警日志（协议 v1.5 丢节点语义）。
@Slf4j
@Service
public class MetricsServiceImpl implements MetricsService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_HISTORY_DAYS = 7;
    // Top 进程列表条数上限，与协议（websocket-protocol.md v1.4）一致。
    private static final int MAX_PROCESS_ENTRIES = 20;
    // 进程名字符数上限，与协议一致。
    private static final int MAX_PROCESS_NAME_LENGTH = 128;
    // 磁盘容量列表条数上限，与协议（websocket-protocol.md v1.5）一致。
    private static final int MAX_DISK_ENTRIES = 32;
    // 网卡吞吐列表条数上限，与协议一致。
    private static final int MAX_NIC_ENTRIES = 64;
    // 挂载点/设备名/网卡名的字符数上限，与协议一致。
    private static final int MAX_RESOURCE_NAME_LENGTH = 128;
    private static final ZoneId APPLICATION_ZONE = ZoneOffset.UTC;
    private final MetricsMapper metricsMapper;
    private final ServerService serverService;
    private final OutboxService outboxService;
    private final OutboxEnvelopeFactory outboxEnvelopeFactory;
    private final ApplicationEventPublisher eventPublisher;
    private final ProcessSnapshotRegistry processSnapshotRegistry;
    private final ResourcesSnapshotRegistry resourcesSnapshotRegistry;

    /** 注入指标数据访问组件、服务器契约、Outbox 登记服务、信封工厂与进程/资源快照注册表（servers 表访问统一走 ServerService）。 */
    public MetricsServiceImpl(MetricsMapper metricsMapper, ServerService serverService,
            OutboxService outboxService, OutboxEnvelopeFactory outboxEnvelopeFactory,
            ApplicationEventPublisher eventPublisher, ProcessSnapshotRegistry processSnapshotRegistry,
            ResourcesSnapshotRegistry resourcesSnapshotRegistry) {
        this.metricsMapper = metricsMapper;
        this.serverService = serverService;
        this.outboxService = outboxService;
        this.outboxEnvelopeFactory = outboxEnvelopeFactory;
        this.eventPublisher = eventPublisher;
        this.processSnapshotRegistry = processSnapshotRegistry;
        this.resourcesSnapshotRegistry = resourcesSnapshotRegistry;
    }

    /**
     * 写入已认证 Agent 上报的一条完整宽表快照。
     *
     * <p>同一服务器的写入先锁定服务器行，确保重复投递与采样时间判定串行化。
     * 同一 messageId 重复投递静默成功；采样时间不严格晚于最后接受采样时拒绝，
     * 因而不会触发重复 Metrics 事件、告警评估或 WebSocket 推送。</p>
     *
     * <p>指标入库成功后，与指标同事务登记 Outbox 待发布事件（MVP-10）：
     * 事务回滚时 outbox 行一并回滚，保证"已入库指标必有待发布事件"。</p>
     *
     * <p>入库阶段的可恢复数据库故障（连接/锁等）包装为
     * {@link MetricsRejectionReason#RETRIABLE_SERVER_ERROR}，Agent 有限重试后死信；
     * 永久拒绝（载荷/采样时间/服务器不存在）维持原语义。</p>
     */
    @Transactional
    public void report(Long authenticatedServerId, String messageId, MetricsReportPayload payload) {
        validatePayload(authenticatedServerId, messageId, payload);
        try {
            if (!serverService.existsActiveForUpdate(authenticatedServerId)) {
                throw new MetricsRejectedException(MetricsRejectionReason.SERVER_NOT_FOUND);
            }
            if (isDuplicateIngestion(authenticatedServerId, messageId, payload.getCollectedAt())) {
                return;
            }
            MetricsEntity entity = toEntity(payload);
            LocalDateTime latestCollectedAt = metricsMapper.selectLatestCollectedAt(authenticatedServerId);
            if (latestCollectedAt != null && !entity.getCollectedAt().isAfter(latestCollectedAt)) {
                throw new MetricsRejectedException(MetricsRejectionReason.STALE_COLLECTED_AT);
            }
            if (metricsMapper.insertMetric(entity) != 1) {
                throw new BusinessException(ErrorCode.DATABASE_ERROR);
            }
            // 与指标同事务登记 Outbox 待发布事件：eventId 由本处生成并写入信封，
            // 保证行内 event_id 与 payload 中 event_id 一致（消费侧幂等主键）。
            String eventId = UUID.randomUUID().toString();
            String envelope = outboxEnvelopeFactory.build(entity, messageId, eventId);
            outboxService.enqueue(OutboxEnvelopeFactory.EVENT_TYPE, OutboxEnvelopeFactory.ROUTING_KEY,
                    envelope, eventId);
            eventPublisher.publishEvent(new MetricsReportedEvent(toLatestVo(entity), toProcessSnapshot(payload),
                    toResourcesSnapshot(payload)));
        } catch (DuplicateKeyException exception) {
            // 重复投递竞态（isDuplicateIngestion 预检查兜底）：数据级冲突，重发不会改变结果
            throw exception;
        } catch (DataAccessException exception) {
            // 可恢复入库故障 → retriable nack，Agent 有限重试后仍未成功再死信
            throw new MetricsRejectedException(MetricsRejectionReason.RETRIABLE_SERVER_ERROR);
        }
    }

    /** 查询服务器最新指标；无记录时返回资源不存在。 */
    @Transactional(readOnly = true)
    public MetricsLatestVo latest(Long serverId) {
        ensureServerExists(serverId);
        MetricsEntity entity = metricsMapper.selectLatestByServerId(serverId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return toLatestVo(entity);
    }

    /** 查询服务器新鲜窗口内的实时 Top 进程快照；无快照或已过期时为空。 */
    @Transactional(readOnly = true)
    public Optional<ProcessSnapshotVo> latestProcessSnapshot(Long serverId) {
        ensureServerExists(serverId);
        return processSnapshotRegistry.latest(serverId);
    }

    /** 查询服务器新鲜窗口内的实时磁盘/网卡扩展资源快照；无快照或已过期时为空。 */
    @Transactional(readOnly = true)
    public Optional<ServerResourcesSnapshotVo> latestResources(Long serverId) {
        ensureServerExists(serverId);
        return resourcesSnapshotRegistry.latest(serverId);
    }

    /** 查询服务器时间窗口内的历史指标。 */
    @Transactional(readOnly = true)
    public PageResult<MetricsHistoryVo> history(Long serverId, OffsetDateTime startTime,
            OffsetDateTime endTime, Integer page, Integer pageSize) {
        ensureServerExists(serverId);
        if (page == null || page < 1 || pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE
                || startTime == null || endTime == null || endTime.isBefore(startTime)
                || endTime.isAfter(startTime.plusDays(MAX_HISTORY_DAYS))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        LocalDateTime start = startTime.atZoneSameInstant(APPLICATION_ZONE).toLocalDateTime();
        LocalDateTime end = endTime.atZoneSameInstant(APPLICATION_ZONE).toLocalDateTime();
        // 分页由 MyBatis-Plus 拦截器承担：COUNT 自动执行，total 回写 Page。
        Page<MetricsEntity> pager = new Page<>(page, pageSize);
        List<MetricsHistoryVo> items = metricsMapper.selectHistory(pager, serverId, start, end)
                .stream().map(this::toHistoryVo).toList();
        PageResult<MetricsHistoryVo> result = new PageResult<>();
        result.setItems(items);
        result.setTotal(pager.getTotal());
        result.setPage(page);
        result.setPageSize(pageSize);
        return result;
    }

    /**
     * 验证服务器存在且有效，不存在时抛出资源不存在异常。
     *
     * @param serverId 服务器 ID
     */
    private void ensureServerExists(Long serverId) {
        if (serverId == null || serverId <= 0 || !serverService.existsActive(serverId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    /** 将唯一消息 ID 登记为已接收；唯一键冲突代表 Agent 对同一消息的合法重试。 */
    private boolean isDuplicateIngestion(Long serverId, String messageId, OffsetDateTime collectedAt) {
        MetricsIngestionEntity ingestion = new MetricsIngestionEntity();
        ingestion.setServerId(serverId);
        ingestion.setMessageId(messageId);
        ingestion.setCollectedAt(collectedAt.atZoneSameInstant(APPLICATION_ZONE).toLocalDateTime());
        try {
            return metricsMapper.insertIngestion(ingestion) != 1;
        } catch (DuplicateKeyException exception) {
            return true;
        }
    }

    /**
     * 校验指标上报载荷的全部字段合法性，非法时抛出永久拒绝异常。
     *
     * @param authenticatedServerId 已认证服务器 ID
     * @param messageId 消息幂等 UUID
     * @param payload 指标上报载荷
     */
    private void validatePayload(Long authenticatedServerId, String messageId, MetricsReportPayload payload) {
        if (payload == null || authenticatedServerId == null || !isUuid(messageId)
                || !authenticatedServerId.equals(payload.getServerId())
                || payload.getCollectedAt() == null
                // 协议（websocket-protocol.md）与消息契约（message-contracts-v1.md）要求
                // collected_at 为 UTC ISO-8601；非 UTC offset 按永久非法载荷拒绝。
                || !ZoneOffset.UTC.equals(payload.getCollectedAt().getOffset())
                || payload.getCollectedAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5))) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
        validatePercent(payload.getCpuPercent());
        validatePercent(payload.getMemoryPercent());
        validatePercent(payload.getDiskPercent());
        validateNonNegative(payload.getMemoryUsed());
        validateNonNegative(payload.getMemoryTotal());
        validateNonNegative(payload.getDiskUsed());
        validateNonNegative(payload.getDiskTotal());
        validateNonNegative(payload.getNetRx());
        validateNonNegative(payload.getNetTx());
        validateNonNegative(payload.getLoadAvg());
        validateProcessTop(payload.getProcessCpuTop());
        validateProcessTop(payload.getProcessMemTop());
        if (payload.getMemoryUsed() != null && payload.getMemoryTotal() != null
                && payload.getMemoryUsed() > payload.getMemoryTotal()
                || payload.getDiskUsed() != null && payload.getDiskTotal() != null
                && payload.getDiskUsed() > payload.getDiskTotal()) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
    }

    /**
     * 校验可选 Top 进程列表的条数与元素合法性。
     *
     * @param samples 进程条目列表；旧版 Agent 整体省略时为 null
     */
    private void validateProcessTop(List<ProcessSamplePayload> samples) {
        if (samples == null) {
            return;
        }
        if (samples.size() > MAX_PROCESS_ENTRIES) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
        for (ProcessSamplePayload sample : samples) {
            if (sample == null || sample.pid() == null || sample.pid() < 1
                    || sample.name() == null || sample.name().isBlank()
                    || sample.name().length() > MAX_PROCESS_NAME_LENGTH
                    || isOutsidePercent(sample.cpuPercent()) || isOutsidePercent(sample.memPercent())) {
                throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
            }
        }
    }

    /** 判断占比数值是否落在 0-100 之外；进程协议字段不允许缺省。 */
    private boolean isOutsidePercent(BigDecimal value) {
        return value == null || value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0;
    }

    /**
     * 将上报载荷中的可选进程排行转换为内存快照视图；两个排行均缺省时返回 null。
     *
     * @param payload 指标上报载荷
     * @return 进程快照；旧版 Agent 载荷返回 null
     */
    private ProcessSnapshotVo toProcessSnapshot(MetricsReportPayload payload) {
        if (payload.getProcessCpuTop() == null && payload.getProcessMemTop() == null) {
            return null;
        }
        List<ProcessSampleVo> cpuTop = toProcessSampleVos(payload.getProcessCpuTop());
        List<ProcessSampleVo> memTop = toProcessSampleVos(payload.getProcessMemTop());
        // 校验层已保证 collected_at 为 UTC offset，可直接透传。
        return new ProcessSnapshotVo(payload.getServerId(), payload.getCollectedAt(), cpuTop, memTop);
    }

    /**
     * 批量转换进程条目视图；空列表保持空列表，缺省（null）转为空列表以稳定快照形状。
     *
     * @param samples 上报的进程条目
     * @return 进程条目视图列表
     */
    private List<ProcessSampleVo> toProcessSampleVos(List<ProcessSamplePayload> samples) {
        if (samples == null) {
            return List.of();
        }
        return samples.stream()
                .map(sample -> new ProcessSampleVo(sample.pid(), sample.name(),
                        sample.cpuPercent(), sample.memPercent()))
                .toList();
    }

    /** UUID 是 Agent 至少一次投递的幂等键，格式无效时不能安全执行去重。 */
    private boolean isUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * 将上报载荷中的可选磁盘/网卡扩展资源转换为内存快照视图；两个列表均缺省时返回 null。
     *
     * <p>扩展字段校验语义与进程字段（永久 nack）刻意不同：任一列表超限或任一元素
     * 非法时丢弃整个快照并输出告警日志，核心指标行照常入库与确认——可选增强
     * 不得打断核心遥测链路（websocket-protocol.md v1.5 丢节点语义）。</p>
     *
     * @param payload 指标上报载荷
     * @return 资源快照；旧版 Agent 载荷或扩展字段被拒收时返回 null
     */
    private ServerResourcesSnapshotVo toResourcesSnapshot(MetricsReportPayload payload) {
        if (payload.getDisks() == null && payload.getNics() == null) {
            return null;
        }
        if (payload.getDisks() != null && (payload.getDisks().size() > MAX_DISK_ENTRIES
                || payload.getDisks().stream().anyMatch(this::isInvalidDiskSample))) {
            log.warn("resources snapshot dropped: invalid disks node, serverId={}, diskEntries={}",
                    payload.getServerId(), payload.getDisks().size());
            return null;
        }
        if (payload.getNics() != null && (payload.getNics().size() > MAX_NIC_ENTRIES
                || payload.getNics().stream().anyMatch(this::isInvalidNicSample))) {
            log.warn("resources snapshot dropped: invalid nics node, serverId={}, nicEntries={}",
                    payload.getServerId(), payload.getNics().size());
            return null;
        }
        List<DiskSampleVo> disks = toDiskSampleVos(payload.getDisks());
        List<NicSampleVo> nics = toNicSampleVos(payload.getNics());
        // 校验层已保证 collected_at 为 UTC offset，可直接透传。
        return new ServerResourcesSnapshotVo(payload.getServerId(), payload.getCollectedAt(), disks, nics);
    }

    /**
     * 判断单个磁盘条目是否非法：元素缺失、名称空白或超长、字节数缺失或为负。
     *
     * @param sample 磁盘条目
     * @return 非法时为 true
     */
    private boolean isInvalidDiskSample(DiskSamplePayload sample) {
        return sample == null || isBlankOrOverLong(sample.mountPoint()) || isBlankOrOverLong(sample.device())
                || isNullOrNegative(sample.total()) || isNullOrNegative(sample.free());
    }

    /**
     * 判断单个网卡条目是否非法：元素缺失、名称空白或超长、速率缺失或为负。
     *
     * @param sample 网卡条目
     * @return 非法时为 true
     */
    private boolean isInvalidNicSample(NicSamplePayload sample) {
        return sample == null || isBlankOrOverLong(sample.name())
                || isNullOrNegative(sample.rxKbps()) || isNullOrNegative(sample.txKbps());
    }

    /** 名称既不能为空白，也不能超过协议规定的字符数上限。 */
    private boolean isBlankOrOverLong(String name) {
        return name == null || name.isBlank() || name.length() > MAX_RESOURCE_NAME_LENGTH;
    }

    /** 数值既不能缺失，也不能为负。 */
    private boolean isNullOrNegative(Number value) {
        return value == null || value.doubleValue() < 0;
    }

    /**
     * 批量转换磁盘条目视图；缺省（null）转为空列表以稳定快照形状。
     *
     * @param samples 上报的磁盘条目
     * @return 磁盘条目视图列表
     */
    private List<DiskSampleVo> toDiskSampleVos(List<DiskSamplePayload> samples) {
        if (samples == null) {
            return List.of();
        }
        return samples.stream()
                .map(sample -> new DiskSampleVo(sample.mountPoint(), sample.device(), sample.total(), sample.free()))
                .toList();
    }

    /**
     * 批量转换网卡条目视图；缺省（null）转为空列表以稳定快照形状。
     *
     * @param samples 上报的网卡条目
     * @return 网卡条目视图列表
     */
    private List<NicSampleVo> toNicSampleVos(List<NicSamplePayload> samples) {
        if (samples == null) {
            return List.of();
        }
        return samples.stream()
                .map(sample -> new NicSampleVo(sample.name(), sample.rxKbps(), sample.txKbps()))
                .toList();
    }

    /**
     * 校验百分比值在 0-100 范围内。
     *
     * @param value 百分比值，允许 null
     */
    private void validatePercent(BigDecimal value) {
        if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
    }

    /**
     * 校验数值为非负数。
     *
     * @param value 数值，允许 null
     */
    private void validateNonNegative(Number value) {
        if (value != null && value.doubleValue() < 0) {
            throw new MetricsRejectedException(MetricsRejectionReason.INVALID_METRICS_PAYLOAD);
        }
    }

    /**
     * 将上报载荷转换为指标实体。
     *
     * @param payload 指标上报载荷
     * @return 指标实体
     */
    private MetricsEntity toEntity(MetricsReportPayload payload) {
        MetricsEntity entity = new MetricsEntity();
        entity.setServerId(payload.getServerId());
        entity.setCpuPercent(payload.getCpuPercent());
        entity.setMemoryPercent(payload.getMemoryPercent());
        entity.setMemoryUsed(payload.getMemoryUsed());
        entity.setMemoryTotal(payload.getMemoryTotal());
        entity.setDiskPercent(payload.getDiskPercent());
        entity.setDiskUsed(payload.getDiskUsed());
        entity.setDiskTotal(payload.getDiskTotal());
        entity.setNetRx(payload.getNetRx());
        entity.setNetTx(payload.getNetTx());
        entity.setTemperature(payload.getTemperature());
        entity.setLoadAvg(payload.getLoadAvg());
        entity.setCollectedAt(payload.getCollectedAt().atZoneSameInstant(APPLICATION_ZONE).toLocalDateTime());
        return entity;
    }

    /**
     * 将指标实体转换为最新指标视图对象。
     *
     * @param entity 指标实体
     * @return 最新指标视图对象
     */
    private MetricsLatestVo toLatestVo(MetricsEntity entity) {
        MetricsLatestVo result = new MetricsLatestVo();
        result.setServerId(entity.getServerId());
        result.setCpuPercent(entity.getCpuPercent());
        result.setMemoryPercent(entity.getMemoryPercent());
        result.setMemoryUsed(entity.getMemoryUsed());
        result.setMemoryTotal(entity.getMemoryTotal());
        result.setDiskPercent(entity.getDiskPercent());
        result.setDiskUsed(entity.getDiskUsed());
        result.setDiskTotal(entity.getDiskTotal());
        result.setNetRx(entity.getNetRx());
        result.setNetTx(entity.getNetTx());
        result.setTemperature(entity.getTemperature());
        result.setLoadAvg(entity.getLoadAvg());
        result.setCollectedAt(entity.getCollectedAt().atOffset(ZoneOffset.UTC));
        return result;
    }

    /**
     * 将指标实体转换为历史指标视图对象。
     *
     * @param entity 指标实体
     * @return 历史指标视图对象
     */
    private MetricsHistoryVo toHistoryVo(MetricsEntity entity) {
        MetricsHistoryVo result = new MetricsHistoryVo();
        MetricsLatestVo latest = toLatestVo(entity);
        result.setServerId(latest.getServerId());
        result.setCpuPercent(latest.getCpuPercent());
        result.setMemoryPercent(latest.getMemoryPercent());
        result.setMemoryUsed(latest.getMemoryUsed());
        result.setMemoryTotal(latest.getMemoryTotal());
        result.setDiskPercent(latest.getDiskPercent());
        result.setDiskUsed(latest.getDiskUsed());
        result.setDiskTotal(latest.getDiskTotal());
        result.setNetRx(latest.getNetRx());
        result.setNetTx(latest.getNetTx());
        result.setTemperature(latest.getTemperature());
        result.setLoadAvg(latest.getLoadAvg());
        result.setCollectedAt(latest.getCollectedAt());
        return result;
    }
}
