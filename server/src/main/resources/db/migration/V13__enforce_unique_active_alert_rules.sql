-- 将历史活跃重复规则转为软删除，保留最早创建的规则供告警评估继续使用。
UPDATE alert_rules AS duplicate_rule
INNER JOIN alert_rules AS retained_rule
    ON duplicate_rule.id > retained_rule.id
    AND duplicate_rule.server_id <=> retained_rule.server_id
    AND duplicate_rule.metric = retained_rule.metric
    AND duplicate_rule.operator = retained_rule.operator
    AND duplicate_rule.threshold_value = retained_rule.threshold_value
    AND duplicate_rule.level = retained_rule.level
    AND retained_rule.deleted = 0
SET duplicate_rule.deleted = 1,
    duplicate_rule.deleted_at = COALESCE(duplicate_rule.deleted_at, NOW())
WHERE duplicate_rule.deleted = 0;

-- 已软删除记录的生成列为 NULL，允许保留多条历史记录；活跃记录使用 0 表示通用规则范围。
ALTER TABLE alert_rules
    ADD COLUMN active_server_scope_id BIGINT UNSIGNED
        GENERATED ALWAYS AS (CASE WHEN deleted = 0 THEN COALESCE(server_id, 0) ELSE NULL END) STORED
        COMMENT '活跃规则的服务器范围，NULL 表示软删除记录' AFTER deleted_at,
    ADD UNIQUE KEY uk_alert_rules_active_signature
        (active_server_scope_id, metric, operator, threshold_value, level);
