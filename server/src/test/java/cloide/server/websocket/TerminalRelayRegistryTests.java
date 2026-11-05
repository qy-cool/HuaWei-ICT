package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

/** 验证单 JVM 终端中继注册表的会话归属和条件移除行为。 */
class TerminalRelayRegistryTests {

    /** 同一会话只允许绑定到同一浏览器和服务器。 */
    @Test
    void bindShouldRejectDifferentOwnership() {
        TerminalRelayRegistry registry = new TerminalRelayRegistry();
        MonitorWebSocketSession first = mock(MonitorWebSocketSession.class);
        MonitorWebSocketSession second = mock(MonitorWebSocketSession.class);

        assertTrue(registry.bind("session-1", 1L, first));
        assertTrue(registry.bind("session-1", 1L, first));
        assertFalse(registry.bind("session-1", 1L, second));
        assertEquals(first, registry.get("session-1").monitorSession());
    }

    /** 浏览器断开只移除其自身创建的会话路由。 */
    @Test
    void removeByMonitorSessionShouldPreserveOtherBindings() {
        TerminalRelayRegistry registry = new TerminalRelayRegistry();
        MonitorWebSocketSession first = mock(MonitorWebSocketSession.class);
        MonitorWebSocketSession second = mock(MonitorWebSocketSession.class);
        registry.bind("session-1", 1L, first);
        registry.bind("session-2", 1L, second);

        assertEquals(1, registry.removeByMonitorSession(first).size());
        assertNull(registry.get("session-1"));
        assertEquals(second, registry.get("session-2").monitorSession());
    }
}
