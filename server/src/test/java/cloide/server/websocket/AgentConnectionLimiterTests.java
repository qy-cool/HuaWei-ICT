package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloide.server.config.AppProperties;
import org.junit.jupiter.api.Test;

/** 验证 Agent 总连接与未认证连接配额的申请、认证迁移和释放行为。 */
class AgentConnectionLimiterTests {

    /** 未认证配额耗尽后拒绝新会话，认证完成释放未认证额度但保留总连接额度。 */
    @Test
    void shouldEnforceAndReleaseConnectionLimits() {
        AppProperties properties = properties(2, 1);
        AgentConnectionLimiter limiter = new AgentConnectionLimiter(properties);

        assertTrue(limiter.admit("one"));
        assertFalse(limiter.admit("two"));
        limiter.authenticate("one");
        assertTrue(limiter.admit("two"));
        assertEquals(2, limiter.admittedCount());
        assertEquals(1, limiter.unauthenticatedCount());

        limiter.release("one");
        assertFalse(limiter.admit("three"));
        limiter.authenticate("two");
        assertTrue(limiter.admit("three"));
        assertEquals(2, limiter.admittedCount());
    }

    private AppProperties properties(int maxConnections, int maxUnauthenticatedConnections) {
        AppProperties properties = new AppProperties();
        properties.getAgent().setMaxConnections(maxConnections);
        properties.getAgent().setMaxUnauthenticatedConnections(maxUnauthenticatedConnections);
        return properties;
    }

    private void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
