package cloide.server.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 冻结的 RabbitMQ 拓扑声明（rabbitmq-topology-v1.md §二/§三）。
 *
 * <p>MVP-10 声明完整四件套：业务 Exchange、死信 Exchange、业务队列（带 DLX 参数）
 * 与死信队列。</p>
 *
 * <p>四个业务队列均已接入消费者：metrics→alert-evaluator、triggered→alert-notifier、
 * resolved→alert-resolved-notifier、explanation→ai-explainer（对照
 * rabbitmq-topology-v1.md §二/§十一）。因此业务队列出现积压不再属于"等待消费者接入"
 * 的过渡态，而是消费者故障或消费能力不足的信号，须按
 * {@code QueueBacklogProbeService} 的 backlog 告警定位，不可按正常状态放过。</p>
 *
 * <p>所有组件 durable + non-auto-delete；队列名不包含实例 ID。</p>
 */
@Configuration
@ConditionalOnProperty(name = "cloide.rabbitmq.enabled", havingValue = "true")
public class RabbitMqTopologyConfig {

    public static final String EVENTS_EXCHANGE = "cloide.events";
    public static final String DLX_EXCHANGE = "cloide.dlx";
    public static final String ALERT_METRICS_QUEUE = "cloide.alert.metrics";
    public static final String ALERT_METRICS_DLQ = "cloide.alert.metrics.dlq";
    public static final String METRICS_REPORTED_KEY = "metrics.reported.v1";
    public static final String ALERT_TRIGGERED_QUEUE = "cloide.alert.triggered";
    public static final String ALERT_TRIGGERED_DLQ = "cloide.alert.triggered.dlq";
    public static final String ALERT_TRIGGERED_KEY = "alert.triggered.v1";
    public static final String ALERT_RESOLVED_QUEUE = "cloide.alert.resolved";
    public static final String ALERT_RESOLVED_DLQ = "cloide.alert.resolved.dlq";
    public static final String ALERT_RESOLVED_KEY = "alert.resolved.v1";
    public static final String AI_EXPLANATION_QUEUE = "cloide.ai.alert.explanation";
    public static final String AI_EXPLANATION_DLQ = "cloide.ai.alert.explanation.dlq";
    public static final String AI_EXPLANATION_KEY = "ai.alert.explanation.requested.v1";

    /** 业务事件交换器。 */
    @Bean
    TopicExchange cloideEventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    /** 死信交换器。 */
    @Bean
    TopicExchange cloideDlxExchange() {
        return new TopicExchange(DLX_EXCHANGE, true, false);
    }

