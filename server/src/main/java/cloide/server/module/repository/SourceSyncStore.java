package cloide.server.module.repository;

import cloide.server.common.BusinessException;
import cloide.server.common.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** source_syncs 与短期授权的 SQL 访问；状态语义与终态裁决在 SourceSyncService。 */
@Repository
@RequiredArgsConstructor
class SourceSyncStore {
    private final JdbcTemplate jdbc;
    private static final String COLUMNS = """
            SELECT sync_id, project_id, repository_id, snapshot_id, server_id, created_by, binding_generation,
                   commit_sha, tree_sha, status, bundle_sha256, byte_count, attempts, last_error, deadline,
                   created_at, updated_at FROM source_syncs
            """;
    private static final RowMapper<SourceSync> ROW = (rs, row) -> new SourceSync(
            rs.getString("sync_id"), rs.getLong("project_id"), rs.getLong("repository_id"), rs.getString("snapshot_id"),
            rs.getLong("server_id"), rs.getLong("created_by"), rs.getLong("binding_generation"),
            rs.getString("commit_sha"), rs.getString("tree_sha"), rs.getString("status"),
            rs.getString("bundle_sha256"), rs.getObject("byte_count", Long.class), rs.getInt("attempts"),
            rs.getString("last_error"), rs.getObject("deadline", LocalDateTime.class),
            rs.getObject("created_at", LocalDateTime.class), rs.getObject("updated_at", LocalDateTime.class));

    void insert(SourceSync sync) {
        jdbc.update("""
                INSERT INTO source_syncs
                  (sync_id, project_id, repository_id, snapshot_id, server_id, created_by, binding_generation,
                   commit_sha, tree_sha, status, deadline, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'queued', ?, ?, ?)
                """, sync.syncId(), sync.projectId(), sync.repositoryId(), sync.snapshotId(), sync.serverId(),
                sync.createdBy(), sync.generation(), sync.commitSha(), sync.treeSha(),
                sync.deadline(), sync.createdAt(), sync.updatedAt());
    }

    void delete(String syncId) {
        jdbc.update("DELETE FROM source_syncs WHERE sync_id = ?", syncId);
    }

    SourceSync read(long projectId, String syncId) {
        return required(jdbc.query(COLUMNS + " WHERE project_id = ? AND sync_id = ?", ROW, projectId, syncId));
    }

    SourceSync lockForProject(long projectId, String syncId) {
        return required(jdbc.query(COLUMNS + " WHERE project_id = ? AND sync_id = ? FOR UPDATE", ROW, projectId, syncId));
    }

    SourceSync lockForNode(long serverId, String syncId) {
        return required(jdbc.query(COLUMNS + " WHERE server_id = ? AND sync_id = ? FOR UPDATE", ROW, serverId, syncId));
    }

    /** 中控待准备队列：最旧的 queued 优先，锁行防止两个调度实例重复生成。 */
    Optional<SourceSync> lockNextQueued() {
        return jdbc.query(COLUMNS + " WHERE status = 'queued' ORDER BY id LIMIT 1 FOR UPDATE", ROW)
                .stream().findFirst();
    }

    /** 节点待派发队列：bundle 就绪（transferring）最旧的优先，未确认的旧同步先于新同步。 */
    Optional<SourceSync> lockNextForNode(long serverId) {
        return jdbc.query(COLUMNS + """
                WHERE server_id = ? AND status IN ('transferring','verifying','cancelling')
                ORDER BY id LIMIT 1 FOR UPDATE
                """, ROW, serverId).stream().findFirst();
    }

    void status(String syncId, String status, LocalDateTime now) {
        jdbc.update("UPDATE source_syncs SET status = ?, updated_at = ? WHERE sync_id = ?", status, now, syncId);
    }

    void progress(String syncId, String stage, LocalDateTime now) {
        jdbc.update("UPDATE source_syncs SET status = ?, updated_at = ? WHERE sync_id = ? AND status IN ('transferring','verifying')",
                stage, now, syncId);
    }

    void bundled(String syncId, String bundleSha256, long byteCount, LocalDateTime now) {
        jdbc.update("""
                UPDATE source_syncs SET status = 'transferring', bundle_sha256 = ?, byte_count = ?, updated_at = ?
                WHERE sync_id = ? AND status = 'preparing'
                """, bundleSha256, byteCount, now, syncId);
    }

    void dispatched(String syncId, LocalDateTime now) {
        jdbc.update("UPDATE source_syncs SET attempts = attempts + 1, updated_at = ? WHERE sync_id = ?", now, syncId);
    }

    void complete(String syncId, String status, String lastError, LocalDateTime now) {
        jdbc.update("UPDATE source_syncs SET status = ?, last_error = ?, updated_at = ? WHERE sync_id = ?",
                status, lastError, now, syncId);
    }

    /**
     * preparing 滞留收敛：中控在 bundle 生成期间崩溃的行按截止时间分流——未过期回
     * queued 供重试（attempts 保留计数防死循环），已过期落 uncertain。滞留判据为
     * updated_at 超过时限，避免抢占仍在正常生成中的 preparing。
     */
    void recoverStalePreparing(LocalDateTime staleBefore, LocalDateTime now) {
        jdbc.update("UPDATE source_syncs SET status = 'queued', updated_at = ? WHERE status = 'preparing' AND updated_at < ? AND deadline > ?",
                now, staleBefore, now);
        jdbc.update("UPDATE source_syncs SET status = 'uncertain', updated_at = ? WHERE status = 'preparing' AND updated_at < ? AND deadline <= ?",
                now, staleBefore, now);
    }

    /** 超时兜底：宽限期后仍未收敛的活跃同步转 uncertain，节点回归后真实回执可覆盖核实。 */
    void expireStale(LocalDateTime deadlineBefore) {
        jdbc.update("""
                UPDATE source_syncs SET status = 'uncertain', updated_at = ?
                WHERE status IN ('transferring','verifying','cancelling') AND deadline < ?
                """, deadlineBefore, deadlineBefore);
    }

    /** 过期授权行清理：期限已过的 grant 不再具备任何效力，随兜底扫描一并删除。 */
    void purgeExpiredGrants(LocalDateTime now) {
        jdbc.update("DELETE FROM source_sync_grants WHERE expires_at < ?", now);
    }

    void insertGrant(String grantId, String syncId, long serverId, String secretHash,
            LocalDateTime expiresAt, LocalDateTime claimedAt) {
        jdbc.update("""
                INSERT INTO source_sync_grants(grant_id, sync_id, server_id, secret_hash, expires_at, claimed_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, grantId, syncId, serverId, secretHash, expiresAt, claimedAt);
    }

    /** 短期授权锁定读：每次内容请求重查归属与期限（返回密钥摘要与过期时间）。 */
    record GrantRow(String secretHash, LocalDateTime expiresAt) { }
    Optional<GrantRow> lockGrantRow(String grantId, long serverId, String syncId) {
        return jdbc.query("""
                SELECT secret_hash, expires_at FROM source_sync_grants
                WHERE grant_id = ? AND server_id = ? AND sync_id = ? FOR UPDATE
                """,(rs,i)->new GrantRow(rs.getString(1),rs.getObject(2,LocalDateTime.class)),
                grantId, serverId, syncId).stream().findFirst();
    }

    private static SourceSync required(List<SourceSync> rows) {
        return rows.stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
    }
}
