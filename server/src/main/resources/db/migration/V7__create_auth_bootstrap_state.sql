CREATE TABLE `auth_bootstrap_state` (
    `id` BIGINT UNSIGNED NOT NULL COMMENT '固定主键，当前仅使用1',
    `admin_initialized` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '首管理员是否已初始化: 0否/1是',
    `initialized_user_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '首管理员用户ID',
    `initialized_at` DATETIME DEFAULT NULL COMMENT '首管理员初始化时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='认证首管理员初始化状态';

INSERT INTO `auth_bootstrap_state` (
    `id`,
    `admin_initialized`,
    `initialized_user_id`,
    `initialized_at`
)
SELECT
    1,
    CASE WHEN `first_admin`.`id` IS NULL THEN 0 ELSE 1 END,
    `first_admin`.`id`,
    `first_admin`.`created_at`
FROM (SELECT 1) AS `bootstrap_seed`
LEFT JOIN (
    SELECT
        `id`,
        `created_at`
    FROM `users`
    WHERE `role` = 'admin'
      AND `review_status` = 'approved'
    ORDER BY `id` ASC
    LIMIT 1
) AS `first_admin` ON 1 = 1;
