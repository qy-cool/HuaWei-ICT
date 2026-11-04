ALTER TABLE `servers`
    ADD COLUMN `ssh_host_key_algorithm` VARCHAR(64) DEFAULT NULL COMMENT '已确认的 SSH 主机公钥算法' AFTER `ssh_private_key_passphrase_encrypted`,
    ADD COLUMN `ssh_host_key_fingerprint` VARCHAR(64) DEFAULT NULL COMMENT '已确认的 OpenSSH SHA-256 主机公钥指纹' AFTER `ssh_host_key_algorithm`,
    ADD COLUMN `ssh_host_key_verified_by` BIGINT UNSIGNED DEFAULT NULL COMMENT '最近确认或轮换指纹的管理员用户 ID' AFTER `ssh_host_key_fingerprint`,
    ADD COLUMN `ssh_host_key_verified_at` DATETIME(6) DEFAULT NULL COMMENT '最近确认或轮换指纹的时间' AFTER `ssh_host_key_verified_by`;
