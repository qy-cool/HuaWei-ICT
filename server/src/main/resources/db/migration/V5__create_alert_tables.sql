CREATE TABLE `alert_rules` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `server_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '服务器ID,为空表示通用规则',
    `metric` VARCHAR(30) NOT NULL COMMENT '告警指标: cpu/memory/disk/temperature/load',
    `operator` VARCHAR(5) NOT NULL COMMENT '比较操作符: >/>=/</<=',
    `threshold_value` DECIMAL(12,2) NOT NULL COMMENT '告警阈值',
    `level` VARCHAR(20) NOT NULL COMMENT '告警等级: warning/critical',
    `enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否启用: 0否,1是',
    `created_by` BIGINT UNSIGNED DEFAULT NULL COMMENT '创建人用户ID',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_alert_rules_server_id` (`server_id`),
    KEY `idx_alert_rules_enabled` (`enabled`),
    KEY `idx_alert_rules_metric` (`metric`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警规则表';

CREATE TABLE `alert_records` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `rule_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '告警规则ID',
    `server_id` BIGINT UNSIGNED NOT NULL COMMENT '服务器ID',
    `metric` VARCHAR(30) NOT NULL COMMENT '告警指标',
    `current_value` DECIMAL(12,2) NOT NULL COMMENT '触发时当前值',
    `threshold_value` DECIMAL(12,2) NOT NULL COMMENT '触发阈值',
    `level` VARCHAR(20) NOT NULL COMMENT '告警等级: warning/critical',
    `status` VARCHAR(20) NOT NULL DEFAULT 'unread' COMMENT '告警状态: unread/read/resolved',
    `message` VARCHAR(500) DEFAULT NULL COMMENT '告警消息',
    `read_by` BIGINT UNSIGNED DEFAULT NULL COMMENT '标记已读用户ID',
    `read_at` DATETIME DEFAULT NULL COMMENT '标记已读时间',
    `triggered_at` DATETIME NOT NULL COMMENT '告警触发时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_alert_records_server_time` (`server_id`, `triggered_at`),
    KEY `idx_alert_records_status` (`status`),
    KEY `idx_alert_records_rule_id` (`rule_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警记录表';
