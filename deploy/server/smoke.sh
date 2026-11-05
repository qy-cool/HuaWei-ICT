#!/usr/bin/env bash
# cloide-shell Java Backend IPv4 本机 smoke 验证脚本
#
# 默认验证本机 127.0.0.1:18080 的最小可观测性，可用 CLOIDE_SMOKE_BASE_URL 覆盖：
# - 监听地址
# - /api/health
# - /api/ready
# - 不依赖 MySQL、AES/JWT 密钥或 Flyway 之外的运行时数据。
# - 不发起任何登录或写入操作。
#
# 使用方法（云服务器或本机）：
#   chmod +x deploy/server/smoke.sh
#   deploy/server/smoke.sh
#
# 可选环境变量：
#   CLOIDE_SMOKE_BASE_URL   默认 http://127.0.0.1:18080
#   CLOIDE_SMOKE_TIMEOUT    curl 超时秒数，默认 10

set -euo pipefail

BASE_URL="${CLOIDE_SMOKE_BASE_URL:-http://127.0.0.1:18080}"
TIMEOUT="${CLOIDE_SMOKE_TIMEOUT:-10}"

# 从 BASE_URL 解析监听检查用的 host/port；非回环地址跳过本地监听检查。
HOSTPORT="${BASE_URL#*://}"
HOSTPORT="${HOSTPORT%%/*}"
SMOKE_HOST="${HOSTPORT%%:*}"
SMOKE_PORT="${HOSTPORT##*:}"
if [ "${SMOKE_PORT}" = "${HOSTPORT}" ]; then
    case "${BASE_URL}" in
        https://*) SMOKE_PORT=443 ;;
        *) SMOKE_PORT=80 ;;
    esac
fi

log() { printf '[smoke] %s\n' "$*"; }
fail() { printf '[smoke] FAIL: %s\n' "$*" >&2; exit 1; }

log "verifying listener state for ${BASE_URL}"

if [ "${SMOKE_HOST}" = "127.0.0.1" ] || [ "${SMOKE_HOST}" = "localhost" ]; then
    if command -v ss >/dev/null 2>&1; then
        if ! ss -ltn "( sport = :${SMOKE_PORT} )" | grep -Eq "127\\.0\\.0\\.1:${SMOKE_PORT}|\\[::ffff:127\\.0\\.0\\.1\\]:${SMOKE_PORT}|0\\.0\\.0\\.0:${SMOKE_PORT}|\\*:${SMOKE_PORT}|\\[::\\]:${SMOKE_PORT}"; then
            fail "127.0.0.1:${SMOKE_PORT} is not listening; start the Java backend first."
        fi
    elif command -v netstat >/dev/null 2>&1; then
        if ! netstat -ltn 2>/dev/null | grep -Eq "127\\.0\\.0\\.1:${SMOKE_PORT}|0\\.0\\.0\\.0:${SMOKE_PORT}"; then
            fail "127.0.0.1:${SMOKE_PORT} is not listening; start the Java backend first."
        fi
    else
        log "WARN: neither ss nor netstat available, skipping listener check"
    fi
else
    log "WARN: listener check skipped for non-loopback host ${SMOKE_HOST}"
fi

log "GET /api/health"
HEALTH="$(curl --silent --show-error --fail --max-time "$TIMEOUT" "${BASE_URL}/api/health")"
STATUS="$(printf '%s' "$HEALTH" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')"
if [ "$STATUS" != "UP" ]; then
    fail "/api/health did not return status=UP: $HEALTH"
fi

log "GET /api/ready"
READY="$(curl --silent --show-error --fail --max-time "$TIMEOUT" "${BASE_URL}/api/ready" || true)"
if [ -z "$READY" ]; then
    log "WARN: /api/ready failed; database may not be reachable"
else
    READY_STATUS="$(printf '%s' "$READY" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p')"
    if [ "$READY_STATUS" != "UP" ]; then
        log "WARN: /api/ready did not return status=UP: $READY"
    fi
fi

log "all smoke checks passed"