CREATE TABLE project_github_repositories (
    project_id BIGINT UNSIGNED NOT NULL COMMENT '授权项目',
    remote_id BIGINT NOT NULL COMMENT 'GitHub 稳定仓库 ID',
    owner_name VARCHAR(100) NOT NULL COMMENT '组织',
    repo_name VARCHAR(100) NOT NULL COMMENT '仓库名及节点配置别名',
    default_branch VARCHAR(255) NOT NULL COMMENT '登记时的默认分支',
    installation_id BIGINT NOT NULL COMMENT 'GitHub App 安装 ID',
    linked_by BIGINT NOT NULL COMMENT '登记人',
    PRIMARY KEY (remote_id),
    UNIQUE KEY uk_github_full_name (owner_name,repo_name),
    KEY idx_github_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='独占项目授权的 GitHub 仓库';

CREATE TABLE project_github_pulls (
    project_id BIGINT UNSIGNED NOT NULL COMMENT '来源项目',
    remote_id BIGINT NOT NULL COMMENT 'GitHub 仓库 ID',
    task_id VARCHAR(36) NOT NULL COMMENT '编码任务',
    pr_number BIGINT NOT NULL COMMENT '远端 PR 序号',
    head_sha CHAR(40) NOT NULL COMMENT '平台提交',
    base_sha CHAR(40) NOT NULL COMMENT '任务基准提交',
    pr_state VARCHAR(20) NOT NULL COMMENT 'open、closed 或 merged',
    remote_updated VARCHAR(32) NOT NULL COMMENT 'GitHub UTC 更新时刻',
    requested_by BIGINT NOT NULL COMMENT '发起人',
    PRIMARY KEY (remote_id,task_id),
    UNIQUE KEY uk_github_pr_number (remote_id,pr_number),
    KEY idx_github_pr_project (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编码任务和可核验 PR';

CREATE TABLE github_deliveries (
    delivery_id VARCHAR(36) NOT NULL COMMENT 'GitHub 投递 UUID',
    event_type VARCHAR(40) NOT NULL COMMENT '事件类型',
    receipt_nonce VARCHAR(36) NOT NULL COMMENT '首次接收者',
    body_sha CHAR(64) NOT NULL COMMENT '签名原文摘要',
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '接收时间',
    PRIMARY KEY (delivery_id),
    KEY idx_github_delivery_time (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='签名验证后交付去重';
