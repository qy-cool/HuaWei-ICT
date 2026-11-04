package cloide.server.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.security.AuthenticatedUser;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 验证 Monitor ticket 的短时、一次性消费和过期清理规则。 */
class MonitorTicketServiceTests {

    /** 验证同一 ticket 只能消费一次。 */
    @Test
    void ticketShouldBeSingleUse() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-22T00:00:00Z"));
        MonitorTicketService service = new MonitorTicketServiceImpl(Duration.ofSeconds(30), clock);
        AuthenticatedUser user = new AuthenticatedUser(1L, "admin", "admin", "approved", null,
                OffsetDateTime.now());

        MonitorTicketVo ticket = service.issue(user);

        assertEquals(user, service.consume(ticket.ticket()));
        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.consume(ticket.ticket()));
        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    /** 验证 30 秒边界前可消费，边界时立即拒绝。 */
    @Test
    void ticketShouldExpireAtThirtySecondBoundary() {
        Instant issuedAt = Instant.parse("2026-07-22T00:00:00Z");
        MutableClock clock = new MutableClock(issuedAt);
        MonitorTicketService service = new MonitorTicketServiceImpl(Duration.ofSeconds(30), clock);
        AuthenticatedUser user = new AuthenticatedUser(1L, "admin", "admin", "approved", null,
                OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC));

        MonitorTicketVo beforeBoundary = service.issue(user);
        clock.set(issuedAt.plusSeconds(30).minusMillis(1));
        assertEquals(user, service.consume(beforeBoundary.ticket()));

        clock.set(issuedAt);
        MonitorTicketVo atBoundary = service.issue(user);
        clock.set(issuedAt.plusSeconds(30));
        BusinessException exception = assertThrows(
                BusinessException.class, () -> service.consume(atBoundary.ticket()));
        assertEquals(ErrorCode.UNAUTHORIZED, exception.getErrorCode());
    }

    /** 验证清理任务移除到期但未消费的 ticket。 */
    @Test
    void purgeShouldRemoveExpiredUnconsumedTicket() throws Exception {
        Instant issuedAt = Instant.parse("2026-07-22T00:00:00Z");
        MutableClock clock = new MutableClock(issuedAt);
        MonitorTicketService service = new MonitorTicketServiceImpl(Duration.ofSeconds(30), clock);
        AuthenticatedUser user = new AuthenticatedUser(1L, "admin", "admin", "approved", null,
                OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC));
        service.issue(user);

        clock.set(issuedAt.plusSeconds(30));
        service.purgeExpiredTickets();

        assertEquals(0, ticketCount(service));
    }

    /** 验证两个并发消费者最多一个成功。 */
    @Test
    void concurrentConsumeShouldSucceedOnlyOnce() throws Exception {
        Instant issuedAt = Instant.parse("2026-07-22T00:00:00Z");
        MutableClock clock = new MutableClock(issuedAt);
        MonitorTicketService service = new MonitorTicketServiceImpl(Duration.ofSeconds(30), clock);
        AuthenticatedUser user = new AuthenticatedUser(1L, "admin", "admin", "approved", null,
                OffsetDateTime.ofInstant(issuedAt, ZoneOffset.UTC));
        MonitorTicketVo ticket = service.issue(user);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        java.util.List<Future<?>> consumers = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < 2; index++) {
                consumers.add(executor.submit(() -> {
                    try {
                        start.await(5, TimeUnit.SECONDS);
                        service.consume(ticket.ticket());
                        successes.incrementAndGet();
                    } catch (BusinessException ignored) {
                        // 另一个消费者已经原子消费 ticket，拒绝符合预期。
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }));
            }
            start.countDown();
            for (Future<?> consumer : consumers) {
                consumer.get(5, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdown();
            org.junit.jupiter.api.Assertions.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(1, successes.get());
    }

    /** 读取测试对象中的待消费 Ticket 数量，用于直接验证清理效果。 */
    private int ticketCount(MonitorTicketService service) throws Exception {
        Field field = MonitorTicketServiceImpl.class.getDeclaredField("tickets");
        field.setAccessible(true);
        return ((ConcurrentMap<?, ?>) field.get(service)).size();
    }

    /** 提供测试可推进的 UTC Clock。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
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
