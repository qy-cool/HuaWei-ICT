#!/usr/bin/env bash
# cloide-shell 数据库与密钥备份脚本（MVP-8）
#
# 用法:
#   sudo bash backup.sh [--dir 备份目录] [--keep 保留份数]
# 环境变量覆盖: BACKUP_DIR / KEEP / ENV_FILE / DB_USER / DB_NAME / DB_HOST
#
# 行为:
#   1) mysqldump 一致性全量（--single-transaction，InnoDB 无锁）
#   2) 复制 /etc/cloide/server.env（JWT/AES 密钥，丢失不可恢复）
#   3) tar.gz 归档 + gzip -t 校验 + 非空校验
#   4) 按保留份数轮换旧备份
# 失败即非零退出（set -euo pipefail）。
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/cloide}"
KEEP="${KEEP:-7}"
ENV_FILE="${ENV_FILE:-/etc/cloide/server.env}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_USER="${DB_USER:-cloide}"
DB_NAME="${DB_NAME:-cloide}"

# 解析 --dir / --keep
while [ $# -gt 0 ]; do
    case "$1" in
        --dir) BACKUP_DIR="$2"; shift 2 ;;
        --keep) KEEP="$2"; shift 2 ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done

if [ "$(id -u)" -ne 0 ]; then
    echo "WARN: 建议以 root 执行（备份目录与 server.env 权限）" >&2
fi

# 从 server.env 提取数据库密码（不打印、不入日志）
# 注意：server.env 可能为 CRLF 行尾（Windows 编辑过），需剥离 \r 否则认证失败
if [ ! -f "$ENV_FILE" ]; then
    echo "FATAL: $ENV_FILE 不存在，密钥未备份——JWT/AES 密钥丢失将导致 SSH 凭据密文永久不可解密" >&2
    exit 1
fi
DB_PASSWORD=$(grep -E '^DB_PASSWORD=' "$ENV_FILE" | head -1 | cut -d= -f2- | tr -d '"' | tr -d "'" | tr -d '\r')
if [ -z "$DB_PASSWORD" ]; then
    echo "FATAL: 无法从 $ENV_FILE 提取 DB_PASSWORD" >&2
    exit 1
fi

mkdir -p "$BACKUP_DIR"
TS=$(date +%Y%m%d-%H%M%S)
OUT="$BACKUP_DIR/cloide-${TS}.tar.gz"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "[1/4] 导出数据库 $DB_NAME ..."
# MYSQL_PWD 只对本次命令生效，避免密码出现在进程列表。
MYSQL_PWD="$DB_PASSWORD" mysqldump -h "$DB_HOST" -u "$DB_USER" \
    --single-transaction --routines --triggers --set-gtid-purged=OFF \
    "$DB_NAME" > "$TMP/cloide.sql"

echo "[2/4] 备份密钥文件 $ENV_FILE ..."
cp "$ENV_FILE" "$TMP/server.env"

echo "[3/4] 归档并校验 ..."
tar -czf "$OUT" -C "$TMP" .
gzip -t "$OUT"
if [ ! -s "$OUT" ]; then
    echo "FATAL: 备份文件为空: $OUT" >&2
    exit 1
fi

echo "[4/4] 轮换旧备份（保留 $KEEP 份）..."
ls -1t "$BACKUP_DIR"/cloide-*.tar.gz 2>/dev/null | tail -n +"$((KEEP + 1))" | xargs -r rm -f

echo "OK: $OUT ($(du -h "$OUT" | cut -f1))"
echo "提示: 请将备份文件异地/离线留存一份（密钥与数据库同等重要）"
