[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
Add-Type -AssemblyName System.Net.Http

$root = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$resultPath = Join-Path $PSScriptRoot 'loopback-surface-result.json'
$backendLog = Join-Path $PSScriptRoot 'local-backend-runtime.log'
$credential = ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N'))
$fixture = $null
$backend = $null
$old = @{}

function Set-TemporaryEnvironment([hashtable]$values) {
    foreach ($name in $values.Keys) {
        $script:old[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process')
    }
}
function Restore-Environment {
    foreach ($name in $script:old.Keys) { [Environment]::SetEnvironmentVariable($name, $script:old[$name], 'Process') }
    $script:old.Clear()
}
function Start-Redirected([string]$file, [string]$arguments) {
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $file
    $psi.Arguments = $arguments
    $psi.WorkingDirectory = $root
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    if (-not $process.Start()) { throw 'Could not start isolated QA process.' }
    return $process
}
function Read-LineBounded([Diagnostics.Process]$process, [TimeSpan]$timeout) {
    $task = $process.StandardOutput.ReadLineAsync()
    if (-not $task.Wait($timeout)) { throw 'Timed out waiting for an exact process readiness event.' }
    return $task.Result
}
function Invoke-Json($http, [string]$path, [string]$body) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Post, "http://127.0.0.1:8080$path")
    $request.Content = [Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
    try { return $http.SendAsync($request).GetAwaiter().GetResult() } finally { $request.Dispose() }
}
function Invoke-Protected($http, [string]$access) {
    $request = [Net.Http.HttpRequestMessage]::new([Net.Http.HttpMethod]::Get,
        "http://127.0.0.1:8080/api/trips/$([Guid]::NewGuid())/trip-items/$([Guid]::NewGuid())")
    $request.Headers.Authorization = [Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $access)
    try { return $http.SendAsync($request).GetAwaiter().GetResult() } finally { $request.Dispose() }
}

try {
    $probe = [Net.Sockets.TcpClient]::new()
    try {
        $connect = $probe.ConnectAsync('127.0.0.1', 8080)
        if ($connect.Wait(300) -and $probe.Connected) { throw 'Refusing runtime QA because 127.0.0.1:8080 is already occupied.' }
    } catch [AggregateException] {} finally { $probe.Dispose() }

    Set-TemporaryEnvironment @{ STOG_FIXTURE_CREDENTIAL = $credential }
    $fixtureScript = Join-Path $PSScriptRoot 'provider_fixture.py'
    $fixture = Start-Redirected 'python.exe' ('"' + $fixtureScript + '"')
    Restore-Environment
    $readyLine = Read-LineBounded $fixture ([TimeSpan]::FromSeconds(10))
    if ($readyLine -notmatch '^READY ([0-9]+)$') { throw 'Loopback provider fixture emitted no valid readiness event.' }
    $fixturePort = [int]$Matches[1]

    Set-TemporaryEnvironment @{
        STOG_AUTH_KAKAO_ENABLED = 'true'
        STOG_AUTH_KAKAO_EXPECTED_APP_ID = '12345'
        STOG_AUTH_KAKAO_FIXTURE_MODE = 'true'
        STOG_AUTH_KAKAO_TOKEN_INFO_URI = "http://127.0.0.1:$fixturePort/token-info"
        STOG_AUTH_KAKAO_USER_INFO_URI = "http://127.0.0.1:$fixturePort/user-info"
    }
    $launcher = Join-Path $root 'backend/scripts/Start-LocalBackend.ps1'
    $backend = Start-Redirected 'powershell.exe' ("-NoProfile -ExecutionPolicy Bypass -File `"$launcher`"")
    Restore-Environment
    $writer = [IO.StreamWriter]::new($backendLog, $false, [Text.UTF8Encoding]::new($false))
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds(120)
        $started = $false
        while (-not $started) {
            $remaining = $deadline - [DateTime]::UtcNow
            if ($remaining -le [TimeSpan]::Zero) { throw 'Dedicated local backend did not become ready.' }
            $line = Read-LineBounded $backend $remaining
            if ($null -eq $line) { throw 'Dedicated local backend exited before readiness.' }
            $writer.WriteLine($line)
            $started = $line -match 'Started StogBackendApplication'
        }
    } finally { $writer.Dispose() }

    $http = [Net.Http.HttpClient]::new()
    try {
        $exchange = Invoke-Json $http '/api/auth/kakao/exchange' ((@{kakao_access_token=$credential} | ConvertTo-Json -Compress))
        if ([int]$exchange.StatusCode -ne 200) { throw "Fixture exchange failed with HTTP $([int]$exchange.StatusCode)." }
        $issued = $exchange.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
        $protected = Invoke-Protected $http $issued.access_token
        $logout = Invoke-Json $http '/api/auth/logout' ((@{refresh_token=$issued.refresh_token} | ConvertTo-Json -Compress))
        $denied = Invoke-Protected $http $issued.access_token
        $refreshDenied = Invoke-Json $http '/api/auth/refresh' ((@{refresh_token=$issued.refresh_token} | ConvertTo-Json -Compress))
        $result = [ordered]@{
            fixture = 'loopback HTTP fixture; not real Kakao'
            backend = 'dedicated local stog_backend_dev/stog_app via DPAPI launcher'
            exchangeStatus = [int]$exchange.StatusCode
            protectedMissingTripStatus = [int]$protected.StatusCode
            logoutStatus = [int]$logout.StatusCode
            protectedAfterLogoutStatus = [int]$denied.StatusCode
            refreshAfterLogoutStatus = [int]$refreshDenied.StatusCode
            tokenValuesRecorded = $false
            realKakao = 'NOT_RUN'
        }
        if ($result.protectedMissingTripStatus -ne 404 -or $result.logoutStatus -ne 204 -or
                $result.protectedAfterLogoutStatus -ne 401 -or $result.refreshAfterLogoutStatus -ne 401) {
            throw 'Loopback auth surface status contract failed.'
        }
        [IO.File]::WriteAllText($resultPath, (($result | ConvertTo-Json -Depth 3) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
        $exchange.Dispose(); $protected.Dispose(); $logout.Dispose(); $denied.Dispose(); $refreshDenied.Dispose()
    } finally { $http.Dispose() }
} finally {
    Restore-Environment
    $credential = $null
    foreach ($process in @($backend, $fixture)) {
        if ($null -ne $process -and -not $process.HasExited) {
            & taskkill.exe /PID $process.Id /T /F 2>&1 | Out-Null
            [void]$process.WaitForExit(10000)
        }
        if ($null -ne $process) { $process.Dispose() }
    }
}
exit 0
