package cloide.server.module.job;

import cloide.server.config.RabbitMqTopologyConfig;
import cloide.server.module.metrics.outbox.OutboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cloide.rabbitmq.enabled", havingValue = "true")
class RunWakeupEvents {
    static final String ROUTE = "job.wakeup.v1";
    private final OutboxService outbox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final AgentJobChannel channel;

    void enqueue(ProjectRun run) {
        String eventId = UUID.randomUUID().toString();
        var envelope = mapper.createObjectNode()
                .put("event_id", eventId).put("event_type", "job.wakeup").put("schema_version", 1)
                .put("occurred_at", Instant.now(clock).toString()).put("producer", "project-runs");
        envelope.putObject("payload").put("job_id", run.jobId()).put("server_id", run.serverId());
        outbox.enqueue("job.wakeup", ROUTE, envelope.toString(), eventId);
    }

    @RabbitListener(bindings = @QueueBinding(value = @Queue(value = "cloide.job.wakeup", durable = "true"),
            exchange = @Exchange(value = RabbitMqTopologyConfig.EVENTS_EXCHANGE, type = ExchangeTypes.TOPIC), key = ROUTE))
    public void consume(Message message) throws IOException {
        JsonNode envelope = mapper.readTree(message.getBody());
        JsonNode node = envelope == null ? null : envelope.path("payload").path("server_id");
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            throw new AmqpRejectAndDontRequeueException("job.wakeup.v1 缺少有效节点标识");
        }
        // 唤醒不携带动作，重新从数据库领取并检查实际授权。离线节点靠 ready 补调。
        channel.wake(node.longValue());
    }
}
