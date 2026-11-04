package cloide.server.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cloide.server.module.metrics.outbox.OutboxPublisherService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 验证 Outbox 发布调度器的触发与异常隔离（不依赖真实 Broker）。
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherSchedulerTests {

    @Mock
    private OutboxPublisherService outboxPublisherService;

    /** 调度触发应调用一轮发布。 */
    @Test
    void scheduledRoundShouldPublishOnce() {
        OutboxPublisherScheduler scheduler = new OutboxPublisherScheduler(outboxPublisherService);
        when(outboxPublisherService.publishOnce()).thenReturn(2);

        scheduler.publishPendingEvents();

        verify(outboxPublisherService).publishOnce();
    }

    /** 单轮失败不应向上抛出，保证后续调度继续。 */
    @Test
    void failedRoundShouldNotPropagate() {
        OutboxPublisherScheduler scheduler = new OutboxPublisherScheduler(outboxPublisherService);
        doThrow(new IllegalStateException("broker down")).when(outboxPublisherService).publishOnce();

        scheduler.publishPendingEvents();

        verify(outboxPublisherService).publishOnce();
    }
}
