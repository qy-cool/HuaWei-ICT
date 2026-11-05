package cloide.server.module.metrics.outbox;

/**
 * Outbox 发布契约：轮询待发布事件并可靠投递到 RabbitMQ。
 */
public interface OutboxPublisherService {

    /**
     * 执行一轮发布：选取待发布行 → 逐行投递 → 按 Confirm 结果回写状态。
     *
     * @return 本轮成功发布的行数
     */
    int publishOnce();
}
