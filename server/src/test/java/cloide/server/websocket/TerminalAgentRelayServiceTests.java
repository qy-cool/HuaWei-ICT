package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.terminal.enums.TerminalSessionStatus;
import cloide.server.module.terminal.service.TerminalSessionService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/** 验证 Agent 终端响应按服务器和会话归属回送原浏览器。 */
class TerminalAgentRelayServiceTests {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SESSION_ID = "c4c942a2-8584-433f-8397-6aef8ea79391";

    /** Agent 的 terminal.opened 应更新元数据并回送原浏览器。 */
    @Test
    void openedShouldUpdateSessionAndForwardToMonitor() throws Exception {
        TerminalSessionService sessions = mock(TerminalSessionService.class);
        TerminalRelayRegistry registry = new TerminalRelayRegistry();
        MonitorWebSocketSession monitor = monitor();
        registry.bind(SESSION_ID, 9L, monitor);
        AgentWebSocketSession agent = agent(9L);
        TerminalAgentRelayService service = service(sessions, registry, mock(TerminalOutputRateLimiter.class),
                mock(TerminalRelayLifecycleService.class));

        service.relay(agent, message("terminal.opened", "session_id", SESSION_ID, "shell", "bash"));

        verify(sessions).markOpened(SESSION_ID, "bash");
        verify(monitor.socketSession()).sendMessage(any(TextMessage.class));
    }

    /** 伪造其他服务器 ID 的 Agent 响应不得路由到浏览器。 */
    @Test
    void responseShouldRejectDifferentAgentServer() {
        TerminalRelayRegistry registry = new TerminalRelayRegistry();
        registry.bind(SESSION_ID, 9L, monitor());
        TerminalAgentRelayService service = service(mock(TerminalSessionService.class), registry,
                mock(TerminalOutputRateLimiter.class), mock(TerminalRelayLifecycleService.class));

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.relay(agent(8L), message("terminal.opened", "session_id", SESSION_ID, "shell", "bash")));

        assertEquals(ErrorCode.TERMINAL_ACCESS_DENIED, exception.getErrorCode());
    }

    /** 超额输出不得发送到 Monitor，必须由生命周期服务关闭绑定并通知 Agent。 */
    @Test
    void overLimitOutputShouldCloseBindingWithoutForwardingToMonitor() throws Exception {
        TerminalRelayRegistry registry = new TerminalRelayRegistry();
        MonitorWebSocketSession monitor = monitor();
        registry.bind(SESSION_ID, 9L, monitor);
        TerminalSessionService sessions = mock(TerminalSessionService.class);
        AgentConnectionRegistry agents = mock(AgentConnectionRegistry.class);
        when(agents.sendToServer(eq(9L), any(TextMessage.class))).thenReturn(true);
        TerminalOutputRateLimiter limiter = mock(TerminalOutputRateLimiter.class);
        when(limiter.allow(eq(SESSION_ID), any())).thenReturn(false);
        TerminalRelayLifecycleService lifecycle = new TerminalRelayLifecycleService(sessions, registry, agents, limiter,
                OBJECT_MAPPER, Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC));

        service(sessions, registry, limiter, lifecycle).relay(agent(9L),
                message("terminal.output", "session_id", SESSION_ID, "data", "YQ=="));

        verify(agents).sendToServer(eq(9L), any(TextMessage.class));
        verify(sessions).closeSession(SESSION_ID, TerminalSessionStatus.CLOSED.value(), "output_rate_exceeded");
        verify(limiter).release(SESSION_ID);
        verify(monitor.socketSession(), never()).sendMessage(any(TextMessage.class));
        org.junit.jupiter.api.Assertions.assertNull(registry.get(SESSION_ID));
    }

    /** 正常关闭必须委托生命周期服务释放输出桶并移除会话绑定。 */
    @Test
    void closedShouldUseLifecycleClosureBeforeForwarding() throws Exception {
        TerminalSessionService sessions = mock(TerminalSessionService.class);
        TerminalRelayRegistry registry = new TerminalRelayRegistry();
        MonitorWebSocketSession monitor = monitor();
        registry.bind(SESSION_ID, 9L, monitor);
        TerminalRelayLifecycleService lifecycle = mock(TerminalRelayLifecycleService.class);

        service(sessions, registry, mock(TerminalOutputRateLimiter.class), lifecycle).relay(agent(9L),
                message("terminal.closed", "session_id", SESSION_ID, "reason", "shell_exit"));

        verify(lifecycle).closeBinding(eq(registry.get(SESSION_ID)), eq(TerminalSessionStatus.CLOSED.value()),
                eq("shell_exit"));
        verify(monitor.socketSession()).sendMessage(any(TextMessage.class));
    }

    /** 构造受测服务并固定时间戳来源，避免断言受系统时钟影响。 */
    private TerminalAgentRelayService service(TerminalSessionService sessions, TerminalRelayRegistry registry,
            TerminalOutputRateLimiter limiter, TerminalRelayLifecycleService lifecycle) {
        return new TerminalAgentRelayService(sessions, registry, limiter, lifecycle, OBJECT_MAPPER,
                Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC));
    }

    private TerminalMessage message(String type, String sessionField, String sessionId, String valueField, String value) {
        var payload = OBJECT_MAPPER.createObjectNode().put("server_id", 9).put(sessionField, sessionId).put(valueField, value);
        return new TerminalMessage(type, UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC).toString(), payload);
    }

    private MonitorWebSocketSession monitor() {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.isOpen()).thenReturn(true);
        return new MonitorWebSocketSession(socket, new cloide.server.security.AuthenticatedUser(
                1L, "user", "user", "approved", null, OffsetDateTime.now(ZoneOffset.UTC)));
    }

    private AgentWebSocketSession agent(Long serverId) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("agent-" + serverId);
        when(socket.isOpen()).thenReturn(true);
        AgentWebSocketSession session = new AgentWebSocketSession(socket, Clock.systemUTC());
        session.authenticate(serverId, java.time.LocalDateTime.now());
        return session;
    }
}
