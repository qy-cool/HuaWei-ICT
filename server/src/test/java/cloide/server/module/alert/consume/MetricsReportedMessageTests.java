package cloide.server.module.alert.consume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import cloide.server.module.metrics.vo.MetricsLatestVo;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

/**
 * 验证 metrics.reported.v1 消息信封按冻结契约（message-contracts-v1.md）反序列化。
 */
class MetricsReportedMessageTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 契约示例信封可完整反序列化，字段与契约一致。 */
    @Test
    void contractEnvelopeShouldDeserialize() throws Exception {
        String envelope = """
                {
                  "event_id": "9f4c2d10-8b7f-4c3d-a5e0-1ef5b67f2f1a",
                  "event_type": "metrics.reported",
                  "schema_version": 1,
                  "occurred_at": "2026-07-28T12:00:00Z",
                  "producer": "metrics-service",
                  "payload": {
                    "server_id": 123,
                    "message_id": "1a08f7b1-51c8-4b46-929a-8879f349a3a2",
                    "collected_at": "2026-07-28T11:59:58Z",
                    "cpu_percent": 72.5,
                    "memory_percent": 61.2,
                    "memory_used": 1024,
                    "memory_total": 2048,
                    "disk_percent": 55.0,
                    "disk_used": 100,
                    "disk_total": 200,
                    "net_rx": 1000,
                    "net_tx": 800,
                    "temperature": null,
                    "load_avg": null
                  }
                }
                """;

        MetricsReportedMessage message = objectMapper.readValue(envelope, MetricsReportedMessage.class);

        assertEquals("9f4c2d10-8b7f-4c3d-a5e0-1ef5b67f2f1a", message.eventId());
        assertEquals("metrics.reported", message.eventType());
        assertEquals(1, message.schemaVersion());
        assertEquals("2026-07-28T12:00:00Z", message.occurredAt());
        assertEquals("metrics-service", message.producer());
        assertEquals(123L, message.payload().serverId());
        assertEquals("1a08f7b1-51c8-4b46-929a-8879f349a3a2", message.payload().messageId());
        assertEquals(0, new BigDecimal("72.5").compareTo(message.payload().cpuPercent()));
        assertEquals(1024L, message.payload().memoryUsed());
        assertNull(message.payload().temperature());
        assertNull(message.payload().loadAvg());
    }

    /** 冻结契约示例通过字段级运行校验。 */
    @Test
    void contractEnvelopeShouldPassRuntimeValidation() throws Exception {
        MetricsReportedMessage message = objectMapper.readValue(contractEnvelope(), MetricsReportedMessage.class);

        new MetricsReportedMessageValidator().validate(message);
    }

    /** 非法 UUID、非 UTC 时间、数值越界与容量不变量冲突应被拒绝。 */
    @Test
    void invalidContractFieldsShouldBeRejected() throws Exception {
        MetricsReportedMessage invalidUuid = objectMapper.readValue(
                contractEnvelope().replace("9f4c2d10-8b7f-4c3d-a5e0-1ef5b67f2f1a", "not-a-uuid"),
                MetricsReportedMessage.class);
        MetricsReportedMessage invalidPercent = objectMapper.readValue(
                contractEnvelope().replace("\"cpu_percent\": 72.5", "\"cpu_percent\": 100.1"),
                MetricsReportedMessage.class);
        MetricsReportedMessage invalidCapacity = objectMapper.readValue(
                contractEnvelope().replace("\"memory_total\": 2048", "\"memory_total\": 100"),
                MetricsReportedMessage.class);

        MetricsReportedMessageValidator validator = new MetricsReportedMessageValidator();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validate(invalidUuid))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validate(invalidPercent))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> validator.validate(invalidCapacity))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private String contractEnvelope() {
        return """
                {
                  "event_id": "9f4c2d10-8b7f-4c3d-a5e0-1ef5b67f2f1a",
                  "event_type": "metrics.reported",
                  "schema_version": 1,
                  "occurred_at": "2026-07-28T12:00:00Z",
                  "producer": "metrics-service",
                  "payload": {
                    "server_id": 123,
                    "message_id": "1a08f7b1-51c8-4b46-929a-8879f349a3a2",
                    "collected_at": "2026-07-28T11:59:58Z",
                    "cpu_percent": 72.5,
                    "memory_percent": 61.2,
                    "memory_used": 1024,
                    "memory_total": 2048,
                    "disk_percent": 55.0,
                    "disk_used": 100,
                    "disk_total": 200,
                    "net_rx": 1000,
                    "net_tx": 800,
                    "temperature": null,
                    "load_avg": null
                  }
                }
                """;
    }


    @Test
    void payloadShouldConvertToMetricsLatestVo() throws Exception {
        String envelope = """
                {"event_id":"e1","event_type":"metrics.reported","schema_version":1,
                 "occurred_at":"2026-07-28T12:00:00Z","producer":"metrics-service",
                 "payload":{"server_id":7,"message_id":"m1","collected_at":"2026-07-28T11:59:58Z",
                   "cpu_percent":90.0,"memory_percent":50.0,"memory_used":1,"memory_total":2,
                   "disk_percent":30.0,"disk_used":3,"disk_total":4,"net_rx":5,"net_tx":6,
                   "temperature":43.1,"load_avg":0.88}}
                """;

        MetricsReportedMessage message = objectMapper.readValue(envelope, MetricsReportedMessage.class);
        MetricsLatestVo vo = message.payload().toMetricsLatestVo();

        assertEquals(7L, vo.getServerId());
        assertEquals(0, new BigDecimal("90.0").compareTo(vo.getCpuPercent()));
        assertEquals(OffsetDateTime.parse("2026-07-28T11:59:58Z"), vo.getCollectedAt());
        assertEquals(0, new BigDecimal("0.88").compareTo(vo.getLoadAvg()));
        assertTrue(vo.getCollectedAt().getOffset().getTotalSeconds() == 0);
    }
}
