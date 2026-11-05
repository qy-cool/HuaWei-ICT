-- cloide-shell local MySQL initialization script.
-- This script is only for local development. Do not use this password in production.
--
-- 密码通过交互式替换或未跟踪环境变量提供，不要在本文件落真实口令：
--   sed -i "s/<LOCAL_DEV_PASSWORD>/你的本机密码/" scripts/local-mysql-init.sql

CREATE DATABASE IF NOT EXISTS `cloide`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'cloide'@'localhost'
    IDENTIFIED BY '<LOCAL_DEV_PASSWORD>';

CREATE USER IF NOT EXISTS 'cloide'@'127.0.0.1'
    IDENTIFIED BY '<LOCAL_DEV_PASSWORD>';

GRANT ALL PRIVILEGES ON `cloide`.* TO 'cloide'@'localhost';
GRANT ALL PRIVILEGES ON `cloide`.* TO 'cloide'@'127.0.0.1';

FLUSH PRIVILEGES;
