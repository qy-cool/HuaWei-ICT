CREATE TABLE node_enrollment_tickets (
    token_hash BINARY(32) NOT NULL COMMENT '一次性登记令牌的 SHA-256 摘要',
    node_name VARCHAR(100) NOT NULL COMMENT '管理员预登记的节点显示名',
    expires_at DATETIME(6) NOT NULL COMMENT '截止时间 UTC',
    PRIMARY KEY (token_hash),
    KEY idx_enrollment_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='节点登记邀请，兑换时事务删除';
