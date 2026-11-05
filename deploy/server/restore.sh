#!/usr/bin/env bash
# cloide-shell 备份恢复脚本（MVP-8）
#
# 用法:
#   sudo bash restore.sh --backup /var/backups/cloide/cloide-YYYYMMDD-HHMMSS.tar.gz [--yes]
#
# 行为:
#   1) 校验备份包（gzip -t + sql 非空）
#   2) 恢复数据库（mysqldump 默认带 DROP TABLE IF EXISTS，覆盖式恢复）
#   3) 恢复 /etc/cloide/server.env（原文件备份为 server.env.bak-时间戳，权限 0600）
#   4) 输出恢复后验证清单（不自动启动应用，由操作者按手册执行）
# 恢复前必须停止应用（systemctl stop cloide-server）。
set -euo pipefail

BACKUP=""
ASSUME_YES=0
ENV_FILE="${ENV_FILE:-/etc/cloide/server.env}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_USER="${DB_USER:-cloide}"
DB_NAME="${DB_NAME:-cloide}"

while [ $# -gt 0 ]; do
    case "$1" in
        --backup) BACKUP="$2"; shift 2 ;;
        --yes) ASSUME_YES=1; shift ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done

if [ -z "$BACKUP" ]; then
    echo "用法: sudo bash restore.sh --backup <备份包路径> [--yes]" >&2
    exit 2
fi
if [ ! -f "$BACKUP" ]; then
    echo "FATAL: 备份包不存在: $BACKUP" >&2
    exit 1
fi
if [ "$(id -u)" -ne 0 ]; then
    echo "FATAL: 恢复操作必须以 root 执行" >&2
    exit 1
fi

echo "[1/4] 校验备份包 ..."
gzip -t "$BACKUP"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
tar -xzf "$BACKUP" -C "$TMP"
if [ ! -s "$TMP/cloide.sql" ]; then
    echo "FATAL: 备份包内无 cloide.sql 或为空" >&2
    exit 1
fi
if [ ! -f "$TMP/server.env" ]; then
    echo "WARN: 备份包内无 server.env（密钥未备份，SSH 凭据密文可能不可解密）" >&2
fi

if [ "$ASSUME_YES" -ne 1 ]; then
    echo "即将覆盖恢复数据库 $DB_NAME 与 $ENV_FILE，确认继续？(yes/no)"
    read -r CONFIRM
    if [ "$CONFIRM" != "yes" ]; then
        echo "已取消"
        exit 1
    fi
fi

DB_PASSWORD=$(grep -E '^DB_PASSWORD=' "$TMP/server.env" 2>/dev/null | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'" || true)
if [ -z "$DB_PASSWORD" ]; then
    echo "FATAL: 无法从备份的 server.env 提取 DB_PASSWORD" >&2
    exit 1
fi

echo "[2/4] 恢复数据库 $DB_NAME ..."
# MYSQL_PWD 只对本次命令生效，避免密码出现在进程列表。
MYSQL_PWD="$DB_PASSWORD" mysql -h "$DB_HOST" -u "$DB_USER" "$DB_NAME" < "$TMP/cloide.sql"

echo "[3/4] 恢复密钥文件 ..."
if [ -f "$ENV_FILE" ]; then
    cp -a "$ENV_FILE" "${ENV_FILE}.bak-$(date +%Y%m%d-%H%M%S)"
    echo "原 server.env 已备份为 ${ENV_FILE}.bak-*"
fi
install -m 0600 -o root -g root "$TMP/server.env" "$ENV_FILE"

echo "[4/4] 恢复完成，验证清单："
echo "  1) sudo systemctl start cloide-server"
echo "  2) sudo journalctl -u cloide-server -n 50 --no-pager  # Flyway 应回放到备份时点，无 ERROR"
echo "  3) curl -s http://127.0.0.1:18080/api/health"
echo "  4) curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18080/api/ready  # 期望 200"
echo "  5) 抽查服务器列表/告警记录与备份时点一致"
