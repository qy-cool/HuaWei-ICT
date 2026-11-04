package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cloide.server.config.AppProperties;
import cloide.server.module.server.mapper.ServerMapper;
import cloide.server.security.AuthenticatedUser;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.SessionLimitExceededException;

/** 验证 Monitor 出站保护的装饰器、异常转换和幂等收口。 */
class MonitorBackpressureProtectionTests {

    /** Handler 必须以配置的时间和缓冲参数创建 TERMINATE 策略的受保护会话。 */
    @Test
    void connectionShouldRegisterConfiguredProtectedSession() throws Exception {
        MonitorSubscriptionRegistry registry = mock(MonitorSubscriptionRegistry.class);
        WebSocketSession socket = monitorSocket();
        AppProperties properties = new AppProperties();
        properties.getTerminal().setMonitorSendTimeLimitMillis(3210);
        properties.getTerminal().setMonitorBufferSizeBytes(654321);
        MonitorWebSocketHandler handler = new MonitorWebSocketHandler(new ObjectMapper(), mock(ServerMapper.class), registry,
                Clock.systemUTC(), null, null, null, null, properties);

        handler.afterConnectionEstablished(socket);

        ArgumentCaptor<MonitorWebSocketSession> captor = ArgumentCaptor.forClass(MonitorWebSocketSession.class);
        verify(registry).register(captor.capture());
        ConcurrentWebSocketSessionDecorator decorator =
                (ConcurrentWebSocketSessionDecorator) captor.getValue().socketSession();
        assertEquals(3210, decorator.getSendTimeLimit());
        assertEquals(654321, decorator.getBufferSizeLimit());
    }

    /** Spring 发送限额异常必须转换为项目背压异常，而不是误认为普通 I/O。 */
    @Test
    void sendShouldConvertSpringLimitExceptionToMonitorBackpressureException() throws Exception {
        WebSocketSession socket = monitorSocket();
        org.mockito.Mockito.doThrow(new SessionLimitExceededException("buffer exhausted", CloseStatus.SESSION_NOT_RELIABLE))
                .when(socket).sendMessage(any(TextMessage.class));
        MonitorWebSocketSession monitor = new MonitorWebSocketSession(socket, null);

        assertThrows(MonitorBackpressureException.class, () -> monitor.send(new TextMessage("payload")));
    }

    /** 背压收口只能执行一次，且必须保留 monitor_backpressure 原因。 */
    @Test
    void backpressureTerminationShouldCloseOnceWithBackpressureReason() throws Exception {
        TerminalRelayLifecycleService lifecycle = mock(TerminalRelayLifecycleService.class);
        MonitorSubscriptionRegistry registry = mock(MonitorSubscriptionRegistry.class);
        TerminalMessageRateLimiter rateLimiter = mock(TerminalMessageRateLimiter.class);
        WebSocketSession socket = monitorSocket();
        MonitorWebSocketSession monitor = new MonitorWebSocketSession(socket, null);
        MonitorSessionTerminationService service = new MonitorSessionTerminationService(lifecycle, registry, rateLimiter);

        service.terminateForBackpressure(monitor);
        service.terminateNormally(monitor);

        verify(lifecycle, times(1)).closeMonitorSessions(monitor, "monitor_backpressure");
        verify(rateLimiter, times(1)).release(monitor);
        verify(registry, times(1)).remove(monitor);
        verify(socket, times(1)).close(CloseStatus.SESSION_NOT_RELIABLE);
    }

    /** 创建携带最小认证属性的开放浏览器会话替身。 */
    private WebSocketSession monitorSocket() {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn("monitor-test");
        when(socket.isOpen()).thenReturn(true);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("authenticated_user", new AuthenticatedUser(
                1L, "user", "user", "approved", null, OffsetDateTime.now()));
        when(socket.getAttributes()).thenReturn(attributes);
        return socket;
    }
}
