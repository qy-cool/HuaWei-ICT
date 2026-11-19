-- A2 节点源码同步：受管 bundle 分发状态与短期下载授权。
-- 幂等沿用 V51 的 repository_idempotent_requests（operation='source-sync'），
-- 节点回执即本表终态：(sync_id) 全局唯一绑定一个节点与绑定代次。
CREATE TABLE source_syncs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '排队顺序',
    sync_id CHAR(36) NOT NULL COMMENT '同步 UUID（节点端持久去重标识）',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '所属项目',
    repository_id BIGINT NOT NULL COMMENT 'GitHub 稳定仓库 remote_id',
    snapshot_id CHAR(36) NOT NULL COMMENT 'A1 提交快照',
    server_id BIGINT UNSIGNED NOT NULL COMMENT '目标节点',
    created_by BIGINT NOT NULL COMMENT '发起用户',
    binding_generation BIGINT UNSIGNED NOT NULL COMMENT '创建时有效的绑定代次',
    commit_sha CHAR(40) NOT NULL COMMENT '快照提交 SHA',
    tree_sha CHAR(40) NOT NULL COMMENT '快照树 SHA',
    status VARCHAR(20) NOT NULL COMMENT 'queued/preparing/transferring/verifying/ready/cancelling/cancelled/failed/uncertain',
    bundle_sha256 CHAR(64) NULL COMMENT 'bundle SHA-256，生成后填充',
    byte_count BIGINT UNSIGNED NULL COMMENT 'bundle 字节数',
    attempts INT NOT NULL DEFAULT 0 COMMENT '派发尝试次数',
    last_error VARCHAR(64) NULL COMMENT '最后错误分类，如 source_unavailable、capacity、digest_mismatch',
    deadline DATETIME(6) NOT NULL COMMENT 'UTC 完成截止时间',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC 创建时间',
    updated_at DATETIME(6) NOT NULL COMMENT 'UTC 最后变更时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_syncs_sync_id (sync_id),
    KEY idx_source_syncs_node_queue (server_id, status, id),
    KEY idx_source_syncs_history (project_id, id),
    KEY idx_source_syncs_sweep (status, deadline)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点源码同步请求与状态';

CREATE TABLE source_sync_grants (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    grant_id CHAR(36) NOT NULL COMMENT '授权 UUID',
    sync_id CHAR(36) NOT NULL COMMENT '所属同步',
    server_id BIGINT UNSIGNED NOT NULL COMMENT '领取节点',
    secret_hash CHAR(64) NOT NULL COMMENT '43 位密钥的 SHA-256，不存明文',
    expires_at DATETIME(6) NOT NULL COMMENT 'UTC 过期时间',
    claimed_at DATETIME(6) NOT NULL COMMENT 'UTC 领取时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_source_sync_grants_grant_id (grant_id),
    KEY idx_source_sync_grants_sync (sync_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='源码同步短期下载授权（每次内容请求重查撤权与期限）';
