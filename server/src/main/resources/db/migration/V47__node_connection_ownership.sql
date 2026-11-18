CREATE TABLE node_connection_owners (
    server_id BIGINT UNSIGNED NOT NULL COMMENT '已登记节点',
    instance_id CHAR(36) NOT NULL COMMENT '持有连接的中控进程标识',
    session_id VARCHAR(128) NOT NULL COMMENT '已认证的 WebSocket 会话',
    claimed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '最近一次领取时间',
    PRIMARY KEY (server_id),
    KEY idx_node_connection_owners_instance (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='多中控节点连接归属，重连覆盖旧持有者';
