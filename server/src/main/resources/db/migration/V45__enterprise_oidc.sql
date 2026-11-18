CREATE TABLE oidc_login_attempts (
    state_hash VARCHAR(64) NOT NULL COMMENT '一次性 state 摘要',
    verifier_hash VARCHAR(64) NOT NULL COMMENT '浏览器持有的 PKCE verifier 摘要',
    nonce_hash VARCHAR(64) NOT NULL COMMENT '期望 ID Token nonce 摘要',
    expires_at DATETIME(6) NOT NULL COMMENT '登录有效截止时间',
    PRIMARY KEY (state_hash),
    KEY idx_oidc_attempt_expiry (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业登录浏览器绑定与单次交换状态';

CREATE TABLE oidc_account_mappings (
    user_id BIGINT UNSIGNED NOT NULL COMMENT '已审核的平台用户',
    issuer VARBINARY(1024) NOT NULL COMMENT '大小写敏感的企业发行方标识',
    subject_id VARBINARY(255) NOT NULL COMMENT '大小写敏感的企业用户 sub',
    assigned_by BIGINT UNSIGNED NOT NULL COMMENT '登记映射的管理员',
    assigned_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '登记时间',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_oidc_subject (issuer,subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='企业身份与本地账号的一对一映射';
