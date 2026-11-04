-- 给 alert_rules 增加软删除字段，不修改 V5 原始迁移文件。
ALTER TABLE `alert_rules`
    ADD COLUMN `deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '软删除标记: 0未删除,1已删除' AFTER `enabled`,
    ADD COLUMN `deleted_at` DATETIME DEFAULT NULL COMMENT '删除时间' AFTER `deleted`,
    ADD KEY `idx_alert_rules_deleted` (`deleted`);

-- 创建告警状态表，维护每条规则在每台服务器上的当前越界状态。
CREATE TABLE `alert_states` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `rule_id` BIGINT UNSIGNED NOT NULL COMMENT '告警规则ID',
    `server_id` BIGINT UNSIGNED NOT NULL COMMENT '触发服务器ID',
    `active` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否处于越界状态: 0否,1是',
    `alert_record_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '当前活动告警记录ID',
    `first_triggered_at` DATETIME NOT NULL COMMENT '本轮异常首次触发时间',
    `last_triggered_at` DATETIME NOT NULL COMMENT '最近一次命中时间',
    `resolved_at` DATETIME DEFAULT NULL COMMENT '最近恢复时间',
    `version` INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_alert_states_rule_server` (`rule_id`, `server_id`),
    KEY `idx_alert_states_active` (`active`),
    KEY `idx_alert_states_server` (`server_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='告警状态表';
