[CmdletBinding()]
param(
    [switch]$SimulateProvisionAcknowledgementLoss
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$database = 'stog_backend_test'
$role = 'stog_m1_002_' + [Guid]::NewGuid().ToString('N')
$schema = $role
$password = $null
$created = $false
$provisionAcknowledgementRecovered = $false
$proofSucceeded = $false
$cleanupSucceeded = $false
$beforeCount = $null
$afterCount = $null
$beforeFingerprint = $null
$afterFingerprint = $null
$beforePublicAcl = $null
$afterPublicAcl = $null
$remaining = $null
$errorText = $null
$cleanupError = $null

if ($role -notmatch '^stog_m1_002_[0-9a-f]{32}$' -or $role.Length -ge 63) {
    throw 'Generated capability-proof identifier is unsafe.'
}

function Add-WslEnvName([string]$Value, [string]$Name) {
    $parts = @($Value -split ':' | Where-Object { $_ -and ($_ -split '/')[0] -ne $Name })
    return (($parts + $Name) -join ':')
}

function Invoke-ProofPsql([string]$Sql, [switch]$Login) {
    $names = @('STOG_PROOF_SQL', 'STOG_PROOF_ROLE', 'PGPASSWORD', 'PGCONNECT_TIMEOUT', 'WSLENV')
    $old = @{}
    foreach ($name in $names) {
        $old[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }

    try {
        [Environment]::SetEnvironmentVariable('STOG_PROOF_SQL', $Sql, 'Process')
        [Environment]::SetEnvironmentVariable('STOG_PROOF_ROLE', $script:role, 'Process')
        [Environment]::SetEnvironmentVariable('PGCONNECT_TIMEOUT', '5', 'Process')
        if ($Login) {
            [Environment]::SetEnvironmentVariable('PGPASSWORD', $script:password, 'Process')
        }

        $wslEnv = $old['WSLENV']
        foreach ($name in @('STOG_PROOF_SQL', 'STOG_PROOF_ROLE', 'PGCONNECT_TIMEOUT')) {
            $wslEnv = Add-WslEnvName $wslEnv $name
        }
        if ($Login) {
            $wslEnv = Add-WslEnvName $wslEnv 'PGPASSWORD'
        }
        [Environment]::SetEnvironmentVariable('WSLENV', $wslEnv, 'Process')

        $command = if ($Login) {
            'printf ''%s'' "${STOG_PROOF_SQL}" | psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U "${STOG_PROOF_ROLE}" -d stog_backend_test -Atq -f -'
        } else {
            'printf ''%s'' "${STOG_PROOF_SQL}" | psql -X -v ON_ERROR_STOP=1 -d stog_backend_test -Atq -f -'
        }
        $psi = [Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = 'wsl.exe'
        $prefix = if ($Login) { '-- ' } else { '-u postgres -- ' }
        $psi.Arguments = $prefix + 'bash -lc "' + ($command.Replace('"', '\"')) + '"'
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true

        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $psi
        try {
            if (-not $process.Start()) {
                throw 'Failed to start capability-proof psql process.'
            }
            $stdout = $process.StandardOutput.ReadToEnd()
            $stderr = $process.StandardError.ReadToEnd()
            $process.WaitForExit()
            if ($process.ExitCode -ne 0) {
                throw 'Capability-proof psql command failed.'
            }
            return $stdout.Trim()
        } finally {
            $process.Dispose()
        }
    } finally {
        foreach ($name in $names) {
            [Environment]::SetEnvironmentVariable($name, $old[$name], 'Process')
        }
    }
}

function Get-ResidueCount {
    return (Invoke-ProofPsql "SELECT count(*) FROM pg_roles WHERE rolname LIKE 'stog_m1_002_%';")
}

function Get-ResidueFingerprint {
    return (Invoke-ProofPsql @"
SELECT md5(COALESCE(string_agg(
    rolname || '|' || rolcanlogin || '|' || rolsuper || '|' ||
    rolcreatedb || '|' || rolcreaterole || '|' || rolreplication,
    E'\n' ORDER BY rolname
), ''))
FROM pg_roles
WHERE rolname LIKE 'stog_m1_002_%';
"@)
}

function Get-PublicAcl {
    return (Invoke-ProofPsql "SELECT COALESCE(nspacl::text, 'NULL') FROM pg_namespace WHERE nspname='public';")
}

function Get-GeneratedState {
    return (Invoke-ProofPsql @"
SET statement_timeout='10s';
SET lock_timeout='5s';
SELECT
    (SELECT count(*) FROM pg_roles WHERE rolname='$role') || '|' ||
    (SELECT count(*)
     FROM pg_namespace n
     JOIN pg_roles r ON r.oid=n.nspowner
     WHERE n.nspname='$schema' AND r.rolname='$role') || '|' ||
    COALESCE((
        SELECT rolsuper || '|' || rolcreatedb || '|' || rolcreaterole || '|' ||
               rolreplication || '|' || rolcanlogin
        FROM pg_roles
        WHERE rolname='$role'
    ), 'missing');
"@)
}

try {
    $beforeCount = Get-ResidueCount
    $beforeFingerprint = Get-ResidueFingerprint
    $beforePublicAcl = Get-PublicAcl
    $absence = Invoke-ProofPsql @"
SET statement_timeout='10s';
SELECT
    (SELECT count(*) FROM pg_roles WHERE rolname='$role') || '|' ||
    (SELECT count(*) FROM pg_namespace WHERE nspname='$schema');
"@
    if ($absence -ne '0|0') {
        throw 'Generated capability-proof role or schema already exists.'
    }

    $bytes = [byte[]]::new(32)
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    $password = (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
    [Array]::Clear($bytes, 0, $bytes.Length)

$provisionSql = @"
SET statement_timeout='10s';
SET lock_timeout='5s';
SET log_statement='none';
SET log_min_error_statement='panic';
BEGIN;
CREATE ROLE "$role" LOGIN PASSWORD '$password'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE SCHEMA "$schema" AUTHORIZATION "$role";
GRANT USAGE, CREATE ON SCHEMA "$schema" TO "$role";
GRANT CONNECT ON DATABASE $database TO "$role";
GRANT USAGE ON SCHEMA public TO "$role";
COMMIT;
"@
    $provisionFailure = $null
    try {
        Invoke-ProofPsql $provisionSql | Out-Null
        if ($SimulateProvisionAcknowledgementLoss) {
            throw 'Simulated post-commit provisioning acknowledgement loss.'
        }
    } catch {
        $provisionFailure = $_.Exception.Message
    } finally {
        $provisionSql = $null
    }

    $state = Get-GeneratedState
    if ($state -eq '1|1|false|false|false|false|true') {
        $created = $true
        $provisionAcknowledgementRecovered = ($null -ne $provisionFailure)
    } elseif ($provisionFailure) {
        throw $provisionFailure
    } else {
        throw "Provisioned capability-proof state is invalid: $state"
    }

    $proof = Invoke-ProofPsql @"
SET search_path TO "$schema";
SELECT 'login_non_super|' ||
       (current_user = '$role') || '|' ||
       (SELECT NOT rolsuper AND NOT rolcreatedb AND NOT rolcreaterole AND NOT rolreplication
        FROM pg_roles WHERE rolname=current_user);
SELECT 'public_usage|' || has_schema_privilege(current_user, 'public', 'USAGE') ||
       '|public_create=' || has_schema_privilege(current_user, 'public', 'CREATE');
SELECT 'similarity|' || (public.similarity('stog', 'stog') = 1.0);
CREATE TABLE capability_probe(normalized_name text);
CREATE INDEX capability_probe_trgm_idx
    ON capability_probe USING gin (normalized_name public.gin_trgm_ops)
    WHERE normalized_name IS NOT NULL;
SELECT 'gin_index|' || (
    SELECT count(*) = 1
    FROM pg_class c
    JOIN pg_namespace n ON n.oid=c.relnamespace
    WHERE n.nspname='$schema' AND c.relname='capability_probe_trgm_idx'
);
DROP TABLE capability_probe;
"@ -Login

    if ($proof -notmatch 'login_non_super\|true\|true' -or
        $proof -notmatch 'public_usage\|true\|public_create=false' -or
        $proof -notmatch 'similarity\|true' -or
        $proof -notmatch 'gin_index\|true') {
        throw 'Capability proof returned an unexpected result.'
    }
    $proofSucceeded = $true
} catch {
    $errorText = $_.Exception.Message
} finally {
    if ($created) {
        try {
            Invoke-ProofPsql @"
BEGIN;
REVOKE USAGE ON SCHEMA public FROM "$role";
DROP SCHEMA "$schema" CASCADE;
REVOKE CONNECT ON DATABASE $database FROM "$role";
DROP ROLE "$role";
COMMIT;
"@ | Out-Null
            $remaining = Invoke-ProofPsql @"
SELECT
    (SELECT count(*) FROM pg_roles WHERE rolname='$role') || '|' ||
    (SELECT count(*) FROM pg_namespace WHERE nspname='$schema');
"@
            if ($remaining -ne '0|0') {
                throw 'Owned capability-proof resources remain.'
            }
            $cleanupSucceeded = $true
        } catch {
            $cleanupError = $_.Exception.Message
        }
    }
    $password = $null
}

$afterCount = Get-ResidueCount
$afterFingerprint = Get-ResidueFingerprint
$afterPublicAcl = Get-PublicAcl

[ordered]@{
    database = $database
    generatedRolePatternValid = $true
    simulatedProvisionAcknowledgementLoss = [bool]$SimulateProvisionAcknowledgementLoss
    provisionAcknowledgementRecovered = $provisionAcknowledgementRecovered
    capabilityProofSucceeded = $proofSucceeded
    loginNonSuperuser = $proofSucceeded
    temporaryPublicUsageOnly = $proofSucceeded
    publicCreateDenied = $proofSucceeded
    qualifiedSimilaritySucceeded = $proofSucceeded
    qualifiedGinOperatorClassSucceeded = $proofSucceeded
    cleanupSucceeded = $cleanupSucceeded
    ownedResourcesRemaining = $remaining
    existingResidueCountBefore = $beforeCount
    existingResidueCountAfter = $afterCount
    existingResidueFingerprintUnchanged = ($beforeFingerprint -eq $afterFingerprint)
    publicAclUnchanged = ($beforePublicAcl -eq $afterPublicAcl)
    credentialOutput = 'none'
    generatedRoleOutput = 'none'
    error = $errorText
    cleanupError = $cleanupError
} | ConvertTo-Json -Depth 3

if (-not $proofSucceeded -or -not $cleanupSucceeded -or
    $beforeFingerprint -ne $afterFingerprint -or $beforePublicAcl -ne $afterPublicAcl) {
    exit 1
}
