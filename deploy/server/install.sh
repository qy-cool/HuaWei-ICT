#!/usr/bin/env bash
# Cloide 中控 JAR 和 systemd 单元安装器。
#
# 此脚本以 root 身份执行：
#   1. 创建专用 cloide 系统用户
#   2. 创建 /opt/cloide/server 和 /var/lib/cloide
#   3. 上传 server.env（占位符须由运维手工填写）
#   4. 安装 systemd unit
#
# 凭据、JWT/AES/Agent 密钥不得写入此文件或仓库。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

RELEASES_DIR="${RELEASES_DIR:-/opt/cloide/releases}"
SERVER_DIR="${SERVER_DIR:-/opt/cloide/server}"
ENV_FILE="${ENV_FILE:-/etc/cloide/server.env}"
JAR_PATH="${JAR_PATH:-$REPO_ROOT/server/target/cloide-server-0.0.1-SNAPSHOT.jar}"

if [ ! -f "$JAR_PATH" ]; then
    echo "[install] FATAL: JAR not found at $JAR_PATH" >&2
    exit 1
fi

jar_digest=$(sha256sum -- "$JAR_PATH" | cut -d ' ' -f 1)
RELEASE_ID="${RELEASE_ID:-${jar_digest:0:16}}"
if [[ ! "$RELEASE_ID" =~ ^[a-zA-Z0-9][a-zA-Z0-9._-]{0,100}$ ]]; then
    echo "[install] invalid release identifier" >&2
    exit 1
fi
target_jar="$RELEASES_DIR/$RELEASE_ID/cloide-server-0.0.1-SNAPSHOT.jar"
if [[ -e "$target_jar" ]] && ! cmp -s -- "$JAR_PATH" "$target_jar"; then
    echo "[install] release identifier already belongs to a different JAR" >&2
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

if [[ ! -e "$target_jar" ]]; then
    install -m 0644 -o root -g root "$JAR_PATH" "$target_jar"
fi

previous_target=$(readlink "$SERVER_DIR/cloide-server.jar" || true)
ln -sfn "$target_jar" "$SERVER_DIR/cloide-server.jar"

systemctl daemon-reload
if grep -Eq '^[^#[:space:]][^=]*=.*REPLACE_WITH_' "$ENV_FILE"; then
    echo "[install] installed $RELEASE_ID. Configure $ENV_FILE, then run systemctl enable --now cloide-server."
    exit 0
fi
systemctl enable cloide-server
if systemctl is-active --quiet cloide-server && [[ "$previous_target" != "$target_jar" ]]; then
    systemctl restart cloide-server
else
    systemctl start cloide-server
fi
systemctl status cloide-server --no-pager
