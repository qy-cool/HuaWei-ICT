CREATE TABLE project_artifacts (
    id VARCHAR(36) NOT NULL COMMENT '制品 ID',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '所属项目',
    run_id VARCHAR(36) NOT NULL COMMENT '提交者关联的成功测试作业',
    repository VARCHAR(64) NOT NULL COMMENT '测试仓库标识',
    commit_sha CHAR(40) NOT NULL COMMENT '测试提交 SHA',
    kind VARCHAR(24) NOT NULL COMMENT '制品格式',
    sha256 CHAR(64) NOT NULL COMMENT '不可变内容寻址摘要',
    byte_count BIGINT NOT NULL COMMENT '原始字节长度',
    uploaded_by BIGINT UNSIGNED NOT NULL COMMENT '制品提交人',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC 登记时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_artifact_content (project_id, run_id, kind, sha256),
    KEY idx_artifact_history (project_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='制品内容与测试来源';

CREATE TABLE release_targets (
    project_id BIGINT UNSIGNED NOT NULL COMMENT '所属项目',
    environment VARCHAR(20) NOT NULL COMMENT '目标环境',
    service VARCHAR(64) NOT NULL COMMENT '逻辑服务',
    generation BIGINT NOT NULL DEFAULT 0 COMMENT '已分配的最后一个代次',
    PRIMARY KEY (project_id, environment, service)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按环境和服务串行分配发布代次';

CREATE TABLE project_releases (
    id VARCHAR(36) NOT NULL COMMENT '发布 ID',
    request_id VARCHAR(36) NOT NULL COMMENT '请求幂等键',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '所属项目',
    environment VARCHAR(20) NOT NULL COMMENT '目标环境',
    service VARCHAR(64) NOT NULL COMMENT '逻辑服务',
    server_id BIGINT UNSIGNED NOT NULL COMMENT '锁定的目标节点',
    generation BIGINT NOT NULL COMMENT '变更代次',
    action VARCHAR(20) NOT NULL COMMENT 'deploy 或 rollback',
    artifact_id VARCHAR(36) NOT NULL COMMENT '目标制品',
    previous_healthy_id VARCHAR(36) NULL COMMENT '创建时本节点该服务的上一健康发布',
    rollback_of VARCHAR(36) NULL COMMENT '要回退的发布',
    status VARCHAR(20) NOT NULL COMMENT 'queued、applying、healthy、failed、cancelled、uncertain',
    evidence VARCHAR(2048) NULL COMMENT '节点探针或中控终止原因',
    requested_by BIGINT UNSIGNED NOT NULL COMMENT '发起用户',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC 创建时间',
    claimed_at DATETIME(6) NULL COMMENT '节点首次取得执行权',
    reported_at DATETIME(6) NULL COMMENT '中控记录最终结果时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_release_order (project_id, environment, service, generation),
    UNIQUE KEY uk_release_request (project_id, requested_by, request_id),
    KEY idx_release_node (server_id, status, created_at),
    KEY idx_release_promote (project_id, environment, service, artifact_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单节点发布代次和实际执行证据';

CREATE TABLE release_events (
    seq BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '事件游标',
    release_id VARCHAR(36) NOT NULL COMMENT '发布 ID',
    status VARCHAR(20) NOT NULL COMMENT '本次状态',
    actor_type VARCHAR(12) NOT NULL COMMENT 'user、node 或 system',
    actor_id BIGINT UNSIGNED NULL COMMENT '用户或节点 ID',
    detail VARCHAR(2048) NULL COMMENT '节点证据或取消原因',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC 记录时间',
    PRIMARY KEY (seq),
    KEY idx_release_events (release_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='追加式发布审计';
