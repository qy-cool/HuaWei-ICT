package cloide.server.module.alert.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 在显式隔离 MySQL 库中验证 V13 告警规则唯一约束和软删除重建语义。
 *
 * <p>该测试直接验证 MySQL 的生成列、NULL 范围归一和唯一索引，H2 Mapper
 * 测试不承担这些方言相关行为的验证职责。</p>
 */
@ActiveProfiles("test")
@SpringBootTest
@ContextConfiguration(initializers = AlertRuleMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class AlertRuleMySqlValidationIT {

    private static final String TEST_PREFIX = "mvp6-alert-validation-";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String testMarker;
    private long testCreatedBy;

    /** 验证 V13 已执行，通用规则查重、软删除后重建和指定服务器规则范围均符合预期。 */
    @Test
    void v13ShouldEnforceActiveRuleSignatureAndAllowRecreationAfterSoftDelete() {
        testMarker = TEST_PREFIX + UUID.randomUUID();
        testCreatedBy = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        assertMigrationAndUniqueIndex();

        Long firstRuleId = insertRule(null, "cpu", ">", new BigDecimal("80.00"), "warning");
        assertNotNull(firstRuleId);
        assertEquals(0L, activeScopeId(firstRuleId));

        assertDuplicateInsertFails(null, "cpu", ">", new BigDecimal("80.00"), "warning");

        jdbcTemplate.update("UPDATE alert_rules SET deleted = 1, deleted_at = ? WHERE id = ?",
                Timestamp.valueOf(LocalDateTime.now()), firstRuleId);
        assertNull(activeScopeId(firstRuleId));

        Long recreatedRuleId = insertRule(null, "cpu", ">", new BigDecimal("80.00"), "warning");
        assertNotNull(recreatedRuleId);
        assertEquals(0L, activeScopeId(recreatedRuleId));

        Long serverRuleId = insertRule(900001L, "cpu", ">", new BigDecimal("80.00"), "warning");
        assertNotNull(serverRuleId);
        assertEquals(900001L, activeScopeId(serverRuleId));
    }

    /** 将当前测试通过唯一 created_by 标记创建的规则软删除，绝不清空表或删除其他规则。 */
    @AfterEach
    void tearDown() {
        if (testMarker != null) {
            jdbcTemplate.update("UPDATE alert_rules SET deleted = 1, deleted_at = ? WHERE created_by = ? AND deleted = 0",
                    Timestamp.valueOf(LocalDateTime.now()), testCreatedBy);
        }
    }

    /** 验证 Flyway V13 已成功执行且唯一索引存在。 */
    private void assertMigrationAndUniqueIndex() {
        Integer migrationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '13' AND success = 1
                """, Integer.class);
        Integer uniqueIndexColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'alert_rules'
                  AND index_name = 'uk_alert_rules_active_signature' AND non_unique = 0
                """, Integer.class);

        assertEquals(1, migrationCount);
        assertEquals(5, uniqueIndexColumns);
    }

    /** 插入带测试标记的规则并返回数据库生成的主键。 */
    private Long insertRule(Long serverId, String metric, String operator, BigDecimal thresholdValue, String level) {
        jdbcTemplate.update("""
                INSERT INTO alert_rules
                (server_id, metric, operator, threshold_value, level, enabled, deleted, created_by)
                VALUES (?, ?, ?, ?, ?, 1, 0, ?)
                """, serverId, metric, operator, thresholdValue, level, testCreatedBy);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    /** 验证相同活跃五元组被 MySQL 唯一索引拒绝。 */
    private void assertDuplicateInsertFails(Long serverId, String metric, String operator,
            BigDecimal thresholdValue, String level) {
        try {
            insertRule(serverId, metric, operator, thresholdValue, level);
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            return;
        }
        throw new AssertionError("active alert rule duplicate was not rejected");
    }

    /** 查询规则生成的活跃范围列，软删除记录必须为 NULL。 */
    private Long activeScopeId(Long ruleId) {
        return jdbcTemplate.queryForObject(
                "SELECT active_server_scope_id FROM alert_rules WHERE id = ?", Long.class, ruleId);
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
