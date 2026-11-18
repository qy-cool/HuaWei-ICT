CREATE TABLE project_runs (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '排队顺序',
    job_id VARCHAR(36) NOT NULL COMMENT '节点端持久去重标识',
    request_id VARCHAR(36) NOT NULL COMMENT '用户请求幂等标识',
    project_id BIGINT NOT NULL COMMENT '所属项目',
    created_by BIGINT NOT NULL COMMENT '发起用户',
    server_id BIGINT NOT NULL COMMENT '目标节点',
    repository VARCHAR(64) NOT NULL COMMENT '节点预登记的项目仓库名',
    commit_sha VARCHAR(40) NOT NULL COMMENT '完整 Git 提交 SHA',
    status VARCHAR(20) NOT NULL COMMENT '节点作业状态',
    exit_code INT NULL COMMENT '真实退出码，无进程时为负值',
    output TEXT NULL COMMENT '节点返回的限长终态报告',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC 创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_runs_job_id (job_id),
    UNIQUE KEY uk_project_runs_request (project_id, created_by, request_id),
    KEY idx_project_runs_server_queue (server_id, status, id),
    KEY idx_project_runs_history (project_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目测试作业与不可变终态';

CREATE TABLE project_run_logs (
    job_id VARCHAR(36) NOT NULL COMMENT '作业 UUID',
    seq INT NOT NULL COMMENT '节点发出的日志分段序号',
    log_text TEXT NOT NULL COMMENT '限长日志片段',
    PRIMARY KEY (job_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按作业和游标分页的日志';
