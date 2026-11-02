[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# These resources are dedicated to local development. This script never targets the
# integration-test database and refuses to reuse or alter names that already exist.
$database = 'stog_backend_dev'
$role = 'stog_backend_app'
$schema = 'stog_app'
$credentialDirectory = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'STOG\local-backend'
$credentialPath = Join-Path $credentialDirectory 'stog_backend_app.password.clixml'
$roleCreated = $false
$databaseCreated = $false
$credentialAttempted = $false
$password = $null
$securePassword = $null
$oldPassword = [Environment]::GetEnvironmentVariable('STOG_DB_PASSWORD', 'Process')
$oldWslEnv = [Environment]::GetEnvironmentVariable('WSLENV', 'Process')
. (Join-Path $PSScriptRoot 'LocalPrivateDirectory.ps1')

function Invoke-AdminPsql([string]$TargetDatabase, [string]$Sql) {
    $Sql | & wsl.exe -u postgres -- psql -X -v ON_ERROR_STOP=1 -Atq -d $TargetDatabase -f -
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL administration failed for database $TargetDatabase." }
}

try {
    $collision = (& wsl.exe -u postgres -- psql -X -v ON_ERROR_STOP=1 -Atq -d postgres -c "SELECT (SELECT count(*) FROM pg_database WHERE datname='$database')::text||'|'||(SELECT count(*) FROM pg_roles WHERE rolname='$role')::text;").Trim()
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL collision check failed.' }
    if ($collision -ne '0|0') { throw "Refusing to alter collided local resources (database|role=$collision)." }
    if (Test-Path -LiteralPath $credentialPath) { throw "Refusing to overwrite existing protected credential at $credentialPath." }

    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    $password = (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
    [Array]::Clear($bytes, 0, $bytes.Length)

    [Environment]::SetEnvironmentVariable('STOG_DB_PASSWORD', $password, 'Process')
    $wslParts = @($oldWslEnv -split ':' | Where-Object { $_ -and ($_ -split '/')[0] -ne 'STOG_DB_PASSWORD' })
    [Environment]::SetEnvironmentVariable('WSLENV', (($wslParts + 'STOG_DB_PASSWORD') -join ':'), 'Process')

    Invoke-AdminPsql 'postgres' @'
\getenv app_password STOG_DB_PASSWORD
CREATE ROLE stog_backend_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
'@
    $roleCreated = $true

    & wsl.exe -u postgres -- createdb --owner=postgres --encoding=UTF8 --template=template0 $database
    if ($LASTEXITCODE -ne 0) { throw 'Dedicated local development database creation failed.' }
    $databaseCreated = $true

    Invoke-AdminPsql $database @'
REVOKE CONNECT, TEMPORARY ON DATABASE stog_backend_dev FROM PUBLIC;
GRANT CONNECT ON DATABASE stog_backend_dev TO stog_backend_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM stog_backend_app;
CREATE SCHEMA stog_app AUTHORIZATION stog_backend_app;
'@

    $credentialAttempted = $true
    Ensure-PrivateLocalDirectory $credentialDirectory
    $securePassword = ConvertTo-SecureString $password -AsPlainText -Force
    $securePassword | Export-Clixml -LiteralPath $credentialPath -Force
    Ensure-PrivateLocalDirectory $credentialDirectory

    Write-Output "Created isolated local database=$database role=$role schema=$schema."
    Write-Output "Stored a DPAPI-protected same-user credential at $credentialPath."
} catch {
    if ($databaseCreated) { & wsl.exe -u postgres -- dropdb --if-exists $database | Out-Null }
    if ($roleCreated) { Invoke-AdminPsql 'postgres' 'DROP ROLE IF EXISTS stog_backend_app;' | Out-Null }
    if ($credentialAttempted -and (Test-Path -LiteralPath $credentialPath)) { Remove-Item -LiteralPath $credentialPath -Force }
    throw
} finally {
    [Environment]::SetEnvironmentVariable('STOG_DB_PASSWORD', $oldPassword, 'Process')
    [Environment]::SetEnvironmentVariable('WSLENV', $oldWslEnv, 'Process')
    $password = $null
    $securePassword = $null
}
