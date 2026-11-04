package cloide.server.module.metrics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import cloide.server.module.metrics.dto.MetricsReportPayload;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

/**
 * 在显式隔离 MySQL 库中验证 V11 指标幂等记录、回滚和同服务器并发顺序。
 */
@ActiveProfiles("test")
@SpringBootTest
@ContextConfiguration(initializers = MetricsIngestionMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class MetricsIngestionMySqlValidationIT {

    private static final String TEST_PREFIX = "p2-metrics-validation-";
    private static final OffsetDateTime BASE_COLLECTED_AT = OffsetDateTime.of(
            2026, 7, 25, 12, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    private MetricsService metricsService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long serverId;

    /** 为每个验证用例创建唯一服务器行，满足 Metrics 写入的行锁前置条件。 */
    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString();
        String host = TEST_PREFIX + unique;
        jdbcTemplate.update("""
                INSERT INTO servers
                (name, host, description, ssh_host, ssh_port, ssh_user, ssh_auth_type)
                VALUES (?, ?, ?, ?, 22, 'validation', 'password')
                """, host, host, TEST_PREFIX, host);
        serverId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertNotNull(serverId);
    }

    /** 仅删除当前测试创建的记录，绝不清空表或修改其他业务服务器。 */
    @AfterEach
    void tearDown() {
        if (serverId == null) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM metrics_ingestions WHERE server_id = ?", serverId);
            jdbcTemplate.update("DELETE FROM metrics WHERE server_id = ?", serverId);
            jdbcTemplate.update("DELETE FROM servers WHERE id = ? AND description = ?", serverId, TEST_PREFIX);
        });
    }

    /** 验证 Flyway 已执行 V11，并建立 V11 表和唯一索引。 */
    @Test
    void flywayShouldApplyV11AndCreateIngestionIndexes() {
        Integer migrationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '11' AND success = 1
                """, Integer.class);
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'metrics_ingestions'
                """, Integer.class);
        Integer uniqueIndexCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'metrics_ingestions'
                  AND index_name = 'uk_metrics_ingestions_server_message' AND non_unique = 0
                """, Integer.class);

        assertEquals(1, migrationCount);
        assertEquals(1, tableCount);
        assertEquals(2, uniqueIndexCount);
    }

    /** 验证相同 message_id 重试只保留一条 Metrics 和 ingestion 记录。 */
    @Test
    void duplicateMessageIdShouldBeSilentlyIdempotent() {
        String messageId = UUID.randomUUID().toString();
        metricsService.report(serverId, messageId, payload(BASE_COLLECTED_AT));
        metricsService.report(serverId, messageId, payload(BASE_COLLECTED_AT));

        assertEquals(1L, count("metrics"));
        assertEquals(1L, count("metrics_ingestions"));
    }

    /** 验证外层事务回滚会同时撤销 Metrics 和 V11 ingestion 记录。 */
    @Test
    void rollbackShouldRemoveMetricAndIngestionTogether() {
        transactionTemplate.executeWithoutResult(status -> {
            metricsService.report(serverId, UUID.randomUUID().toString(), payload(BASE_COLLECTED_AT));
            status.setRollbackOnly();
        });

        assertEquals(0L, count("metrics"));
        assertEquals(0L, count("metrics_ingestions"));
    }

    /** 验证同一服务器并发上报由服务器行锁串行化，旧采样在新采样提交后被拒绝。 */
    @Test
    void olderConcurrentSampleShouldWaitThenBeRejected() throws Exception {
        CountDownLatch newerTransactionLocked = new CountDownLatch(1);
        CountDownLatch allowNewerCommit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> newer = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM servers WHERE id = ? FOR UPDATE", Long.class, serverId);
                newerTransactionLocked.countDown();
                await(allowNewerCommit);
                metricsService.report(serverId, UUID.randomUUID().toString(),
                        payload(BASE_COLLECTED_AT.plusSeconds(1)));
            }));
            assertTrue(newerTransactionLocked.await(5, TimeUnit.SECONDS));

            Future<?> older = executor.submit(() -> metricsService.report(
                    serverId, UUID.randomUUID().toString(), payload(BASE_COLLECTED_AT)));
            assertFalse(older.isDone());

            allowNewerCommit.countDown();
            newer.get(5, TimeUnit.SECONDS);
            ExecutionException exception = assertThrows(ExecutionException.class,
                    () -> older.get(5, TimeUnit.SECONDS));
            assertTrue(exception.getCause() instanceof BusinessException);
            assertEquals(ErrorCode.INVALID_REQUEST_PARAMETER,
                    ((BusinessException) exception.getCause()).getErrorCode());
            assertEquals(1L, count("metrics"));
            assertEquals(1L, count("metrics_ingestions"));
        } finally {
            allowNewerCommit.countDown();
            executor.shutdownNow();
        }
    }

    /** 构造满足 Metrics 校验的最小采样载荷。 */
    private MetricsReportPayload payload(OffsetDateTime collectedAt) {
        MetricsReportPayload payload = new MetricsReportPayload();
        payload.setServerId(serverId);
        payload.setCollectedAt(collectedAt);
        payload.setCpuPercent(BigDecimal.TEN);
        return payload;
    }

    /** 查询当前测试服务器在指定表中的记录数，表名仅来自固定内部常量。 */
    private long count(String tableName) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + tableName + " WHERE server_id = ?", Long.class, serverId);
    }

    /** 等待测试线程的受控放行信号；超时说明数据库锁顺序不符合预期。 */
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

    /** 在 Spring 创建数据源前拒绝非本机或非隔离验证库。 */
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
