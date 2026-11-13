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
umask 077

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

if ! [[ "$KEEP" =~ ^[1-9][0-9]*$ ]] || [ -L "$BACKUP_DIR" ]; then
    echo "FATAL: 需要私有备份目录和至少一份保留副本" >&2
    exit 1
fi
if [ ! -d "$BACKUP_DIR" ]; then mkdir -p -m 0700 -- "$BACKUP_DIR"; fi
if [ ! -O "$BACKUP_DIR" ] || [ "$(stat -c %a -- "$BACKUP_DIR")" != 700 ]; then
    echo "FATAL: 请使用执行者专属的 0700 备份目录，先核对现有目录的所有者和权限" >&2
    exit 1
fi
TS=$(date +%Y%m%d-%H%M%S)
TMP=$(mktemp -d)
PENDING=""
trap 'rm -rf -- "$TMP"; if [ -n "$PENDING" ]; then rm -f -- "$PENDING"; fi' EXIT
PENDING=$(mktemp -- "$BACKUP_DIR/.pending-XXXXXXXX")
OUT="$BACKUP_DIR/cloide-${TS}-${PENDING##*.pending-}.tar.gz"

echo "[1/4] 导出数据库 $DB_NAME ..."
# MYSQL_PWD 只对本次命令生效，避免密码出现在进程列表。
MYSQL_PWD="$DB_PASSWORD" mysqldump -h "$DB_HOST" -u "$DB_USER" \
    --single-transaction --routines --triggers --set-gtid-purged=OFF \
    "$DB_NAME" > "$TMP/cloide.sql"

echo "[2/4] 备份密钥文件 $ENV_FILE ..."
cp "$ENV_FILE" "$TMP/server.env"

echo "[3/4] 归档并校验 ..."
tar -czf "$PENDING" -C "$TMP" .
gzip -t "$PENDING"
if [ ! -s "$PENDING" ]; then
    echo "FATAL: 备份文件为空" >&2
    exit 1
fi
ln -- "$PENDING" "$OUT"

echo "[4/4] 轮换旧备份（保留 $KEEP 份）..."
find "$BACKUP_DIR" -maxdepth 1 -type f -name 'cloide-*.tar.gz' -printf '%T@ %p\0' \
    | sort -zrn | tail -z -n +"$((KEEP + 1))" | cut -z -d ' ' -f 2- | xargs -0 -r rm -f --

echo "OK: $OUT ($(du -h "$OUT" | cut -f1))"
echo "提示: 请将备份文件异地/离线留存一份（密钥与数据库同等重要）"
