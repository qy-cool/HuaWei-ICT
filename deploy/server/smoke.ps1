# cloide-shell Java Backend IPv4 本机 smoke 验证脚本
#
# 默认验证本机 127.0.0.1:18080 的最小可观测性，可用 -BaseUrl 覆盖：
# - 监听地址
# - /api/health
# - /api/ready
# - 不依赖 MySQL、AES/JWT 密钥或 Flyway 之外的运行时数据。
# - 不发起任何登录或写入操作。

[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:18080',
    [int]$TimeoutSeconds = 10
)

$ErrorActionPreference = 'Stop'

function Write-Step {
    param([string]$Message)
    Write-Host "[smoke] $Message" -ForegroundColor Cyan
}

function Fail-Step {
    param([string]$Message)
    Write-Host "[smoke] FAIL: $Message" -ForegroundColor Red
    exit 1
}

$smokeUri = [Uri]$BaseUrl
$smokePort = $smokeUri.Port
$smokeHost = $smokeUri.Host

Write-Step "verifying listener state for $BaseUrl"
if ($smokeHost -eq '127.0.0.1' -or $smokeHost -eq 'localhost') {
    try {
        $listener = Get-NetTCPConnection -State Listen -LocalAddress '127.0.0.1' -LocalPort $smokePort -ErrorAction Stop
    } catch {
        try {
            $listener = netstat -ano | Select-String "127\.0\.0\.1:$smokePort.*LISTENING"
        } catch {
            $listener = $null
        }
    }
    if (-not $listener) {
        Fail-Step "127.0.0.1:$smokePort is not listening; start the Java backend first."
    }
    Write-Step "listener is up"
} else {
    Write-Step "WARN: listener check skipped for non-loopback host $smokeHost"
}

Write-Step "GET /api/health"
$health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/health" -TimeoutSec $TimeoutSeconds -ErrorAction Stop
if ($health.data.status -ne 'UP') {
    Fail-Step "/api/health did not return status=UP: $($health | ConvertTo-Json -Depth 2)"
}
Write-Step "health OK"

Write-Step "GET /api/ready"
try {
    $ready = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/ready" -TimeoutSec $TimeoutSeconds -ErrorAction Stop
    if ($ready.data.status -ne 'UP') {
        Fail-Step "/api/ready did not return status=UP: $($ready | ConvertTo-Json -Depth 2)"
    }
    Write-Step "ready OK (database reachable)"
} catch {
    Write-Host "[smoke] WARN: /api/ready failed: $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "[smoke] note: ready requires database connectivity" -ForegroundColor Yellow
}

Write-Step "all smoke checks passed"
exit 0