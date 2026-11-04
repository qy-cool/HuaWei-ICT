CREATE TABLE `commands` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `server_id` BIGINT UNSIGNED NOT NULL COMMENT '服务器ID',
    `created_by` BIGINT UNSIGNED DEFAULT NULL COMMENT '创建命令的用户ID',
    `command_text` TEXT NOT NULL COMMENT '命令内容',
    `status` VARCHAR(20) NOT NULL DEFAULT 'created' COMMENT '命令状态: created/running/success/failed/canceled',
    `exit_code` INT DEFAULT NULL COMMENT '命令退出码',
    `output_summary` TEXT COMMENT '命令输出摘要',
    `started_at` DATETIME DEFAULT NULL COMMENT '开始执行时间',
    `finished_at` DATETIME DEFAULT NULL COMMENT '执行结束时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_commands_server_time` (`server_id`, `created_at`),
    KEY `idx_commands_created_by` (`created_by`),
    KEY `idx_commands_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='命令记录表';
