package cloide.server.module.metrics.service;

import cloide.server.common.vo.PageResult;
import cloide.server.module.metrics.dto.MetricsReportPayload;
import cloide.server.module.metrics.vo.MetricsHistoryVo;
import cloide.server.module.metrics.vo.MetricsLatestVo;
import cloide.server.module.metrics.vo.ProcessSnapshotVo;
import cloide.server.module.metrics.vo.ServerResourcesSnapshotVo;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * 定义 Agent 指标写入和查询的业务契约，供 HTTP 与 WebSocket 适配层依赖。
 */
public interface MetricsService {

    /** 写入已认证 Agent 的指标快照。 */
    void report(Long authenticatedServerId, String messageId, MetricsReportPayload payload);

    /** 查询服务器最新指标。 */
    MetricsLatestVo latest(Long serverId);

    /** 分页查询服务器指标历史。 */
    PageResult<MetricsHistoryVo> history(Long serverId, OffsetDateTime startTime,
            OffsetDateTime endTime, Integer page, Integer pageSize);

    /**
     * 查询服务器新鲜窗口内的实时 Top 进程快照。
     *
     * @param serverId 服务器 ID
     * @return 快照；服务器无记录或快照已过期时为空
     */
    Optional<ProcessSnapshotVo> latestProcessSnapshot(Long serverId);

    /**
     * 查询服务器新鲜窗口内的实时磁盘/网卡扩展资源快照。
     *
     * @param serverId 服务器 ID
     * @return 快照；服务器无记录或快照已过期时为空
     */
    Optional<ServerResourcesSnapshotVo> latestResources(Long serverId);

    /**
     * 表示已写入数据库、等待事务提交后广播的指标事件。
     *
     * @param metrics 最新指标快照
     * @param processes 可选的 Top 进程快照；旧版 Agent 不携带时为 null
     * @param resources 可选的磁盘/网卡扩展资源快照；旧版 Agent 不携带或扩展字段被拒收时为 null
     */
    record MetricsReportedEvent(MetricsLatestVo metrics, ProcessSnapshotVo processes,
            ServerResourcesSnapshotVo resources) {

        /** 兼容旧调用方的双参构造：不带扩展资源快照。 */
        public MetricsReportedEvent(MetricsLatestVo metrics, ProcessSnapshotVo processes) {
            this(metrics, processes, null);
        }
    }
}
