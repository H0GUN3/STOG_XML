[CmdletBinding()]
param(
    [switch]$ConnectionOnly,
    [string[]]$Tests = @(),
    [switch]$SkipBootJar,
    [ValidatePattern('^[a-z0-9][a-z0-9/_-]*$')]
    [string]$ArtifactSubdirectory = 'm1-foundation-002'
)

$ErrorActionPreference = 'Stop'
$Db = 'stog_backend_test'
$ExpectedVersionNumber = '180006'
$Root = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$ArtifactDir = Join-Path $Root ('.omo/artifacts/' + $ArtifactSubdirectory)
$ResultPath = Join-Path $ArtifactDir 'test-runner-result.json'
$LogPath = Join-Path $ArtifactDir 'logs/postgres-runner-connection-only.log'
$started = (Get-Date).ToUniversalTime().ToString('o')
$role = $null
$schema = $null
$password = $null
$created = $false
$authenticated = $false
$cleanupOk = $false
$metadataUnchanged = $false
$remaining = $null
$gradleExit = $null
$errorText = $null
$cleanupError = $null
$ackRecovered = $false
$beforeMetadata = $null
$beforePrefixCount = $null
$afterPrefixCount = $null
$mediaRoot = $null
$mediaRootCleanupOk = $false

function Protect-Text([string]$Text) {
    if ($null -eq $Text) { return $null }
    if ($script:password) { return $Text.Replace($script:password, '[REDACTED]') }
    return $Text
}

function Add-WslEnvName([string]$Value, [string]$Name) {
    $parts = @($Value -split ':' | Where-Object { $_ -and ($_ -split '/')[0] -ne $Name })
    $parts += $Name
    return ($parts -join ':')
}

function Invoke-Psql([string]$Sql, [switch]$Admin, [switch]$Login, [int]$TimeoutSeconds = 30) {
    $names = @('STOG_PSQL_SQL', 'STOG_PSQL_ROLE', 'PGPASSWORD', 'PGCONNECT_TIMEOUT', 'WSLENV')
    $old = @{}
    foreach ($name in $names) { $old[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }

    $process = $null
    try {
        [Environment]::SetEnvironmentVariable('STOG_PSQL_SQL', $Sql, 'Process')
        [Environment]::SetEnvironmentVariable('STOG_PSQL_ROLE', $script:role, 'Process')
        [Environment]::SetEnvironmentVariable('PGCONNECT_TIMEOUT', '5', 'Process')
        if ($Login) { [Environment]::SetEnvironmentVariable('PGPASSWORD', $script:password, 'Process') }

        $wslEnv = $old['WSLENV']
        foreach ($name in @('STOG_PSQL_SQL', 'STOG_PSQL_ROLE', 'PGCONNECT_TIMEOUT')) { $wslEnv = Add-WslEnvName $wslEnv $name }
        if ($Login) { $wslEnv = Add-WslEnvName $wslEnv 'PGPASSWORD' }
        [Environment]::SetEnvironmentVariable('WSLENV', $wslEnv, 'Process')

        $psi = [Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = 'wsl.exe'
        $command = if ($Login) {
            'printf ''%s'' "${STOG_PSQL_SQL}" | psql -X -v ON_ERROR_STOP=1 -h localhost -U "${STOG_PSQL_ROLE}" -d stog_backend_test -Atq -f -'
        } else {
            'printf ''%s'' "${STOG_PSQL_SQL}" | psql -X -v ON_ERROR_STOP=1 -d stog_backend_test -Atq -f -'
        }
        $prefix = if ($Admin) { '-u postgres -- ' } else { '-- ' }
        $psi.Arguments = $prefix + 'bash -lc "' + ($command.Replace('"', '\"')) + '"'
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true

        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $psi
        if (-not $process.Start()) { throw 'Failed to start WSL psql process.' }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            try { $process.Kill() } catch {}
            [void]$process.WaitForExit(5000)
            throw "WSL psql timed out after $TimeoutSeconds seconds."
        }
        $process.WaitForExit()
        $exitCode = $process.ExitCode
        $out = (Protect-Text $stdoutTask.GetAwaiter().GetResult()).Trim()
        $err = (Protect-Text $stderrTask.GetAwaiter().GetResult()).Trim()
        if ($exitCode -ne 0) {
            if (-not $err) { $err = 'no stderr' }
            throw "psql failed with exit code ${exitCode}: $err"
        }
        return $out
    } finally {
        if ($process) { $process.Dispose() }
        foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $old[$name], 'Process') }
    }
}

