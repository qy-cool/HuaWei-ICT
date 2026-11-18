CREATE TABLE workspaces (
    id CHAR(36) NOT NULL COMMENT '工作区 UUID',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '项目 ID',
    server_id BIGINT UNSIGNED NOT NULL COMMENT '开发节点 ID',
    owner_id BIGINT UNSIGNED NOT NULL COMMENT '工作区独占使用者',
    name VARCHAR(100) NOT NULL COMMENT '工作区显示名称',
    hostname VARCHAR(253) NOT NULL COMMENT '预配置的独立 HTTPS 域名，停用后仍保留',
    active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '允许打开与续用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspaces_hostname (hostname),
    KEY idx_workspaces_project_owner (project_id,owner_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业自托管浏览器工作区';

CREATE TABLE workspace_credentials (
    token_hash CHAR(64) NOT NULL COMMENT '随机票据或会话的 SHA-256',
    workspace_id CHAR(36) NOT NULL COMMENT '固定工作区',
    kind VARCHAR(12) NOT NULL COMMENT 'ticket 或 session',
    expires_at BIGINT NOT NULL COMMENT 'UTC Unix 秒，绝对有效期',
    PRIMARY KEY (token_hash),
    KEY idx_workspace_credentials_workspace (workspace_id),
    KEY idx_workspace_credentials_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次性进入票据与工作区会话';
