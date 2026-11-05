package cloide.server.module.terminal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.config.AppProperties;
import cloide.server.module.terminal.entity.TerminalSessionEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

/** 在隔离 MySQL 中验证当前终端会话配额实现的并发边界和幂等约束。 */
@ActiveProfiles("test")
@SpringBootTest
@ContextConfiguration(initializers = TerminalSessionQuotaMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class TerminalSessionQuotaMySqlValidationIT {

    private static final String TEST_PREFIX = "terminal-quota-validation-";

    @Autowired
    private TerminalSessionService terminalSessionService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AppProperties appProperties;

    private Long userId;
    private Long serverId;
    private final List<Long> fixtureUserIds = new ArrayList<>();
    private final List<Long> fixtureServerIds = new ArrayList<>();
    private long baselineActiveSessions;
    private long baselineActiveUsers;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String marker = TEST_PREFIX + suffix.substring(0, 20);
        jdbcTemplate.update("""
                INSERT INTO users (username, password_hash, role, review_status)
                VALUES (?, 'validation-only-hash', 'user', 'approved')
                """, marker);
        userId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        fixtureUserIds.add(userId);
        jdbcTemplate.update("""
                INSERT INTO servers
                (name, host, description, ssh_host, ssh_port, ssh_user, ssh_auth_type, status, agent_status)
                VALUES (?, ?, ?, ?, 22, 'validation', ?, 'online', 'online')
                """, marker, marker, TEST_PREFIX, marker, "password");
        serverId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        fixtureServerIds.add(serverId);
        assertNotNull(userId);
        assertNotNull(serverId);
        baselineActiveSessions = countActiveGlobally();
        baselineActiveUsers = countActiveUsers();
        appProperties.getTerminal().setMaxOperatingUsers(100);
    }

    @AfterEach
    void tearDown() {
        transactionTemplate.executeWithoutResult(status -> {
            for (Long fixtureUserId : fixtureUserIds) {
                jdbcTemplate.update("DELETE FROM terminal_sessions WHERE user_id = ?", fixtureUserId);
            }
            for (Long fixtureServerId : fixtureServerIds) {
                jdbcTemplate.update("DELETE FROM terminal_sessions WHERE server_id = ?", fixtureServerId);
            }
            if (!fixtureServerIds.isEmpty()) {
                String placeholders = String.join(",", java.util.Collections.nCopies(fixtureServerIds.size(), "?"));
                jdbcTemplate.update("DELETE FROM servers WHERE id IN (" + placeholders + ") AND description = ?",
                        appendArgs(fixtureServerIds, TEST_PREFIX));
            }
            if (!fixtureUserIds.isEmpty()) {
                String placeholders = String.join(",", java.util.Collections.nCopies(fixtureUserIds.size(), "?"));
                jdbcTemplate.update("DELETE FROM users WHERE id IN (" + placeholders + ") AND username LIKE ?",
                        appendArgs(fixtureUserIds, TEST_PREFIX + "%"));
            }
        });
        fixtureUserIds.clear();
        fixtureServerIds.clear();
        appProperties.getTerminal().setMaxOperatingUsers(5);
    }

    private Object[] appendArgs(List<Long> values, String suffix) {
        Object[] args = new Object[values.size() + 1];
        for (int i = 0; i < values.size(); i++) {
            args[i] = values.get(i);
        }
        args[values.size()] = suffix;
        return args;
    }

    /** 同一用户并发请求 8 次时，当前限制应最多允许 2 个会话。 */
    @Test
    void concurrentOpenShouldRespectPerUserLimit() throws Exception {
        List<OpenResult> results = concurrentlyOpen(8, serverId);

        assertEquals(2, successfulCount(results));
        assertEquals(2L, countActiveByUser());
    }

    /** 相同用户和 open_message_id 并发重试时，唯一约束应只保留一个会话。 */
    @Test
    void concurrentDuplicateMessageShouldCreateOneSession() throws Exception {
        String messageId = UUID.randomUUID().toString();
        List<OpenResult> results = concurrentlyOpenSameMessage(8, serverId, messageId);

        assertEquals(1, distinctSessionCount(results));
        assertEquals(1L, countActiveByUser());
    }

    /** 多个用户竞争同一服务器时，服务器活动会话不得超过 4 个。 */
    @Test
    void concurrentOpenShouldRespectPerServerLimit() throws Exception {
        List<OpenRequest> requests = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Long requestUserId = i == 0 ? userId : createUser();
            requests.add(new OpenRequest(requestUserId, serverId, UUID.randomUUID().toString()));
        }

        List<OpenResult> results = concurrentlyOpenRequests(requests);

        assertEquals(4, successfulCount(results));
        assertEquals(4L, countActiveByServer(serverId));
        assertQuotaFailures(results);
    }

    /** 多个用户和服务器竞争全局额度时，全局活动会话不得超过 16 个。 */
    @Test
    void concurrentOpenShouldRespectGlobalLimit() throws Exception {
        List<OpenRequest> requests = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Long requestUserId = i == 0 ? userId : createUser();
            Long requestServerId = i == 0 ? serverId : createServer();
            requests.add(new OpenRequest(requestUserId, requestServerId, UUID.randomUUID().toString()));
        }

        List<OpenResult> results = concurrentlyOpenRequests(requests);

        long expectedSuccessful = Math.max(0L, 16L - baselineActiveSessions);
        assertEquals(expectedSuccessful, successfulCount(results));
        assertEquals(16L, countActiveGlobally());
        assertQuotaFailures(results);
    }

    /** 六个不同用户并发打开时，活动操作用户不得超过五个。 */
    @Test
    void concurrentOpenShouldRespectOperatingUserLimit() throws Exception {
        appProperties.getTerminal().setMaxOperatingUsers(5);
        try {
            List<OpenRequest> requests = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                requests.add(new OpenRequest(
                        i == 0 ? userId : createUser(),
                        createServer(),
                        UUID.randomUUID().toString()));
            }

            List<OpenResult> results = concurrentlyOpenRequests(requests);

            assertEquals(Math.max(0, 5 - (int) baselineActiveUsers), successfulCount(results));
            assertTrue(countActiveUsers() <= 5);
            assertQuotaFailures(results);
        } finally {
            appProperties.getTerminal().setMaxOperatingUsers(100);
        }
    }

    private List<OpenResult> concurrentlyOpenRequests(List<OpenRequest> requests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(requests.size());
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<OpenResult>> futures = new ArrayList<>();
            for (OpenRequest request : requests) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    return open(request.userId(), request.serverId(), request.messageId());
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return collect(futures);
        } finally {
            start.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    private Long createUser() {
        String marker = TEST_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbcTemplate.update("""
                INSERT INTO users (username, password_hash, role, review_status)
                VALUES (?, 'validation-only-hash', 'user', 'approved')
                """, marker);
        Long created = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        fixtureUserIds.add(created);
        return created;
    }

    private Long createServer() {
        String marker = TEST_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        jdbcTemplate.update("""
                INSERT INTO servers
                (name, host, description, ssh_host, ssh_port, ssh_user, ssh_auth_type, status, agent_status)
                VALUES (?, ?, ?, ?, 22, 'validation', ?, 'online', 'online')
                """, marker, marker, TEST_PREFIX, marker, "password");
        Long created = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        fixtureServerIds.add(created);
        return created;
    }

    private void assertQuotaFailures(List<OpenResult> results) {
        assertTrue(results.stream().filter(result -> !result.success()).allMatch(
                result -> result.errorCode() == ErrorCode.TERMINAL_SESSION_LIMIT_REACHED));
    }

    private List<OpenResult> concurrentlyOpen(int count, Long targetServerId) throws Exception {
        List<OpenRequest> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            requests.add(new OpenRequest(userId, targetServerId, UUID.randomUUID().toString()));
        }
        return concurrentlyOpenRequests(requests);
    }

    private List<OpenResult> concurrentlyOpenSameMessage(int count, Long targetServerId, String messageId)
            throws Exception {
        List<OpenRequest> requests = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            requests.add(new OpenRequest(userId, targetServerId, messageId));
        }
        return concurrentlyOpenRequests(requests);
    }

    private OpenResult open(Long targetUserId, Long targetServerId, String messageId) {
        try {
            return OpenResult.success(
                    terminalSessionService.openSession(targetUserId, targetServerId, messageId));
        } catch (BusinessException exception) {
            return OpenResult.failure(exception.getErrorCode());
        }
    }

    private long countActiveByServer(Long targetServerId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM terminal_sessions WHERE server_id = ? AND status IN ('opening', 'open')",
                Long.class, targetServerId);
    }

    private long countActiveGlobally() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM terminal_sessions WHERE status IN ('opening', 'open')", Long.class);
    }

    private long countActiveUsers() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT user_id) FROM terminal_sessions WHERE status IN ('opening', 'open')",
                Long.class);
    }

    private List<OpenResult> collect(List<Future<OpenResult>> futures) throws Exception {
        List<OpenResult> results = new ArrayList<>();
        for (Future<OpenResult> future : futures) {
            try {
                results.add(future.get(5, TimeUnit.SECONDS));
            } catch (ExecutionException exception) {
                throw new AssertionError("concurrent open failed unexpectedly", exception.getCause());
            }
        }
        return results;
    }

    private long countActiveByUser() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM terminal_sessions WHERE user_id = ? AND status IN ('opening', 'open')",
                Long.class, userId);
    }

    private int successfulCount(List<OpenResult> results) {
        return (int) results.stream().filter(OpenResult::success).count();
    }

    private int distinctSessionCount(List<OpenResult> results) {
        return (int) results.stream().filter(OpenResult::success).map(OpenResult::sessionId).distinct().count();
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("validation test timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("validation test interrupted", exception);
        }
    }

    private record OpenRequest(Long userId, Long serverId, String messageId) { }

    private record OpenResult(boolean success, String sessionId, ErrorCode errorCode) {
        static OpenResult success(TerminalSessionEntity session) {
            return new OpenResult(true, session.getSessionId(), null);
        }

        static OpenResult failure(ErrorCode errorCode) {
            return new OpenResult(false, null, errorCode);
        }
    }

    static final class TargetDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            if (!"true".equalsIgnoreCase(System.getenv("RUN_MYSQL_VALIDATION_TESTS"))) {
                throw new IllegalStateException("MySQL validation requires RUN_MYSQL_VALIDATION_TESTS=true");
            }
            String host = System.getenv("DB_HOST");
            String database = System.getenv("DB_NAME");
            if (!("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host))) {
                throw new IllegalStateException("MySQL validation requires a local DB_HOST");
            }
            if (database == null || database.isBlank()
                    || "cloide".equalsIgnoreCase(database)
                    || !database.toLowerCase().contains("validation")) {
                throw new IllegalStateException("MySQL validation requires an isolated validation DB_NAME");
            }
        }
    }
}