function New-SecretHex([int]$ByteCount) {
    $bytes = [byte[]]::new($ByteCount)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
}

function Get-GeneratedState {
    return Invoke-Psql "SET statement_timeout='10s'; SET lock_timeout='5s'; SELECT (SELECT count(*) FROM pg_roles WHERE rolname='$script:role')||'|'||(SELECT count(*) FROM pg_namespace n JOIN pg_roles r ON r.oid=n.nspowner WHERE n.nspname='$script:schema' AND r.rolname='$script:role')||'|'||COALESCE((SELECT rolsuper||'|'||rolcreatedb||'|'||rolcreaterole||'|'||rolreplication||'|'||rolcanlogin FROM pg_roles WHERE rolname='$script:role'),'missing');" -Admin
}

function Get-MetadataFingerprint {
    $sql = @"
SET statement_timeout='10s'; SET lock_timeout='5s';
SELECT md5(COALESCE(string_agg(row_to_json(x)::text, E'\n' ORDER BY x.rolname),'')) FROM (SELECT * FROM pg_roles WHERE rolname <> '$role') x;
SELECT md5(COALESCE(string_agg(row_to_json(x)::text, E'\n' ORDER BY x.nspname),'')) FROM (SELECT n.nspname, pg_get_userbyid(n.nspowner) AS owner, n.nspacl::text AS acl FROM pg_namespace n WHERE n.nspname='public' OR n.nspname ~ '^task14_') x;
SELECT md5(row_to_json(x)::text) FROM (SELECT datname, datdba, datacl::text AS acl FROM pg_database WHERE datname='$Db') x;
"@
    return Invoke-Psql $sql -Admin
}

function Get-PrefixCount {
    return Invoke-Psql "SET statement_timeout='10s'; SELECT (SELECT count(*) FROM pg_roles WHERE rolname LIKE 'stog_m1_002_%')||'|'||(SELECT count(*) FROM pg_namespace WHERE nspname LIKE 'stog_m1_002_%');" -Admin
}

