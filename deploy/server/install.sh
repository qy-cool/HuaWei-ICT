#!/usr/bin/env bash
# cloide-shell Java 后端一次性安装脚本（仅在不重复执行的前提下使用）
#
# 此脚本以 root 身份执行：
#   1. 创建专用 cloide 系统用户
#   2. 创建 /opt/cloide/server 和 /var/lib/cloide
#   3. 上传 server.env（占位符须由运维手工填写）
#   4. 安装 systemd unit
#
# 凭据、JWT/AES/Agent 密钥不得写入此文件或仓库。
# 仅当目标主机唯一用于本次部署且已备份现有 /opt/cloide 时执行。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

RELEASE_ID="${RELEASE_ID:-REPLACE_WITH_RELEASE_ID}"
RELEASES_DIR="${RELEASES_DIR:-/opt/cloide/releases}"
SERVER_DIR="${SERVER_DIR:-/opt/cloide/server}"
ENV_FILE="${ENV_FILE:-/etc/cloide/server.env}"
JAR_PATH="${JAR_PATH:-$REPO_ROOT/server/target/cloide-server-0.0.1-SNAPSHOT.jar}"

if [ ! -f "$JAR_PATH" ]; then
    echo "[install] FATAL: JAR not found at $JAR_PATH" >&2
    exit 1
fi

if ! id -u cloide >/dev/null 2>&1; then
    useradd --system --home-dir /var/lib/cloide --shell /sbin/nologin cloide
fi

install -d -o root -g root -m 0755 "$RELEASES_DIR/$RELEASE_ID"
install -d -o root -g root -m 0755 "$SERVER_DIR"
install -d -o root -g root -m 0700 /etc/cloide
install -d -o cloide -g cloide -m 0700 /var/lib/cloide

install -m 0644 -o root -g root "$SCRIPT_DIR/cloide-server.service" /etc/systemd/system/cloide-server.service
if [ ! -f "$ENV_FILE" ]; then
    install -m 0600 -o root -g root "$SCRIPT_DIR/cloide-server.env.example" "$ENV_FILE"
    echo "[install] installed $ENV_FILE from template; must edit secrets manually"
fi

install -m 0644 -o root -g root "$JAR_PATH" "$RELEASES_DIR/$RELEASE_ID/cloide-server-0.0.1-SNAPSHOT.jar"

ln -sfn "$RELEASES_DIR/$RELEASE_ID/cloide-server-0.0.1-SNAPSHOT.jar" "$SERVER_DIR/cloide-server.jar"

systemctl daemon-reload
systemctl enable --now cloide-server
systemctl status cloide-server --no-pager