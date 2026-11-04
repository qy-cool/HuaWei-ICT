-- MVP-11 消息消费幂等记录：消费者按 event_id 去重，保证"至少一次投递"下
-- 重复消息不产生第二次业务效果。业务处理与消费记录必须在同一数据库事务提交。
CREATE TABLE message_consume_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    consumer VARCHAR(64) NOT NULL COMMENT '消费者标识（当前 alert-evaluator）',
    event_id VARCHAR(36) NOT NULL COMMENT '冻结事件 ID（消费幂等主键）',
    status VARCHAR(20) NOT NULL DEFAULT 'consumed' COMMENT 'consumed=已消费 / failed=失败留痕',
    attempts INT NOT NULL DEFAULT 0 COMMENT '已尝试消费次数',
    last_error VARCHAR(500) NULL COMMENT '最近一次失败原因（不落敏感信息）',
    consumed_at DATETIME NULL COMMENT '成功消费时刻（UTC）',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_consume_event (consumer, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息消费幂等记录（Transactional Outbox 消费侧）';
