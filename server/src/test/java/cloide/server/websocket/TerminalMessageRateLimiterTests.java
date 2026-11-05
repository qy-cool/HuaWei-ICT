package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import cloide.server.config.AppProperties;
import cloide.server.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

/** 验证终端控制帧限流按消息类型、浏览器连接和会话独立计算。 */
class TerminalMessageRateLimiterTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** input 使用每会话独立桶，令牌按配置速率补充。 */
    @Test
    void shouldLimitInputPerMonitorAndSessionThenRefill() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-26T00:00:00Z"));
        AppProperties properties = new AppProperties();
        properties.getTerminal().setInputRatePerMinute(2);
        properties.getTerminal().setInputBurst(2);
        TerminalMessageRateLimiter limiter = new TerminalMessageRateLimiter(properties, clock);
        MonitorWebSocketSession monitor = monitor("monitor-1");
        TerminalMessage firstSession = message("terminal.input", "session_id", UUID.randomUUID().toString());
        TerminalMessage secondSession = message("terminal.input", "session_id", UUID.randomUUID().toString());

        assertTrue(limiter.allow(monitor, firstSession));
        assertTrue(limiter.allow(monitor, firstSession));
        assertFalse(limiter.allow(monitor, firstSession));
        assertTrue(limiter.allow(monitor, secondSession));

        clock.advanceSeconds(30);
        assertTrue(limiter.allow(monitor, firstSession));
    }

    /** 同一浏览器连接关闭后限流状态必须释放，重连不继承旧令牌消耗。 */
    @Test
    void releaseShouldResetAllBucketsForMonitor() {
        AppProperties properties = new AppProperties();
        properties.getTerminal().setOpenBurst(1);
        TerminalMessageRateLimiter limiter = new TerminalMessageRateLimiter(properties, Clock.systemUTC());
        MonitorWebSocketSession monitor = monitor("monitor-1");
        TerminalMessage open = message("terminal.open", "server_id", 1);

        assertTrue(limiter.allow(monitor, open));
        assertFalse(limiter.allow(monitor, open));
        limiter.release(monitor);
        assertTrue(limiter.allow(monitor, open));
    }

    /** 构造已认证的浏览器会话，socket ID 用作限流边界。 */
    private MonitorWebSocketSession monitor(String sessionId) {
        WebSocketSession socketSession = mock(WebSocketSession.class);
        when(socketSession.getId()).thenReturn(sessionId);
        AuthenticatedUser user = new AuthenticatedUser(1L, "approved-user", "user", "approved", null,
                OffsetDateTime.now(ZoneOffset.UTC));
        return new MonitorWebSocketSession(socketSession, user);
    }

    /** 构造限流器已知的控制帧，仅需 type 和作用域字段。 */
    private TerminalMessage message(String type, String field, Object value) {
        var payload = OBJECT_MAPPER.createObjectNode();
        if (value instanceof Integer number) {
            payload.put(field, number);
        } else {
            payload.put(field, (String) value);
        }
        return new TerminalMessage(type, UUID.randomUUID().toString(), OffsetDateTime.now(ZoneOffset.UTC).toString(), payload);
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
