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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * 在显式隔离 MySQL 库中验证 V53 源码同步两表的 DDL、唯一键，以及撤权取消
 * 未终态同步、清除授权的方言更新。
 */
@ActiveProfiles("test")
@SpringBootTest(properties = "cloide.ai.enabled=false")
@ContextConfiguration(initializers = RepositorySourceIdentityMySqlValidationIT.TargetDatabaseGuard.class)
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_VALIDATION_TESTS", matches = "true")
class SourceSyncMySqlValidationIT {

    private static final long REMOTE_ID = 910000053L;
    private static final String SYNC_ID = "33333333-3333-3333-3333-333333333333";
    private static final String GRANT_ID = "44444444-4444-4444-4444-444444444444";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM source_sync_grants WHERE grant_id = ?", GRANT_ID);
        jdbcTemplate.update("DELETE FROM source_syncs WHERE repository_id = ?", REMOTE_ID);
    }

    @Test
    void v53ShouldCreateSyncTablesAndUniqueKeys() {
        Integer migrationCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '53' AND success = 1
                """, Integer.class);
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name IN
                    ('source_syncs','source_sync_grants')
                """, Integer.class);
        assertEquals(1, migrationCount);
        assertEquals(2, tableCount);

        insertSync("queued");
        assertThrows(DuplicateKeyException.class, () -> insertSync("queued"));
        jdbcTemplate.update("""
                INSERT INTO source_sync_grants(grant_id, sync_id, server_id, secret_hash, expires_at, claimed_at)
                VALUES (?,?,?,?,'2099-01-01 00:00:00','2026-01-01 00:00:00')
                """, GRANT_ID, SYNC_ID, 2L, "a".repeat(64));
        assertThrows(DuplicateKeyException.class, () -> jdbcTemplate.update("""
                INSERT INTO source_sync_grants(grant_id, sync_id, server_id, secret_hash, expires_at, claimed_at)
                VALUES (?,?,?,?,'2099-01-01 00:00:00','2026-01-01 00:00:00')
                """, GRANT_ID, SYNC_ID, 2L, "b".repeat(64)));
    }

    @Test
    void cancelOpenByRepositoryMarksNonTerminalAndDeletesGrants() {
        insertSync("transferring");
        jdbcTemplate.update("""
                INSERT INTO source_sync_grants(grant_id, sync_id, server_id, secret_hash, expires_at, claimed_at)
                VALUES (?,?,?,?,'2099-01-01 00:00:00','2026-01-01 00:00:00')
                """, GRANT_ID, SYNC_ID, 2L, "a".repeat(64));
        int cancelled = jdbcTemplate.update("""
                UPDATE source_syncs SET status = 'cancelled', last_error = 'binding_revoked',
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE repository_id = ? AND status IN ('queued','preparing','transferring','verifying','cancelling')
                """, REMOTE_ID);
        int deleted = jdbcTemplate.update("""
                DELETE FROM source_sync_grants WHERE sync_id IN (
                    SELECT sync_id FROM source_syncs WHERE repository_id = ?)
                """, REMOTE_ID);
        assertEquals(1, cancelled);
        assertEquals(1, deleted);
        assertEquals("cancelled", jdbcTemplate.queryForObject(
                "SELECT status FROM source_syncs WHERE sync_id = ?", String.class, SYNC_ID));
        Integer grants = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM source_sync_grants WHERE grant_id = ?", Integer.class, GRANT_ID);
        assertEquals(0, grants);
        // 已终态行再执行取消为 0 行，保证幂等。
        jdbcTemplate.update("UPDATE source_syncs SET status = 'ready', last_error = NULL WHERE sync_id = ?", SYNC_ID);
        int again = jdbcTemplate.update("""
                UPDATE source_syncs SET status = 'cancelled', last_error = 'binding_revoked',
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE repository_id = ? AND status IN ('queued','preparing','transferring','verifying','cancelling')
                """, REMOTE_ID);
        assertEquals(0, again);
        assertEquals("ready", jdbcTemplate.queryForObject(
                "SELECT status FROM source_syncs WHERE sync_id = ?", String.class, SYNC_ID));
        List<String> indexes = jdbcTemplate.queryForList("""
                SELECT INDEX_NAME FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'source_syncs'
                  AND INDEX_NAME IN ('uk_source_syncs_sync_id','idx_source_syncs_node_queue')
                """, String.class);
        assertTrue(indexes.contains("uk_source_syncs_sync_id"));
        assertTrue(indexes.contains("idx_source_syncs_node_queue"));
    }

    private void insertSync(String status) {
        jdbcTemplate.update("""
                INSERT INTO source_syncs
                  (sync_id, project_id, repository_id, snapshot_id, server_id, created_by, binding_generation,
                   commit_sha, tree_sha, status, deadline, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?, '2099-01-01 00:00:00', '2026-01-01 00:00:00', '2026-01-01 00:00:00')
                """, SYNC_ID, REMOTE_ID, REMOTE_ID, "55555555-5555-5555-5555-555555555555", 2L, 51L, 1L,
                "c".repeat(40), "t".repeat(40), status);
    }
}
