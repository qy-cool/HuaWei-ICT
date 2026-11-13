CREATE TABLE node_job_capabilities (
    server_id BIGINT UNSIGNED NOT NULL COMMENT '报告能力的已认证节点',
    details_json JSON NOT NULL COMMENT '节点架构、容量、工具链及按项目登记的仓库别名',
    reported_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '中控接收时间',
    PRIMARY KEY (server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测试节点最近报告的作业能力';
