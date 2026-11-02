[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$operatorDirectory = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'STOG\local-backend'
$credentialPath = Join-Path $operatorDirectory 'stog_backend_app.password.clixml'
$restrictedOwnerPath = Join-Path $operatorDirectory 'restricted-owner-user-id.clixml'
$oldPassword = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'Process')
$oldWslEnv = [Environment]::GetEnvironmentVariable('WSLENV', 'Process')
$passwordPointer = [IntPtr]::Zero
$plainPassword = $null
$ownerUserId = $null

if (-not (Test-Path -LiteralPath $credentialPath -PathType Leaf)) {
    throw "Protected local backend credential is missing at $credentialPath. Run Initialize-LocalBackend.ps1 once."
}
if (Test-Path -LiteralPath $restrictedOwnerPath) {
    throw "Refusing to overwrite existing protected restricted-owner setting at $restrictedOwnerPath."
}

try {
    $securePassword = Import-Clixml -LiteralPath $credentialPath
    if ($securePassword -isnot [Security.SecureString]) { throw 'Protected credential has an invalid format.' }
    $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
    $plainPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
    [Environment]::SetEnvironmentVariable('PGPASSWORD', $plainPassword, 'Process')
    $wslParts = @($oldWslEnv -split ':' | Where-Object { $_ -and ($_ -split '/')[0] -ne 'PGPASSWORD' })
    [Environment]::SetEnvironmentVariable('WSLENV', (($wslParts + 'PGPASSWORD') -join ':'), 'Process')

    $sql = @'
WITH mapping AS (
  SELECT i.user_id
  FROM stog_app.auth_identity i
  JOIN stog_app.stog_user u ON u.id = i.user_id
  WHERE i.provider = 'KAKAO'
), facts AS (
  SELECT
    (SELECT count(*) FROM stog_app.auth_identity WHERE provider = 'KAKAO') AS identity_count,
    (SELECT count(*) FROM mapping) AS valid_mapping_count,
    (SELECT count(DISTINCT user_id) FROM mapping) AS user_count,
    (SELECT count(*) FROM stog_app.auth_session s JOIN mapping m ON m.user_id = s.user_id) AS session_count
)
SELECT identity_count::text || '|' || valid_mapping_count::text || '|' || user_count::text || '|' || session_count::text || '|' ||
       CASE WHEN identity_count = 1 AND valid_mapping_count = 1 AND user_count = 1
            THEN (SELECT user_id::text FROM mapping)
            ELSE '' END
FROM facts;
'@
    $raw = @($sql | wsl.exe -- psql -X -v ON_ERROR_STOP=1 -Atq -h 127.0.0.1 -U stog_backend_app -d stog_backend_dev -f -)
    if ($LASTEXITCODE -ne 0 -or $raw.Count -ne 1) { throw 'Read-only restricted-owner mapping check failed.' }
    $parts = $raw[0] -split '\|', 5
    if ($parts.Count -ne 5 -or $parts[0] -ne '1' -or $parts[1] -ne '1' -or $parts[2] -ne '1' -or [int]$parts[3] -lt 1) {
        throw 'Restricted-owner mapping is absent or ambiguous; no setting was written.'
    }
    $ownerUserId = $parts[4]
    if ($ownerUserId -cnotmatch '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$') {
        throw 'Restricted-owner mapping did not yield a canonical internal user identifier.'
    }

    New-Item -ItemType Directory -Force -Path $operatorDirectory | Out-Null
    ConvertTo-SecureString $ownerUserId -AsPlainText -Force | Export-Clixml -LiteralPath $restrictedOwnerPath
    Write-Output 'Pinned one unambiguous existing Kakao identity mapping in a same-user protected restricted-owner setting.'
} finally {
    [Environment]::SetEnvironmentVariable('PGPASSWORD', $oldPassword, 'Process')
    [Environment]::SetEnvironmentVariable('WSLENV', $oldWslEnv, 'Process')
    $plainPassword = $null
    $ownerUserId = $null
    if ($passwordPointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer) }
}
