CREATE TABLE `ssh_sessions` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `session_id` VARCHAR(64) NOT NULL COMMENT 'SSH 会话ID',
    `server_id` BIGINT UNSIGNED NOT NULL COMMENT '服务器ID',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `status` VARCHAR(20) NOT NULL DEFAULT 'opening' COMMENT '会话状态: opening/open/closed/timeout/error',
    `close_reason` VARCHAR(200) DEFAULT NULL COMMENT '关闭原因',
    `opened_at` DATETIME NOT NULL COMMENT '打开时间',
    `closed_at` DATETIME DEFAULT NULL COMMENT '关闭时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ssh_sessions_session_id` (`session_id`),
    KEY `idx_ssh_sessions_server_time` (`server_id`, `opened_at`),
    KEY `idx_ssh_sessions_user_time` (`user_id`, `opened_at`),
    KEY `idx_ssh_sessions_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='SSH 会话记录表';
