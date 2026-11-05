package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloide.server.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 验证 Agent 消息限流的独立桶、突发额度、时间补充与关闭释放。 */
class AgentMessageRateLimiterTests {

    /** heartbeat 和 Metrics 使用独立桶，令牌在一分钟内按配置速率补充。 */
    @Test
    void shouldLimitAndRefillMessageBuckets() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-25T00:00:00Z"));
        AppProperties properties = new AppProperties();
        properties.getAgent().setHeartbeatRatePerMinute(2);
        properties.getAgent().setHeartbeatBurst(2);
        properties.getAgent().setMetricsRatePerMinute(1);
        properties.getAgent().setMetricsBurst(1);
        AgentMessageRateLimiter limiter = new AgentMessageRateLimiter(properties, clock);

        assertTrue(limiter.allowHeartbeat("session"));
        assertTrue(limiter.allowHeartbeat("session"));
        assertFalse(limiter.allowHeartbeat("session"));
        assertTrue(limiter.allowMetrics("session"));
        assertFalse(limiter.allowMetrics("session"));

        clock.advanceSeconds(30);
        assertTrue(limiter.allowHeartbeat("session"));
        assertFalse(limiter.allowMetrics("session"));
        clock.advanceSeconds(30);
        assertTrue(limiter.allowMetrics("session"));
    }

    /** 关闭后清除状态，重连会获得独立的新令牌桶。 */
    @Test
    void releaseShouldResetSessionBuckets() {
        AgentMessageRateLimiter limiter = new AgentMessageRateLimiter(new AppProperties(), Clock.systemUTC());
        assertTrue(limiter.allowMetrics("session"));
        limiter.release("session");
        assertTrue(limiter.allowMetrics("session"));
    }

    /** 提供测试可推进的 UTC 时钟。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
