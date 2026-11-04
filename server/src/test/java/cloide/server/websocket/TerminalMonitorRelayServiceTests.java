package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.terminal.entity.TerminalSessionEntity;
import cloide.server.module.terminal.service.TerminalSessionService;
import cloide.server.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;

/** 验证浏览器终端控制帧按会话归属定向转发到对应 Agent。 */
class TerminalMonitorRelayServiceTests {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** terminal.open 必须由服务生成 session_id 并将 server_id 注入 Agent 载荷。 */
    @Test
    void openShouldBindAndRelayToAgent() throws Exception {
        TerminalSessionService sessions = mock(TerminalSessionService.class);
        AgentConnectionRegistry agents = mock(AgentConnectionRegistry.class);
        TerminalRelayRegistry relays = new TerminalRelayRegistry();
        MonitorWebSocketSession monitor = monitor(7L);
        TerminalSessionEntity terminalSession = session("session-1", 9L, 7L);
        when(sessions.openSession(eq(7L), eq(9L), any())).thenReturn(terminalSession);
        when(agents.sendToServer(eq(9L), any(TextMessage.class))).thenReturn(true);
        TerminalMonitorRelayService service = service(sessions, relays, agents);

        service.relay(monitor, message("terminal.open", "server_id", 9, "cols", 80, "rows", 24));

        org.mockito.ArgumentCaptor<TextMessage> captor = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        verify(agents).sendToServer(eq(9L), captor.capture());
        var body = OBJECT_MAPPER.readTree(captor.getValue().getPayload());
        assertEquals("session-1", body.path("payload").path("session_id").asText());
        assertEquals(9L, body.path("payload").path("server_id").asLong());
        assertEquals(monitor, relays.get("session-1").monitorSession());
    }

    /** 已绑定会话只能由原浏览器连接继续发送控制帧。 */
    @Test
    void inputShouldRejectDifferentMonitorSession() throws Exception {
        TerminalSessionService sessions = mock(TerminalSessionService.class);
        AgentConnectionRegistry agents = mock(AgentConnectionRegistry.class);
        TerminalRelayRegistry relays = new TerminalRelayRegistry();
        MonitorWebSocketSession owner = monitor(7L);
        MonitorWebSocketSession attacker = monitor(7L);
        TerminalSessionEntity terminalSession = session("session-1", 9L, 7L);
        relays.bind("session-1", 9L, owner);
        when(sessions.requireActiveSession(7L, "session-1")).thenReturn(terminalSession);
        TerminalMonitorRelayService service = service(sessions, relays, agents);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.relay(attacker, message("terminal.input", "session_id", "session-1", "data", "YQ==")));

        assertEquals(ErrorCode.TERMINAL_ACCESS_DENIED, exception.getErrorCode());
    }

    /** 构造与当前协议一致的 UTC 终端消息。 */
    private TerminalMessage message(String type, Object... fields) {
        var payload = OBJECT_MAPPER.createObjectNode();
        for (int index = 0; index < fields.length; index += 2) {
            String key = (String) fields[index];
            Object value = fields[index + 1];
            if (value instanceof Integer integer) {
                payload.put(key, integer);
            } else {
                payload.put(key, (String) value);
            }
        }
        return new TerminalMessage(type, UUID.randomUUID().toString(),
                OffsetDateTime.now(ZoneOffset.UTC).toString(), payload);
    }

    /** 构造已审核浏览器会话包装。 */
    private MonitorWebSocketSession monitor(Long userId) {
        AuthenticatedUser user = new AuthenticatedUser(userId, "approved-user", "user", "approved", null,
                OffsetDateTime.now(ZoneOffset.UTC));
        return new MonitorWebSocketSession(mock(org.springframework.web.socket.WebSocketSession.class), user);
    }

    /** 构造数据库返回的活动终端会话。 */
    private TerminalSessionEntity session(String sessionId, Long serverId, Long userId) {
        TerminalSessionEntity session = new TerminalSessionEntity();
        session.setSessionId(sessionId);
        session.setServerId(serverId);
        session.setUserId(userId);
        return session;
    }

    /** 创建使用固定 UTC Clock 的中继服务。 */
    private TerminalMonitorRelayService service(TerminalSessionService sessions, TerminalRelayRegistry relays,
            AgentConnectionRegistry agents) {
        return new TerminalMonitorRelayService(sessions, relays, agents, OBJECT_MAPPER,
                Clock.fixed(Instant.parse("2026-07-26T00:00:00Z"), ZoneOffset.UTC));
    }
}
