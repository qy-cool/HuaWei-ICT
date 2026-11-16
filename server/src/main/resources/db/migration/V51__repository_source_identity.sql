-- A1 仓库身份与提交选择：绑定历史代次、来源策略、提交快照与幂等请求账本。
-- 现存 project_github_repositories 活动行在事务内补建首代次（generation=1）。
-- active 用可空列：1=活动，NULL=已撤销；uk(remote_id, active) 借“唯一索引允许多个 NULL”
-- 保证同一仓库至多一行活动绑定，同时允许多个历史撤销代次并存。

CREATE TABLE project_repository_bindings (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '绑定历史主键',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '授权项目',
    remote_id BIGINT NOT NULL COMMENT 'GitHub 稳定仓库 ID',
    owner_name VARCHAR(100) NOT NULL COMMENT '组织',
    repo_name VARCHAR(100) NOT NULL COMMENT '仓库名（改名时按稳定 ID 核对后更新）',
    default_branch VARCHAR(255) NOT NULL COMMENT '登记时的默认分支',
    installation_id BIGINT NOT NULL COMMENT 'GitHub App 安装 ID',
    generation BIGINT UNSIGNED NOT NULL COMMENT '绑定代次，同一仓库内单调递增',
    active TINYINT(1) NULL DEFAULT 1 COMMENT '1 活动；NULL 已撤销',
    linked_by BIGINT NOT NULL COMMENT '登记人',
    linked_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登记时间',
    unlinked_at DATETIME(6) NULL COMMENT '撤销时间',
    unlinked_by BIGINT NULL COMMENT '撤销人',
    PRIMARY KEY (id),
    UNIQUE KEY uk_binding_active_repo (remote_id, active),
    UNIQUE KEY uk_binding_generation (remote_id, generation),
    KEY idx_binding_project (project_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库绑定的不可变历史与代次';

INSERT INTO project_repository_bindings
    (project_id, remote_id, owner_name, repo_name, default_branch, installation_id, generation, active, linked_by)
SELECT project_id, remote_id, owner_name, repo_name, default_branch, installation_id, 1, 1, linked_by
FROM project_github_repositories;

-- 策略按版本保留历史：快照记录 policy_version，幂等重放按原版本返回当时允许清单。
CREATE TABLE repository_source_policies (
    remote_id BIGINT NOT NULL COMMENT 'GitHub 稳定仓库 ID',
    policy_version BIGINT UNSIGNED NOT NULL COMMENT '乐观并发版本，每次更新插入新版本行',
    allowed_refs JSON NOT NULL COMMENT '允许的完整分支引用数组，如 ["refs/heads/main"]',
    updated_by BIGINT NOT NULL COMMENT '该版本写入人',
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '该版本写入时间',
    PRIMARY KEY (remote_id, policy_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库允许来源策略（按版本保留历史）';

CREATE TABLE source_snapshots (
    snapshot_id CHAR(36) NOT NULL COMMENT '快照 UUID',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '所属项目',
    remote_id BIGINT NOT NULL COMMENT 'GitHub 稳定仓库 ID',
    ref VARCHAR(255) NOT NULL COMMENT '钉住时的完整分支引用',
    commit_sha CHAR(40) NOT NULL COMMENT '解析出的 40 位小写提交 SHA',
    tree_sha CHAR(40) NOT NULL COMMENT '提交树 SHA',
    binding_generation BIGINT UNSIGNED NOT NULL COMMENT '创建时有效的绑定代次',
    policy_version BIGINT UNSIGNED NOT NULL COMMENT '创建时生效的策略版本',
    resolved_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT 'UTC 解析时间',
    created_by BIGINT NOT NULL COMMENT '发起人',
    PRIMARY KEY (snapshot_id),
    KEY idx_snapshot_project (project_id, remote_id, commit_sha),
    KEY idx_snapshot_repo (remote_id, binding_generation)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库提交的不可变快照';

CREATE TABLE repository_idempotent_requests (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '所属项目',
    requester_id BIGINT NOT NULL COMMENT '发起人',
    operation VARCHAR(40) NOT NULL COMMENT '操作类型，如 source-policy、snapshot',
    request_id VARCHAR(36) NOT NULL COMMENT '客户端请求 UUID',
    param_digest CHAR(64) NOT NULL COMMENT '规范化业务字段 SHA-256',
    result_id VARCHAR(64) NOT NULL COMMENT '成功后的资源标识',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_idem_request (project_id, requester_id, operation, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='仓库域幂等请求账本';
