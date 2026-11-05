-- MVP-10 Transactional Outbox：指标待发布事件表。
-- 语义：Metrics 入库与 outbox 写入必须在同一事务提交；发布器轮询 pending 行
-- 经 RabbitMQ 可靠投递后标记 published。Broker 不可用时事件保留在表中，恢复后补发。
CREATE TABLE message_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(36) NOT NULL COMMENT '冻结事件 ID（UUID），消费侧幂等主键',
    event_type VARCHAR(64) NOT NULL COMMENT '逻辑事件名，当前为 metrics.reported',
    payload TEXT NOT NULL COMMENT '冻结信封 JSON（message-contracts-v1 格式）',
    status VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT 'pending=待发布 / published=已确认发布',
    attempts INT NOT NULL DEFAULT 0 COMMENT '已尝试发布次数（退避重试）',
    next_attempt_at DATETIME NOT NULL COMMENT '下次可发布时刻（退避后）',
    last_error VARCHAR(500) NULL COMMENT '最近一次发布失败原因（不落敏感信息）',
    published_at DATETIME NULL COMMENT 'Broker 确认发布时刻',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_pending (status, next_attempt_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标待发布事件（Transactional Outbox）';
