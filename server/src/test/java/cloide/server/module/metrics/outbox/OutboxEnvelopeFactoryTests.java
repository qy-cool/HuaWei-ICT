package cloide.server.module.metrics.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cloide.server.module.metrics.entity.MetricsEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * 验证冻结信封（message-contracts-v1.md §二/§三）与实现序列化结果一致。
 */
class OutboxEnvelopeFactoryTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-31T10:00:00Z"), ZoneOffset.UTC);
    private static final String EVENT_ID = "9f4c2d10-8b7f-4c3d-a5e0-1ef5b67f2f1a";
    private static final String MESSAGE_ID = "1a08f7b1-51c8-4b46-929a-8879f349a3a2";

    private final OutboxEnvelopeFactory factory = new OutboxEnvelopeFactory(new ObjectMapper(), CLOCK);

    /** 信封头字段与契约 §二 一致（event_id/event_type/schema_version/occurred_at/producer/payload）。 */
    @Test
    void envelopeHeaderShouldMatchContract() throws Exception {
        JsonNode envelope = new ObjectMapper().readTree(factory.build(entityWithNullableFields(), MESSAGE_ID, EVENT_ID));

        assertEquals(EVENT_ID, envelope.get("event_id").asText());
        assertEquals("metrics.reported", envelope.get("event_type").asText());
        assertEquals(1, envelope.get("schema_version").asInt());
        assertEquals("2026-07-31T10:00:00Z", envelope.get("occurred_at").asText());
        assertEquals("metrics-service", envelope.get("producer").asText());
        assertTrue(envelope.has("payload"));
        // 可选字段本阶段（MVP-10）不携带。
        assertNull(envelope.get("trace_id"));
        assertNull(envelope.get("correlation_id"));
    }

    /** payload 字段与契约 §三 一致（snake_case、UTC 时间、数值完整）。 */
    @Test
    void payloadShouldMatchContract() throws Exception {
        JsonNode payload = new ObjectMapper().readTree(
                factory.build(entityWithNullableFields(), MESSAGE_ID, EVENT_ID)).get("payload");

        assertEquals(123L, payload.get("server_id").asLong());
        assertEquals(MESSAGE_ID, payload.get("message_id").asText());
        assertEquals("2026-07-31T09:59:58Z", payload.get("collected_at").asText());
        assertEquals(0, new BigDecimal("72.5").compareTo(payload.get("cpu_percent").decimalValue()));
        assertEquals(0, new BigDecimal("61.2").compareTo(payload.get("memory_percent").decimalValue()));
        assertEquals(1024L, payload.get("memory_used").asLong());
        assertEquals(2048L, payload.get("memory_total").asLong());
        assertEquals(0, new BigDecimal("55.0").compareTo(payload.get("disk_percent").decimalValue()));
        assertEquals(100L, payload.get("disk_used").asLong());
        assertEquals(200L, payload.get("disk_total").asLong());
        assertEquals(1000L, payload.get("net_rx").asLong());
        assertEquals(800L, payload.get("net_tx").asLong());
        assertTrue(payload.get("temperature").isNull());
        assertTrue(payload.get("load_avg").isNull());
    }

    /** temperature/load_avg 有值时输出数值而非 null。 */
    @Test
    void nullableFieldsShouldCarryValuesWhenPresent() throws Exception {
        MetricsEntity entity = entityWithNullableFields();
        entity.setTemperature(new BigDecimal("43.1"));
        entity.setLoadAvg(new BigDecimal("0.88"));

        JsonNode payload = new ObjectMapper().readTree(
                factory.build(entity, MESSAGE_ID, EVENT_ID)).get("payload");

        assertEquals(0, new BigDecimal("43.1").compareTo(payload.get("temperature").decimalValue()));
        assertEquals(0, new BigDecimal("0.88").compareTo(payload.get("load_avg").decimalValue()));
    }

    private MetricsEntity entityWithNullableFields() {
        MetricsEntity entity = new MetricsEntity();
        entity.setServerId(123L);
        entity.setCpuPercent(new BigDecimal("72.5"));
        entity.setMemoryPercent(new BigDecimal("61.2"));
        entity.setMemoryUsed(1024L);
        entity.setMemoryTotal(2048L);
        entity.setDiskPercent(new BigDecimal("55.0"));
        entity.setDiskUsed(100L);
        entity.setDiskTotal(200L);
        entity.setNetRx(1000L);
        entity.setNetTx(800L);
        entity.setCollectedAt(LocalDateTime.of(2026, 7, 31, 9, 59, 58));
        return entity;
    }
}
