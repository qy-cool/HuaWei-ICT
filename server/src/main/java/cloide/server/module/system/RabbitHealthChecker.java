package cloide.server.module.system;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RabbitMQ 就绪探活（存活但未就绪语义）。
 *
 * <p>Broker 不可达时返回 false 而非抛异常，由 /api/ready 统一转为 503；
 * 应用本身不退出，Outbox 发布器继续退避重试。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "cloide.rabbitmq.enabled", havingValue = "true")
public class RabbitHealthChecker {

    private final ConnectionFactory connectionFactory;

    /** 注入连接工厂（starter 自动配置）。 */
    public RabbitHealthChecker(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    /**
     * 探测 Broker 连接是否可用。
     *
     * @return 连接创建成功且未关闭时为 true
     */
    public boolean isHealthy() {
        try (Connection connection = connectionFactory.createConnection()) {
            return connection != null && connection.isOpen();
        } catch (Exception exception) {
            log.warn("rabbitmq health check failed: {}", exception.getMessage());
            return false;
        }
    }
}