    /** Alert 消费 metrics.reported.v1 的业务队列（重试耗尽后进 DLX）。 */
    @Bean
    Queue cloideAlertMetricsQueue() {
        return QueueBuilder.durable(ALERT_METRICS_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(METRICS_REPORTED_KEY)
                .build();
    }

    /** 死信队列，不自动回投业务队列。 */
    @Bean
    Queue cloideAlertMetricsDlq() {
        return QueueBuilder.durable(ALERT_METRICS_DLQ).build();
    }

    /**
     * alert.triggered.v1 业务队列（出站告警触发事件）。
     *
     * <p>告警事件由 Outbox 发布器按行 routing_key 路由至此；消费者
     * {@code alert-notifier}（AlertTriggeredConsumer）在消费事务内为规则配置的渠道排程
     * 通知、提交后异步发送（2026-08-12 接入，见 rabbitmq-topology-v1.md §十一）。
     * 本队列积压意味着外部告警通知停发，须按故障处置而非视为正常等待。</p>
     */
    @Bean
    Queue cloideAlertTriggeredQueue() {
        return QueueBuilder.durable(ALERT_TRIGGERED_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(ALERT_TRIGGERED_KEY)
                .build();
    }

    /** alert.triggered.v1 死信队列，不自动回投业务队列。 */
    @Bean
    Queue cloideAlertTriggeredDlq() {
        return QueueBuilder.durable(ALERT_TRIGGERED_DLQ).build();
    }

    /**
     * alert.resolved.v1 业务队列（出站恢复事件，消费者 alert-resolved-notifier 已接入，
     * 驱动"恢复通知"排程；与 alert.triggered.v1 对称）。
     */
    @Bean
    Queue cloideAlertResolvedQueue() {
        return QueueBuilder.durable(ALERT_RESOLVED_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(ALERT_RESOLVED_KEY)
                .build();
    }

    /** alert.resolved.v1 死信队列，不自动回投业务队列。 */
    @Bean
    Queue cloideAlertResolvedDlq() {
        return QueueBuilder.durable(ALERT_RESOLVED_DLQ).build();
    }

    /**
     * ai.alert.explanation.requested.v1 业务队列（F1 告警智能解释请求，
     * 消费者 ai-explainer 已随开关接入，驱动解释生成与补充通知）。
     */
    @Bean
    Queue cloideAiExplanationQueue() {
        return QueueBuilder.durable(AI_EXPLANATION_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(AI_EXPLANATION_KEY)
                .build();
    }

    /** ai.alert.explanation.requested.v1 死信队列，不自动回投业务队列。 */
    @Bean
    Queue cloideAiExplanationDlq() {
        return QueueBuilder.durable(AI_EXPLANATION_DLQ).build();
    }

    /** 业务队列绑定：cloide.events -- metrics.reported.v1 --> cloide.alert.metrics。 */
    @Bean
    Binding alertMetricsBinding() {
        return BindingBuilder.bind(cloideAlertMetricsQueue())
                .to(cloideEventsExchange()).with(METRICS_REPORTED_KEY);
    }

    /** 死信队列绑定：cloide.dlx -- metrics.reported.v1 --> cloide.alert.metrics.dlq。 */
    @Bean
    Binding alertMetricsDlqBinding() {
        return BindingBuilder.bind(cloideAlertMetricsDlq())
                .to(cloideDlxExchange()).with(METRICS_REPORTED_KEY);
    }

    /** 业务队列绑定：cloide.events -- alert.triggered.v1 --> cloide.alert.triggered。 */
    @Bean
    Binding alertTriggeredBinding() {
        return BindingBuilder.bind(cloideAlertTriggeredQueue())
                .to(cloideEventsExchange()).with(ALERT_TRIGGERED_KEY);
    }

    /** 死信队列绑定：cloide.dlx -- alert.triggered.v1 --> cloide.alert.triggered.dlq。 */
    @Bean
    Binding alertTriggeredDlqBinding() {
        return BindingBuilder.bind(cloideAlertTriggeredDlq())
                .to(cloideDlxExchange()).with(ALERT_TRIGGERED_KEY);
    }

    /** 业务队列绑定：cloide.events -- alert.resolved.v1 --> cloide.alert.resolved。 */
    @Bean
    Binding alertResolvedBinding() {
        return BindingBuilder.bind(cloideAlertResolvedQueue())
                .to(cloideEventsExchange()).with(ALERT_RESOLVED_KEY);
    }

    /** 死信队列绑定：cloide.dlx -- alert.resolved.v1 --> cloide.alert.resolved.dlq。 */
    @Bean
    Binding alertResolvedDlqBinding() {
        return BindingBuilder.bind(cloideAlertResolvedDlq())
                .to(cloideDlxExchange()).with(ALERT_RESOLVED_KEY);
    }

    /** 业务队列绑定：cloide.events -- ai.alert.explanation.requested.v1 --> cloide.ai.alert.explanation。 */
    @Bean
    Binding aiExplanationBinding() {
        return BindingBuilder.bind(cloideAiExplanationQueue())
                .to(cloideEventsExchange()).with(AI_EXPLANATION_KEY);
    }

    /** 死信队列绑定：cloide.dlx -- ai.alert.explanation.requested.v1 --> cloide.ai.alert.explanation.dlq。 */
    @Bean
    Binding aiExplanationDlqBinding() {
        return BindingBuilder.bind(cloideAiExplanationDlq())
                .to(cloideDlxExchange()).with(AI_EXPLANATION_KEY);
    }
}
