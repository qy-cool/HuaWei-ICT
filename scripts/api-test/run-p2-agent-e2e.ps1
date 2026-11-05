Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$serverDirectory = Join-Path $projectRoot 'server'
$agentExecutable = Join-Path $env:TEMP 'cloide-agent-p2-e2e.exe'
$serverLog = Join-Path $env:TEMP 'cloide-p2-e2e-server.log'
$serverErrorLog = Join-Path $env:TEMP 'cloide-p2-e2e-server.err.log'
$databasePassword = $env:DB_PASSWORD

if (-not (Test-Path -LiteralPath $agentExecutable)) {
    throw "Agent executable is missing: $agentExecutable"
}
if ([string]::IsNullOrWhiteSpace($databasePassword)) {
    throw 'Set DB_PASSWORD in the current process before running this script.'
}

$serverEnvironment = @{
    DB_HOST = '127.0.0.1'
    DB_PORT = '3306'
    DB_NAME = 'cloide_metrics_validation'
    DB_USER = 'cloide'
    DB_PASSWORD = $databasePassword
    SERVER_ADDRESS = '127.0.0.1'
    SERVER_PORT = '18081'
    APP_ENV = 'test'
    SPRING_PROFILES_ACTIVE = 'test'
    JWT_SECRET = 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='
    AES_GCM_KEY = 'MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY='
    AGENT_MAX_CONNECTIONS = '3'
    AGENT_MAX_UNAUTHENTICATED_CONNECTIONS = '3'
    AGENT_HANDSHAKE_RATE_PER_MINUTE = '20'
    AGENT_HEARTBEAT_RATE_PER_MINUTE = '1'
    AGENT_HEARTBEAT_BURST = '1'
    AGENT_METRICS_RATE_PER_MINUTE = '1'
    AGENT_METRICS_BURST = '1'
}

$savedEnvironment = @{}
foreach ($entry in $serverEnvironment.GetEnumerator()) {
    $savedEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, 'Process')
    [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
}

$serverProcess = $null
try {
    $serverProcess = Start-Process -FilePath 'java.exe' -ArgumentList '-jar', 'target\cloide-server-0.0.1-SNAPSHOT.jar' `
        -WorkingDirectory $serverDirectory -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrorLog -PassThru
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri 'http://127.0.0.1:18081/api/health' -UseBasicParsing -TimeoutSec 1
            if ($response.StatusCode -eq 200) {
                break
            }
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not (Test-NetConnection -ComputerName '127.0.0.1' -Port 18081 -InformationLevel Quiet -WarningAction SilentlyContinue)) {
        throw 'Java validation service did not listen on 18081.'
    }

    $adminUsername = $env:CLOIDE_VALIDATION_ADMIN_USERNAME
    $adminPassword = $env:CLOIDE_VALIDATION_ADMIN_PASSWORD
    function Approve-ValidationAdministrator([string]$username) {
        if ($username -notmatch '^[A-Za-z0-9_]{3,50}$') {
            throw 'Validation administrator username contains unsupported characters.'
        }
        $env:MYSQL_PWD = $databasePassword
        try {
            mysql -h 127.0.0.1 -P 3306 -u cloide -D cloide_metrics_validation -e `
                "UPDATE users SET role = 'admin', review_status = 'approved', reviewed_by = NULL, reviewed_at = UTC_TIMESTAMP() WHERE username = '$username';"
            if ($LASTEXITCODE -ne 0) {
                throw 'Validation administrator approval failed.'
            }
        } finally {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        }
    }
    if ($env:CLOIDE_CREATE_VALIDATION_ADMIN -eq 'true') {
        $registerBody = @{ username = $adminUsername; password = $adminPassword } | ConvertTo-Json -Compress
        $registration = Invoke-WebRequest -Uri 'http://127.0.0.1:18081/api/auth/register' -Method Post `
            -ContentType 'application/json' -Body $registerBody -UseBasicParsing -TimeoutSec 10
        if ($registration.StatusCode -ne 200) {
            throw 'Validation administrator registration failed.'
        }
        Approve-ValidationAdministrator $adminUsername
    }
    if ([string]::IsNullOrWhiteSpace($adminUsername) -or [string]::IsNullOrWhiteSpace($adminPassword)) {
        $adminUsername = "p2admin$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
        $adminPassword = [Guid]::NewGuid().ToString('N')
        $registerBody = @{ username = $adminUsername; password = $adminPassword } | ConvertTo-Json -Compress
        $registration = Invoke-WebRequest -Uri 'http://127.0.0.1:18081/api/auth/register' -Method Post `
            -ContentType 'application/json' -Body $registerBody -UseBasicParsing -TimeoutSec 10
        if ($registration.StatusCode -ne 200) {
            throw 'Temporary validation administrator registration failed.'
        }
        Approve-ValidationAdministrator $adminUsername
    }

    $env:CLOIDE_BASE_URL = 'http://127.0.0.1:18081'
    $env:CLOIDE_VALIDATION_ADMIN_USERNAME = $adminUsername
    $env:CLOIDE_VALIDATION_ADMIN_PASSWORD = $adminPassword
    $env:CLOIDE_AGENT_EXECUTABLE = $agentExecutable
    $env:CLOIDE_RECONNECT_GATE_CONNECTIONS = '3'
    node (Join-Path $PSScriptRoot 'verify-go-agent-reconnect.mjs')
    if ($LASTEXITCODE -ne 0) {
        throw 'Go Agent reconnect validation failed.'
    }
    node (Join-Path $PSScriptRoot 'verify-go-agent-recovery.mjs')
    if ($LASTEXITCODE -ne 0) {
        throw 'Go Agent recovery validation failed.'
    }
    node (Join-Path $PSScriptRoot 'verify-p2-agent-limits.mjs')
    if ($LASTEXITCODE -ne 0) {
        throw 'P2 Agent limit validation failed.'
    }
} finally {
    if ($null -ne $serverProcess -and -not $serverProcess.HasExited) {
        Stop-Process -Id $serverProcess.Id -Force
    }
    foreach ($entry in $savedEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, 'Process')
    }
    Remove-Item Env:DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:CLOIDE_BASE_URL -ErrorAction SilentlyContinue
    Remove-Item Env:CLOIDE_VALIDATION_ADMIN_USERNAME -ErrorAction SilentlyContinue
    Remove-Item Env:CLOIDE_VALIDATION_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:CLOIDE_AGENT_EXECUTABLE -ErrorAction SilentlyContinue
    Remove-Item Env:CLOIDE_RECONNECT_GATE_CONNECTIONS -ErrorAction SilentlyContinue
    Remove-Item Env:CLOIDE_CREATE_VALIDATION_ADMIN -ErrorAction SilentlyContinue
}
