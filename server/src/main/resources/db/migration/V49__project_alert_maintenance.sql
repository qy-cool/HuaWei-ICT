CREATE TABLE project_alert_scopes (
    alert_id BIGINT UNSIGNED NOT NULL COMMENT '触发时的告警记录',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '当时的项目归属',
    server_id BIGINT UNSIGNED NOT NULL COMMENT '当时的节点',
    environment VARCHAR(20) NOT NULL COMMENT '当时的环境',
    metric VARCHAR(30) NOT NULL COMMENT '触发指标',
    level VARCHAR(20) NOT NULL COMMENT '触发等级',
    current_value DECIMAL(12,2) NOT NULL COMMENT '实测值',
    threshold_value DECIMAL(12,2) NOT NULL COMMENT '阈值',
    triggered_at DATETIME NOT NULL COMMENT '原始触发时间',
    PRIMARY KEY (alert_id),
    KEY idx_project_alerts (project_id, alert_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警触发事务内冻结项目归属';

CREATE TABLE project_alert_actions (
    id CHAR(36) NOT NULL COMMENT '维护记录 UUID',
    alert_id BIGINT UNSIGNED NOT NULL COMMENT '来源告警',
    project_id BIGINT UNSIGNED NOT NULL COMMENT '项目',
    actor_id BIGINT UNSIGNED NOT NULL COMMENT '发起人',
    request_id CHAR(36) NOT NULL COMMENT '调用方幂等键',
    action VARCHAR(20) NOT NULL COMMENT 'confirm_cause、repair 或 rollback',
    release_id CHAR(36) NOT NULL COMMENT '选中的事故相关发布',
    build_node BIGINT UNSIGNED NULL COMMENT '执行代码修复的开发节点',
    rationale VARCHAR(1200) NOT NULL COMMENT '发起理由或人工确认根因的证据',
    result_id CHAR(36) NULL COMMENT '实际创建的修复任务或回滚发布',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC 时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_alert_action_request (project_id,actor_id,request_id),
    KEY idx_alert_action_history (project_id,alert_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='告警、人工归因、维护动作和结果的审计';
