package cloide.server.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import cloide.server.module.metrics.service.MetricsService.MetricsReportedEvent;
import cloide.server.module.metrics.service.ProcessSnapshotRegistry;
import cloide.server.module.metrics.service.ResourcesSnapshotRegistry;
import cloide.server.module.metrics.vo.MetricsLatestVo;
import cloide.server.module.metrics.vo.ProcessSnapshotVo;
import cloide.server.module.metrics.vo.ServerResourcesSnapshotVo;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.TextMessage;

/** 在 Metrics 事务提交后向有权限的 Monitor 订阅者广播指标更新。 */
@Component
public class MonitorMetricsPublisher {

    private final ObjectMapper objectMapper;
    private final MonitorSubscriptionRegistry registry;
    private final Clock clock;
    private final MonitorSessionTerminationService terminationService;
    private final ProcessSnapshotRegistry processSnapshotRegistry;
    private final ResourcesSnapshotRegistry resourcesSnapshotRegistry;

    /** 注入 JSON 序列化器、订阅注册表、进程/资源快照注册表与终止服务。 */
    public MonitorMetricsPublisher(ObjectMapper objectMapper, MonitorSubscriptionRegistry registry, Clock clock,
            MonitorSessionTerminationService terminationService, ProcessSnapshotRegistry processSnapshotRegistry,
            ResourcesSnapshotRegistry resourcesSnapshotRegistry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
        this.clock = clock;
        this.terminationService = terminationService;
        this.processSnapshotRegistry = processSnapshotRegistry;
        this.resourcesSnapshotRegistry = resourcesSnapshotRegistry;
    }

    /** 仅在数据库事务成功提交后发送指标更新。 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(MetricsReportedEvent event) {
        MetricsLatestVo metrics = event.metrics();
        // 提交后才把进程快照落入注册表，保证 REST 读到的快照都来自已确认入库的上报。
        if (event.processes() != null) {
            processSnapshotRegistry.update(event.processes());
        }
        // 提交后才把扩展资源快照落入注册表，语义与进程快照一致（协议 v1.5）。
        if (event.resources() != null) {
            resourcesSnapshotRegistry.update(event.resources());
        }
        for (MonitorWebSocketSession subscriber : registry.subscribers(metrics.getServerId())) {
            try {
                if (!subscriber.send(new TextMessage(message(metrics, event.processes(), event.resources())))) {
                    terminationService.terminateNormally(subscriber);
                }
            } catch (MonitorBackpressureException exception) {
                terminationService.terminateForBackpressure(subscriber);
            } catch (IOException exception) {
                terminationService.terminateNormally(subscriber);
            }
        }
    }

    /**
     * 构建 metrics.update WebSocket 消息 JSON 字符串；进程快照存在时追加可选 processes 节点，
     * 扩展资源快照存在时追加可选 resources 节点（协议 v1.4/v1.5）。
     */
    private String message(MetricsLatestVo metrics, ProcessSnapshotVo processes,
            ServerResourcesSnapshotVo resources) throws IOException {
        var payload = objectMapper.createObjectNode();
        payload.put("server_id", metrics.getServerId());
        payload.set("metrics", objectMapper.valueToTree(metrics));
        if (processes != null) {
            payload.set("processes", objectMapper.valueToTree(processes));
        }
        if (resources != null) {
            payload.set("resources", objectMapper.valueToTree(resources));
        }
        return objectMapper.createObjectNode()
                .put("type", "metrics.update")
                .put("message_id", java.util.UUID.randomUUID().toString())
                .put("timestamp", OffsetDateTime.now(clock).toString())
                .set("payload", payload)
                .toString();
    }
}
