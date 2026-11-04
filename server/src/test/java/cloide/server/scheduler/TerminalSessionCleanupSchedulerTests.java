package cloide.server.scheduler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import cloide.server.module.terminal.service.TerminalSessionService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** 验证终端会话清理调度器只触发服务并隔离单轮异常。 */
class TerminalSessionCleanupSchedulerTests {

    /** 验证调度器会调用终端会话过期清理服务。 */
    @Test
    void schedulerShouldTriggerCleanupService() {
        TerminalSessionService service = Mockito.mock(TerminalSessionService.class);
        TerminalSessionCleanupScheduler scheduler = new TerminalSessionCleanupScheduler(service);

        scheduler.cleanupExpiredTerminalSessions();

        verify(service).closeExpiredSessions();
    }

    /** 验证清理服务异常不会从调度入口继续向外抛出。 */
    @Test
    void schedulerShouldIsolateCleanupFailure() {
        TerminalSessionService service = Mockito.mock(TerminalSessionService.class);
        doThrow(new IllegalStateException("cleanup failed")).when(service).closeExpiredSessions();
        TerminalSessionCleanupScheduler scheduler = new TerminalSessionCleanupScheduler(service);

        scheduler.cleanupExpiredTerminalSessions();

        verify(service).closeExpiredSessions();
    }
}
