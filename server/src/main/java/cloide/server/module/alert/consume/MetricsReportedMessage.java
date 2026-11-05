package cloide.server.module.alert.consume;

import com.fasterxml.jackson.annotation.JsonProperty;
import cloide.server.module.metrics.vo.MetricsLatestVo;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * metrics.reported.v1 消息信封（对照冻结契约 message-contracts-v1.md §二/§三）。
 *
 * <p>由 RabbitMQ 消费者反序列化后调用告警评估；payload 与
 * {@link MetricsLatestVo} 字段一一对应（另含 message_id）。</p>
 */
public record MetricsReportedMessage(
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("occurred_at") String occurredAt,
        String producer,
        Payload payload) {

    /** 指标载荷，字段名与冻结契约一致（snake_case）。 */
    public record Payload(
            @JsonProperty("server_id") Long serverId,
            @JsonProperty("message_id") String messageId,
            @JsonProperty("collected_at") String collectedAt,
            @JsonProperty("cpu_percent") BigDecimal cpuPercent,
            @JsonProperty("memory_percent") BigDecimal memoryPercent,
            @JsonProperty("memory_used") Long memoryUsed,
            @JsonProperty("memory_total") Long memoryTotal,
            @JsonProperty("disk_percent") BigDecimal diskPercent,
            @JsonProperty("disk_used") Long diskUsed,
            @JsonProperty("disk_total") Long diskTotal,
            @JsonProperty("net_rx") Long netRx,
            @JsonProperty("net_tx") Long netTx,
            BigDecimal temperature,
            @JsonProperty("load_avg") BigDecimal loadAvg) {

        /** 转换为评估器可复用的指标快照。 */
        public MetricsLatestVo toMetricsLatestVo() {
            MetricsLatestVo vo = new MetricsLatestVo();
            vo.setServerId(serverId);
            vo.setCpuPercent(cpuPercent);
            vo.setMemoryPercent(memoryPercent);
            vo.setMemoryUsed(memoryUsed);
            vo.setMemoryTotal(memoryTotal);
            vo.setDiskPercent(diskPercent);
            vo.setDiskUsed(diskUsed);
            vo.setDiskTotal(diskTotal);
            vo.setNetRx(netRx);
            vo.setNetTx(netTx);
            vo.setTemperature(temperature);
            vo.setLoadAvg(loadAvg);
            vo.setCollectedAt(OffsetDateTime.parse(collectedAt));
            return vo;
        }
    }
}
