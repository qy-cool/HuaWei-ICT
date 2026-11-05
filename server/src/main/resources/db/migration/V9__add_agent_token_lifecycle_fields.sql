ALTER TABLE `servers`
    ADD COLUMN `agent_token_created_at` DATETIME NULL COMMENT 'Agent Token 创建时间' AFTER `agent_token_hash`,
    ADD COLUMN `agent_token_rotated_at` DATETIME NULL COMMENT 'Agent Token 最近轮换时间' AFTER `agent_token_created_at`,
    ADD COLUMN `agent_token_revoked_at` DATETIME NULL COMMENT 'Agent Token 撤销时间' AFTER `agent_token_rotated_at`;
