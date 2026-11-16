package cloide.server.module.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 在显式隔离 MySQL 库中验证 V51 仓库身份四表的 DDL 与关键唯一键行为。
 *
 * <p>核心是可空 active 唯一键（MySQL 允许唯一索引中多个 NULL）：同一仓库
 * 的多个撤销代次可并存且至多一行活动绑定。H2 单测不承担方言相关行为验证。</p>
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "cloide.ai.enabled=false")
@ContextConfiguration(initializers = RepositorySourceIdentityMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class RepositorySourceIdentityMySqlValidationIT {

    /** 使用远端仓库 ID 高位段避免与真实绑定冲突；测试后清理。 */
    private static final long REMOTE_ID = 910000051L;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM source_snapshots WHERE remote_id = ?", REMOTE_ID);
        jdbcTemplate.update("DELETE FROM repository_idempotent_requests WHERE project_id = ?", REMOTE_ID);
        jdbcTemplate.update("DELETE FROM repository_source_policies WHERE remote_id = ?", REMOTE_ID);
        jdbcTemplate.update("DELETE FROM project_repository_bindings WHERE remote_id = ?", REMOTE_ID);
        jdbcTemplate.update("DELETE FROM project_github_repositories WHERE remote_id = ?", REMOTE_ID);
    }

    /** V51 已在真实 MySQL 执行且四表存在。 */
    @Test
    void v51ShouldCreateAllFourTables() {
        Integer migrationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '51' AND success = 1
                """, Integer.class);
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name IN
                    ('project_repository_bindings','repository_source_policies',
                     'source_snapshots','repository_idempotent_requests')
                """, Integer.class);

        assertEquals(1, migrationCount);
        assertEquals(4, tableCount);
    }

    /**
     * 可空 active 唯一键（MySQL 实测核心）：同仓库三代次共存（两个 NULL 撤销 + 一个活动），
     * 第二行 active=1 被唯一索引拒绝。
     */
    @Test
    void nullableActiveUniqueKeyAllowsManyRevokedButOneActive() {
        insertBinding(1, "2026-11-16 00:00:01");
        revokeActiveBinding();
        insertBinding(2, "2026-11-16 00:00:02");
        revokeActiveBinding();
        insertBinding(3, "2026-11-16 00:00:03");

        assertEquals(3, activeGenerationCount(REMOTE_ID));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_repository_bindings WHERE remote_id=? AND active=1",
                Integer.class, REMOTE_ID));

        // 已有两个撤销代次（NULL）共存的情况下，再插一行活动绑定被唯一键拒绝。
        assertThrows(DuplicateKeyException.class, () -> insertBinding(4, "2026-11-16 00:00:04"));
    }

    /** (remote_id, generation) 唯一键：重复代次号被拒绝，即使旧行已撤销。 */
    @Test
    void generationUniqueKeyRejectsDuplicateEvenAfterRevoked() {
        insertBinding(1, "2026-11-16 01:00:00");
        revokeActiveBinding();
        assertThrows(DuplicateKeyException.class, () -> insertBinding(1, "2026-11-16 01:00:01"));
    }

    /** 幂等请求账本 (project, requester, operation, request_id) 唯一键：同键重复被拒绝。 */
    @Test
    void idempotentRequestUniqueKeyRejectsSameKey() {
        jdbcTemplate.update("""
                INSERT INTO repository_idempotent_requests
                    (project_id, requester_id, operation, request_id, param_digest, result_id)
                VALUES (?,?,?,?,?,?)
                """, REMOTE_ID, 51L, "snapshot", "0f0e0d0c-1111-2222-3333-444455556666",
                "a".repeat(64), "snap-1");

        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update("""
                INSERT INTO repository_idempotent_requests
                    (project_id, requester_id, operation, request_id, param_digest, result_id)
                VALUES (?,?,?,?,?,?)
                """, REMOTE_ID, 51L, "snapshot", "0f0e0d0c-1111-2222-3333-444455556666",
                "b".repeat(64), "snap-2"));
    }

    /** 策略版本复合主键：同 (remote_id, policy_version) 重复被拒绝；不同版本行共存。 */
    @Test
    void policyPrimaryKeyRejectsDuplicateVersion() {
        jdbcTemplate.update("""
                INSERT INTO repository_source_policies(remote_id, policy_version, allowed_refs, updated_by)
                VALUES (?,?,?,?)
                """, REMOTE_ID, 1L, "[\"refs/heads/main\"]", 51L);
        jdbcTemplate.update("""
                INSERT INTO repository_source_policies(remote_id, policy_version, allowed_refs, updated_by)
                VALUES (?,?,?,?)
                """, REMOTE_ID, 2L, "[\"refs/heads/main\",\"refs/heads/dev\"]", 51L);

        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update("""
                INSERT INTO repository_source_policies(remote_id, policy_version, allowed_refs, updated_by)
                VALUES (?,?,?,?)
                """, REMOTE_ID, 2L, "[\"refs/heads/other\"]", 51L));

        List<Long> versions = jdbcTemplate.queryForList(
                "SELECT policy_version FROM repository_source_policies WHERE remote_id=? ORDER BY policy_version",
                Long.class, REMOTE_ID);
        assertEquals(List.of(1L, 2L), versions);
    }

    private void insertBinding(long generation, String linkedAt) {
        jdbcTemplate.update("""
                INSERT INTO project_repository_bindings
                    (project_id, remote_id, owner_name, repo_name, default_branch,
                     installation_id, generation, active, linked_by, linked_at)
                VALUES (?,?,?,?,?,?,?,?,1,?)
                """, REMOTE_ID, REMOTE_ID, "acme", "validation-a1", "main", 17L, generation, 51L, linkedAt);
    }

    private void revokeActiveBinding() {
        int updated = jdbcTemplate.update("""
                UPDATE project_repository_bindings SET active = NULL,
                    unlinked_at = CURRENT_TIMESTAMP(6), unlinked_by = 51
                WHERE remote_id = ? AND active = 1
                """, REMOTE_ID);
        assertTrue(updated == 1, "每次撤销应恰好命中一行活动绑定");
    }

    private int activeGenerationCount(long remoteId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM project_repository_bindings WHERE remote_id=?", Integer.class, remoteId);
        return count == null ? 0 : count;
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
