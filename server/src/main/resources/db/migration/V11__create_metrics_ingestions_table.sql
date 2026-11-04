CREATE TABLE `metrics_ingestions` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `server_id` BIGINT UNSIGNED NOT NULL COMMENT '指标所属服务器ID',
    `message_id` VARCHAR(36) NOT NULL COMMENT 'Agent指标消息UUID',
    `collected_at` DATETIME NOT NULL COMMENT 'Agent采样时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_metrics_ingestions_server_message` (`server_id`, `message_id`),
    KEY `idx_metrics_ingestions_server_collected` (`server_id`, `collected_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='指标消息幂等接收记录表';
