CREATE TABLE `users` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `username` VARCHAR(50) NOT NULL COMMENT '用户名',
    `password_hash` VARCHAR(255) NOT NULL COMMENT 'BCrypt 密码哈希',
    `role` VARCHAR(20) NOT NULL DEFAULT 'user' COMMENT '角色: admin/user',
    `review_status` VARCHAR(20) NOT NULL DEFAULT 'pending' COMMENT '审核状态: pending/approved/rejected',
    `reviewed_by` BIGINT UNSIGNED DEFAULT NULL COMMENT '审核人用户ID',
    `reviewed_at` DATETIME DEFAULT NULL COMMENT '审核时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_users_username` (`username`),
    KEY `idx_users_review_status` (`review_status`),
    KEY `idx_users_role` (`role`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户表';
