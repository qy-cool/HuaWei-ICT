package cloide.server.websocket;

import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;

/** 以幂等顺序收口 Monitor 连接关联的终端路由和指标订阅。 */
@Component
public class MonitorSessionTerminationService {

    private static final String MONITOR_DISCONNECTED_REASON = "monitor_disconnected";
    private static final String MONITOR_BACKPRESSURE_REASON = "monitor_backpressure";

    private final TerminalRelayLifecycleService terminalRelayLifecycleService;
    private final MonitorSubscriptionRegistry registry;
    private final TerminalMessageRateLimiter terminalMessageRateLimiter;

    /** 注入终端生命周期服务和 Monitor 订阅索引。 */
    public MonitorSessionTerminationService(TerminalRelayLifecycleService terminalRelayLifecycleService,
            MonitorSubscriptionRegistry registry, TerminalMessageRateLimiter terminalMessageRateLimiter) {
        this.terminalRelayLifecycleService = terminalRelayLifecycleService;
        this.registry = registry;
        this.terminalMessageRateLimiter = terminalMessageRateLimiter;
    }

    /** 按正常断连原因收口；若背压已先取得收口权，不覆盖其持久化原因。 */
    public void terminateNormally(MonitorWebSocketSession monitorSession) {
        terminate(monitorSession, MONITOR_DISCONNECTED_REASON, false);
    }

    /** 按慢消费者原因收口，并关闭已失去可靠性的浏览器 WebSocket。 */
    public void terminateForBackpressure(MonitorWebSocketSession monitorSession) {
        terminate(monitorSession, MONITOR_BACKPRESSURE_REASON, true);
    }

    /** 只允许第一个触发方关闭终端、移除订阅和关闭套接字。 */
    private void terminate(MonitorWebSocketSession monitorSession, String reason, boolean closeSocket) {
        if (!monitorSession.beginTermination()) {
            return;
        }
        terminalRelayLifecycleService.closeMonitorSessions(monitorSession, reason);
        terminalMessageRateLimiter.release(monitorSession);
        registry.remove(monitorSession);
        if (closeSocket) {
            try {
                monitorSession.socketSession().close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (IOException ignored) {
                // 缓冲溢出后底层连接可能已关闭，终端和订阅状态已在前面完成收口。
            }
        }
    }
}
