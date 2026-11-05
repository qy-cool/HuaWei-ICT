param(
    [string]$BaseUrl = 'http://localhost:18081',
    [string]$AdminUsername = $env:CLOIDE_VALIDATION_ADMIN_USERNAME,
    [string]$AdminPassword = $env:CLOIDE_VALIDATION_ADMIN_PASSWORD,
    [string]$DatabaseName = 'cloide_agent_ws_validation_20260721',
    [string]$DatabaseUser = 'susumon_aws',
    [string]$DatabasePassword = $env:CLOIDE_VALIDATION_DB_PASSWORD
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($AdminUsername) -or
        [string]::IsNullOrWhiteSpace($AdminPassword) -or
        [string]::IsNullOrWhiteSpace($DatabasePassword)) {
    throw 'Set CLOIDE_VALIDATION_ADMIN_USERNAME, CLOIDE_VALIDATION_ADMIN_PASSWORD and CLOIDE_VALIDATION_DB_PASSWORD.'
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null,
        [string]$BearerToken = $null
    )

    $request = [System.Net.HttpWebRequest]::Create("$BaseUrl$Path")
    $request.Method = $Method
    $request.ContentType = 'application/json'
    $request.Timeout = 10000
    if (-not [string]::IsNullOrWhiteSpace($BearerToken)) {
        $request.Headers['Authorization'] = "Bearer $BearerToken"
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Depth 10 -Compress
        $bytes = [Text.Encoding]::UTF8.GetBytes($json)
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream()
        try {
            $stream.Write($bytes, 0, $bytes.Length)
        } finally {
            $stream.Dispose()
        }
    }
    try {
        $response = $request.GetResponse()
    } catch [System.Net.WebException] {
        $response = $_.Exception.Response
        if ($null -eq $response) {
            throw
        }
    }
    try {
        $status = [int]$response.StatusCode
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        try {
            $body = $reader.ReadToEnd() | ConvertFrom-Json
        } finally {
            $reader.Dispose()
        }
        return @{Status = $status; Body = $body}
    } finally {
        $response.Dispose()
    }
}

function Assert-Equal {
    param([object]$Actual, [object]$Expected, [string]$Message)
    if ($Actual -ne $Expected) {
        throw "$Message. Expected=$Expected Actual=$Actual"
    }
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) {
        throw $Message
    }
}

$login = Invoke-Api -Method POST -Path '/api/auth/login' -Body @{
    username = $AdminUsername
    password = $AdminPassword
}
Assert-Equal $login.Status 200 'Admin login failed'
$adminToken = $login.Body.data.token
Assert-True (-not [string]::IsNullOrWhiteSpace($adminToken)) 'Admin JWT is missing'

$suffix = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$octet3 = [int](($suffix / 256) % 254) + 1
$octet4 = [int]($suffix % 254) + 1
$validationHost = "127.1.$octet3.$octet4"
$server = Invoke-Api -Method POST -Path '/api/servers' -BearerToken $adminToken -Body @{
    name = "agent-api-$suffix"
    host = $validationHost
    description = 'Agent API validation'
    ssh_host = $validationHost
    ssh_port = 22
    ssh_user = 'root'
    ssh_auth_type = 'password'
    ssh_password = 'validation-placeholder'
}
Assert-Equal $server.Status 200 'Server creation failed'
$serverId = $server.Body.data.id
Assert-True ($serverId -gt 0) 'Server ID is invalid'

$registered = Invoke-Api -Method POST -Path "/api/servers/$serverId/agent/register" -BearerToken $adminToken
Assert-Equal $registered.Status 200 'Agent Token registration failed'
Assert-Equal (($registered.Body.data.PSObject.Properties.Name) -join ',') 'server_id,agent_token,created_at' 'Agent Token field contract mismatch'
Assert-True (-not [string]::IsNullOrWhiteSpace($registered.Body.data.agent_token)) 'Agent Token is missing'
Assert-True ($registered.Body.data.created_at -match 'Z$') 'Agent Token time is not UTC'
$originalToken = $registered.Body.data.agent_token

$env:MYSQL_PWD = $DatabasePassword
try {
    $databaseRow = mysql -u $DatabaseUser -h 127.0.0.1 -P 3306 $DatabaseName -N -B -e `
        "SELECT agent_token_hash, agent_status FROM servers WHERE id=$serverId;"
} finally {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
}
$columns = $databaseRow -split "`t"
Assert-True ($columns[0] -like 'sha256:*') 'Database hash does not use sha256 prefix'
Assert-True ($columns[0] -notlike "*$originalToken*") 'Database contains plaintext Agent Token'
Assert-Equal $columns[1] 'offline' 'Agent should remain offline before WebSocket authentication'

$duplicateRegister = Invoke-Api -Method POST -Path "/api/servers/$serverId/agent/register" -BearerToken $adminToken
Assert-Equal $duplicateRegister.Status 409 'Duplicate registration HTTP status mismatch'
Assert-Equal $duplicateRegister.Body.code 40900 'Duplicate registration business code mismatch'

$rotated = Invoke-Api -Method POST -Path "/api/servers/$serverId/agent/rotate" -BearerToken $adminToken
Assert-Equal $rotated.Status 200 'Agent Token rotation failed'
Assert-True ($rotated.Body.data.agent_token -ne $originalToken) 'Rotated Agent Token did not change'

$revoked = Invoke-Api -Method DELETE -Path "/api/servers/$serverId/agent/revoke" -BearerToken $adminToken
Assert-Equal $revoked.Status 200 'Agent Token revoke failed'
Assert-Equal $revoked.Body.code 0 'Agent Token revoke business code mismatch'

$duplicateRevoke = Invoke-Api -Method DELETE -Path "/api/servers/$serverId/agent/revoke" -BearerToken $adminToken
Assert-Equal $duplicateRevoke.Status 409 'Duplicate revoke HTTP status mismatch'
Assert-Equal $duplicateRevoke.Body.code 40900 'Duplicate revoke business code mismatch'

$unauthorized = Invoke-Api -Method POST -Path "/api/servers/$serverId/agent/register"
Assert-Equal $unauthorized.Status 401 'Unauthenticated request HTTP status mismatch'
Assert-Equal $unauthorized.Body.code 40100 'Unauthenticated request business code mismatch'

$notFound = Invoke-Api -Method POST -Path '/api/servers/999999999/agent/register' -BearerToken $adminToken
Assert-Equal $notFound.Status 404 'Missing server HTTP status mismatch'
Assert-Equal $notFound.Body.code 40400 'Missing server business code mismatch'

$invalidId = Invoke-Api -Method POST -Path '/api/servers/0/agent/register' -BearerToken $adminToken
Assert-Equal $invalidId.Status 400 'Invalid server ID HTTP status mismatch'
Assert-Equal $invalidId.Body.code 40002 'Invalid server ID business code mismatch'

@{
    status = 'PASS'
    server_id = $serverId
    checks = 19
    token_values_logged = $false
} | ConvertTo-Json -Compress