try {
    $identity = Invoke-Psql "SET statement_timeout='10s'; SELECT current_database()||'|'||current_setting('server_version_num');" -Admin
    if ($identity -ne "$Db|$ExpectedVersionNumber") { throw "Unexpected database identity/version: $identity" }

    $role = 'stog_m1_002_' + [Guid]::NewGuid().ToString('N')
    $schema = $role
    if ($role.Length -ge 63 -or $role -notmatch '^stog_m1_002_[0-9a-f]{32}$') { throw 'Generated identifier is unsafe.' }
    $password = New-SecretHex 32

    $absence = Invoke-Psql "SET statement_timeout='10s'; SELECT (SELECT count(*) FROM pg_roles WHERE rolname='$role')||'|'||(SELECT count(*) FROM pg_namespace WHERE nspname='$schema');" -Admin
    if ($absence -ne '0|0') { throw 'Generated role or schema already exists.' }
    $beforeMetadata = Get-MetadataFingerprint
    $beforePrefixCount = Get-PrefixCount

    $provisionSql = @"
SET statement_timeout='10s'; SET lock_timeout='5s'; SET log_statement='none'; SET log_min_error_statement='panic';
BEGIN;
CREATE ROLE "$role" LOGIN PASSWORD '$password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE SCHEMA "$schema" AUTHORIZATION "$role";
GRANT USAGE, CREATE ON SCHEMA "$schema" TO "$role";
GRANT CONNECT ON DATABASE "$Db" TO "$role";
COMMIT;
"@
    $provisionFailure = $null
    try { Invoke-Psql $provisionSql -Admin | Out-Null } catch { $provisionFailure = Protect-Text $_.Exception.Message }
    $state = Get-GeneratedState
    if ($state -eq '1|1|false|false|false|false|true') {
        $created = $true
        $ackRecovered = ($null -ne $provisionFailure)
    } elseif ($provisionFailure) {
        throw $provisionFailure
    } else {
        throw "Provisioned fixture state is invalid: $state"
    }

    $mediaRoot = Join-Path ([IO.Path]::GetTempPath()) ('stog-r2b-media-' + [Guid]::NewGuid().ToString('N'))
    [IO.Directory]::CreateDirectory($mediaRoot) | Out-Null
    $envNames = @('STOG_TEST_JDBC_URL', 'STOG_TEST_DB_USERNAME', 'STOG_TEST_DB_PASSWORD', 'STOG_TEST_DB_NAME', 'STOG_TEST_DB_SCHEMA', 'STOG_TEST_DB_ROLE', 'STOG_MEDIA_ROOT')
    $envBackup = @{}
    foreach ($name in $envNames) { $envBackup[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
    try {
        [Environment]::SetEnvironmentVariable('STOG_TEST_JDBC_URL', "jdbc:postgresql://localhost:5432/$Db`?currentSchema=$schema", 'Process')
        [Environment]::SetEnvironmentVariable('STOG_TEST_DB_USERNAME', $role, 'Process')
        [Environment]::SetEnvironmentVariable('STOG_TEST_DB_PASSWORD', $password, 'Process')
        [Environment]::SetEnvironmentVariable('STOG_TEST_DB_NAME', $Db, 'Process')
        [Environment]::SetEnvironmentVariable('STOG_TEST_DB_SCHEMA', $schema, 'Process')
        [Environment]::SetEnvironmentVariable('STOG_TEST_DB_ROLE', $role, 'Process')
        [Environment]::SetEnvironmentVariable('STOG_MEDIA_ROOT', $mediaRoot, 'Process')

        $loginSql = "SET statement_timeout='10s'; SET lock_timeout='5s'; SET search_path TO `"$schema`"; SELECT current_database()||'|'||current_user||'|'||current_schema()||'|'||(SELECT rolsuper FROM pg_roles WHERE rolname=current_user); CREATE TABLE `"$schema`".runner_probe(id integer); DROP TABLE `"$schema`".runner_probe;"
        $who = Invoke-Psql $loginSql -Login
        if ($who -ne "$Db|$role|$schema|false") { throw "TCP login identity mismatch: $who" }
        $authenticated = $true

        if (-not $ConnectionOnly) {
            $jdk = 'C:\Program Files\Java\jdk-17'
            if (-not (Test-Path (Join-Path $jdk 'bin/java.exe'))) { throw "JDK 17 not found at $jdk" }
            $oldJavaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
            $oldPath = [Environment]::GetEnvironmentVariable('PATH', 'Process')
            try {
                [Environment]::SetEnvironmentVariable('JAVA_HOME', $jdk, 'Process')
                [Environment]::SetEnvironmentVariable('PATH', (Join-Path $jdk 'bin') + ';' + $oldPath, 'Process')
                Push-Location $Root
                try {
                    $gradleArguments = @('-p', 'backend', '--no-daemon', 'test')
                    foreach ($test in $Tests) { $gradleArguments += @('--tests', $test) }
                    if (-not $SkipBootJar) { $gradleArguments += 'bootJar' }
                    $gradleArguments += '--rerun-tasks'
                    & (Join-Path $Root 'gradlew.bat') @gradleArguments
                    $gradleExit = $LASTEXITCODE
                } finally { Pop-Location }
            } finally {
                [Environment]::SetEnvironmentVariable('JAVA_HOME', $oldJavaHome, 'Process')
                [Environment]::SetEnvironmentVariable('PATH', $oldPath, 'Process')
            }
        }
    } finally {
        foreach ($name in $envNames) { [Environment]::SetEnvironmentVariable($name, $envBackup[$name], 'Process') }
        if ($mediaRoot -and (Test-Path -LiteralPath $mediaRoot)) {
            Remove-Item -LiteralPath $mediaRoot -Recurse -Force
            $mediaRootCleanupOk = -not (Test-Path -LiteralPath $mediaRoot)
        }
    }
} catch {
    $errorText = Protect-Text $_.Exception.Message
} finally {
    if ($created) {
        try {
            $state = Get-GeneratedState
            if ($state -ne '1|1|false|false|false|false|true') { throw "Cleanup ownership/role assertion failed: $state" }
            $cleanupSql = @"
SET statement_timeout='10s'; SET lock_timeout='5s'; SET log_statement='none'; SET log_min_error_statement='panic';
BEGIN;
DROP SCHEMA "$schema" CASCADE;
REVOKE CONNECT ON DATABASE "$Db" FROM "$role";
DROP ROLE "$role";
COMMIT;
"@
            $cleanupCommandError = $null
            try { Invoke-Psql $cleanupSql -Admin | Out-Null } catch { $cleanupCommandError = Protect-Text $_.Exception.Message }
            $remaining = Invoke-Psql "SET statement_timeout='10s'; SELECT (SELECT count(*) FROM pg_roles WHERE rolname='$role')||'|'||(SELECT count(*) FROM pg_namespace WHERE nspname='$schema');" -Admin
            if ($remaining -ne '0|0') {
                if ($cleanupCommandError) { throw "$cleanupCommandError; invocation resources remaining: $remaining" }
                throw "Invocation resources remaining: $remaining"
            }
            $afterPrefixCount = Get-PrefixCount
            $afterMetadata = Get-MetadataFingerprint
            $metadataUnchanged = ($beforeMetadata -eq $afterMetadata -and $beforePrefixCount -eq $afterPrefixCount)
            if (-not $metadataUnchanged) { throw 'Preexisting role/schema/database metadata changed.' }
            $cleanupOk = $true
        } catch {
            $cleanupError = Protect-Text $_.Exception.Message
        }
    }

    $result = [ordered]@{
        startedUtc = $started
        finishedUtc = (Get-Date).ToUniversalTime().ToString('o')
        database = $Db
        serverVersionNumber = $ExpectedVersionNumber
        connectionOnly = [bool]$ConnectionOnly
        focusedTests = @($Tests)
        bootJarSkipped = [bool]$SkipBootJar
        fixtureRole = $role
        generatedFixture = $created
        provisionAcknowledgementRecovered = $ackRecovered
        authenticatedNonSuperuser = $authenticated
        ownSchemaAccessVerified = $authenticated
        windowsJdbcVerification = $(if ($ConnectionOnly) { 'not-run-connection-only' } elseif ($null -eq $gradleExit) { 'not-run' } else { 'run-by-gradle' })
        cleanupSucceeded = $cleanupOk
        invocationResourcesRemaining = $remaining
        preexistingMetadataUnchanged = $metadataUnchanged
        preexistingTaskPrefixCountsBefore = $beforePrefixCount
        preexistingTaskPrefixCountsAfter = $afterPrefixCount
        gradleExit = $gradleExit
        error = $errorText
        cleanupError = $cleanupError
        passwordOutput = 'none'
        generatedMediaRoot = ($null -ne $mediaRoot)
        mediaRootCleanupSucceeded = $mediaRootCleanupOk
        sanitizedLog = ('.omo/artifacts/' + $ArtifactSubdirectory + '/logs/postgres-runner-connection-only.log')
    }
    New-Item -ItemType Directory -Force (Split-Path $LogPath) | Out-Null
    $json = $result | ConvertTo-Json -Depth 4
    [IO.File]::WriteAllText($ResultPath, $json + [Environment]::NewLine, [Text.UTF8Encoding]::new($false))
    $logRecord = [ordered]@{ finishedUtc=$result.finishedUtc; fixtureRole=$role; authenticatedNonSuperuser=$authenticated; cleanupSucceeded=$cleanupOk; invocationResourcesRemaining=$remaining; preexistingMetadataUnchanged=$metadataUnchanged; connectionOnly=[bool]$ConnectionOnly; error=$errorText; cleanupError=$cleanupError }
    [IO.File]::AppendAllText($LogPath, (($logRecord | ConvertTo-Json -Compress) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
}

if ($gradleExit -ne $null -and $gradleExit -ne 0) { exit $gradleExit }
if ($errorText -or -not $authenticated -or -not $cleanupOk) { exit 1 }
exit 0
