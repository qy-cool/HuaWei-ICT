CREATE TABLE project_coding_tasks (
    task_id CHAR(36) NOT NULL COMMENT '任务和工作树标识',
    project_id BIGINT NOT NULL COMMENT '任务所属项目',
    server_id BIGINT NOT NULL COMMENT '开发节点',
    created_by BIGINT NOT NULL COMMENT '任务发起人',
    request_id CHAR(36) NOT NULL COMMENT '用户幂等标识',
    repository VARCHAR(64) NOT NULL COMMENT '节点登记的项目仓库别名',
    base_sha CHAR(40) NOT NULL COMMENT '不可变基准提交',
    prompt TEXT NOT NULL COMMENT '原始工程需求',
    permission_mode VARCHAR(12) NOT NULL COMMENT 'build、guarded 或 yolo',
    plan_only BOOLEAN NOT NULL COMMENT '独立的只读计划开关',
    status VARCHAR(24) NOT NULL COMMENT '任务当前状态',
    worker_id CHAR(36) NULL COMMENT '本次认领的进程代次',
    last_seen TIMESTAMP(6) NULL COMMENT '最后授权续租时间',
    last_seq INT NOT NULL DEFAULT 0 COMMENT '已提交事件游标',
    approval_id CHAR(36) NULL COMMENT '待决变更标识',
    approval_seq INT NULL COMMENT '绑定到具体拟改内容和摘要的审批事件',
    approval_decision BOOLEAN NULL COMMENT '一次性审批结果',
    decided_by BIGINT NULL COMMENT '最近一次审批操作者',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '创建时间',
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_coding_request (project_id,created_by,request_id),
    KEY idx_coding_queue (server_id,project_id,repository,status,created_at),
    KEY idx_coding_lease (status,last_seen),
    KEY idx_coding_history (project_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目隔离的 AI 编码任务';

CREATE TABLE project_coding_events (
    task_id CHAR(36) NOT NULL COMMENT '所属任务',
    seq INT NOT NULL COMMENT '节点事件游标',
    event_json MEDIUMTEXT NOT NULL COMMENT '可重放的结构化事件、审批内容或补丁',
    PRIMARY KEY (task_id,seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='编码任务事件与审查证据';

CREATE TABLE project_coding_decisions (
    task_id CHAR(36) NOT NULL COMMENT '工程任务',
    approval_id CHAR(36) NOT NULL COMMENT '绑定本次文件与摘要的变更',
    actor_id BIGINT NOT NULL COMMENT '操作者',
    allowed BOOLEAN NOT NULL COMMENT '审批结果',
    decided_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '审批时间',
    PRIMARY KEY (task_id,approval_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单次文件变更审批记录';
