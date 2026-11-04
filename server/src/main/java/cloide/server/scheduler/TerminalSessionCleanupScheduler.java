package cloide.server.scheduler;

import cloide.server.module.terminal.service.TerminalSessionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按配置定时收口空闲或超过最大生命周期的终端会话。
 */
@Slf4j
@Component
public class TerminalSessionCleanupScheduler {

    private final TerminalSessionService terminalSessionService;

    /**
     * 构造终端会话清理调度器。
     *
     * @param terminalSessionService 终端会话生命周期服务
     */
    public TerminalSessionCleanupScheduler(TerminalSessionService terminalSessionService) {
        this.terminalSessionService = terminalSessionService;
    }

    /**
     * 执行一次终端会话过期清理；单轮失败不影响后续调度。
     */
    @Scheduled(cron = "${cloide.terminal.cleanup-cron}")
    public void cleanupExpiredTerminalSessions() {
        try {
            terminalSessionService.closeExpiredSessions();
        } catch (RuntimeException exception) {
            log.error("Terminal session cleanup failed", exception);
        }
    }
}
