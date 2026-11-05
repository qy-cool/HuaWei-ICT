package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloide.server.config.AppProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** 验证 Agent 终端输出按服务端会话和 Base64 原始字节数限流。 */
class TerminalOutputRateLimiterTests {

    /** 令牌耗尽后拒绝输出，固定时钟推进后按字节速率恢复令牌。 */
    @Test
    void shouldRejectExhaustedOutputThenRefillWithFixedClock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-26T00:00:00Z"));
        TerminalOutputRateLimiter limiter = limiter(4, 4, clock);
        String data = base64(4);

        assertTrue(limiter.allow("session-1", data));
        assertFalse(limiter.allow("session-1", base64(1)));

        clock.advanceSeconds(1);
        assertTrue(limiter.allow("session-1", data));
    }

    /** 不同服务端会话使用独立令牌桶，不相互消耗带宽额度。 */
    @Test
    void shouldIsolateTokensBetweenSessions() {
        TerminalOutputRateLimiter limiter = limiter(1, 1, Clock.systemUTC());
        String data = base64(1);

        assertTrue(limiter.allow("session-1", data));
        assertFalse(limiter.allow("session-1", data));
        assertTrue(limiter.allow("session-2", data));
    }

    /** 关闭会话后释放桶，后续同一 session ID 不继承此前的令牌消耗。 */
    @Test
    void releaseShouldDiscardSessionBucket() {
        TerminalOutputRateLimiter limiter = limiter(1, 1, Clock.systemUTC());
        String data = base64(1);

        assertTrue(limiter.allow("session-1", data));
        limiter.release("session-1");

        assertTrue(limiter.allow("session-1", data));
    }

    /** 创建测试限流器并覆盖输出字节速率和突发容量。 */
    private TerminalOutputRateLimiter limiter(int rateBytesPerSecond, int burstBytes, Clock clock) {
        AppProperties properties = new AppProperties();
        properties.getTerminal().setOutputRateBytesPerSecond(rateBytesPerSecond);
        properties.getTerminal().setOutputBurstBytes(burstBytes);
        return new TerminalOutputRateLimiter(properties, clock);
    }

    /** 构造指定原始字节数的 Base64 输出。 */
    private String base64(int byteCount) {
        return Base64.getEncoder().encodeToString(new byte[byteCount]);
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
