package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cloide.server.security.AuthenticatedUser;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.socket.WebSocketSession;

/** 验证 Monitor 订阅的幂等性和断开清理。 */
class MonitorSubscriptionRegistryTests {

    /** 验证重复订阅不会产生重复订阅者，移除后不再返回。 */
    @Test
    void duplicateSubscriptionShouldBeIdempotent() {
        MonitorSubscriptionRegistry registry = new MonitorSubscriptionRegistry();
        WebSocketSession socket = Mockito.mock(WebSocketSession.class);
        Mockito.when(socket.getId()).thenReturn("session-1");
        MonitorWebSocketSession session = new MonitorWebSocketSession(socket,
                new AuthenticatedUser(1L, "admin", "admin", "approved", null, OffsetDateTime.now()));

        registry.register(session);
        registry.subscribe(session, 11L);
        registry.subscribe(session, 11L);
        assertEquals(1, registry.subscribers(11L).size());

        registry.remove(session);
        assertEquals(0, registry.subscribers(11L).size());
    }
}
