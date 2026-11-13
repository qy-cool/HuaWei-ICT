package org.cloide.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderEvents {
    record Event(UUID eventId, UUID orderId, int orderVersion, String status) {}
    private final JdbcTemplate db;
    private final RabbitTemplate queue;
    private final ObjectMapper json;

    public OrderEvents(JdbcTemplate db, RabbitTemplate queue, ObjectMapper json) {
        this.db = db;
        this.queue = queue;
        this.json = json;
    }

    @Scheduled(fixedDelayString="${orders.outbox.delay:2000}")
    public void publish() throws Exception {
        var pending = db.query("SELECT event_id,order_id,order_version,status FROM order_outbox WHERE sent=false "
                + "ORDER BY order_id,order_version LIMIT 32", new DataClassRowMapper<>(Event.class));
        for (var event : pending) {
            var confirm = new CorrelationData();
            queue.convertAndSend("", "orders.events", json.writeValueAsString(event), message -> {
                message.getMessageProperties().setMessageId(event.eventId().toString());
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            }, confirm);
            if (!confirm.getFuture().get(5, TimeUnit.SECONDS).isAck() || confirm.getReturned() != null) {
                throw new IllegalStateException("订单事件尚未进入队列");
            }
            db.update("UPDATE order_outbox SET sent=true WHERE event_id=?", event.eventId());
        }
    }

    @RabbitListener(queues="orders.events")
    @Transactional
    public void consume(String body) throws Exception {
        var event = json.readValue(body, Event.class);
        db.update("INSERT INTO order_event_receipts(event_id) VALUES (?) ON CONFLICT (event_id) DO NOTHING", event.eventId());
    }
}
