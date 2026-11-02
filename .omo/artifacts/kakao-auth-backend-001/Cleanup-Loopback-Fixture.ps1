[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$credentialPath = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'STOG\local-backend\stog_backend_app.password.clixml'
$securePassword = Import-Clixml -LiteralPath $credentialPath
if ($securePassword -isnot [Security.SecureString]) { throw 'Protected credential has an invalid format.' }
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
$password = $null
$names = @('PGPASSWORD','WSLENV','STOG_QA_SQL')
$old = @{}
foreach ($name in $names) { $old[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $sql = @"
BEGIN;
SELECT user_id AS qa_user_id FROM stog_app.auth_identity WHERE provider='KAKAO' AND subject='991001' \gset
DELETE FROM stog_app.auth_session WHERE user_id=:'qa_user_id';
DELETE FROM stog_app.auth_login_exchange WHERE provider='KAKAO' AND subject='991001';
DELETE FROM stog_app.auth_identity WHERE provider='KAKAO' AND subject='991001';
DELETE FROM stog_app.stog_user u WHERE u.id=:'qa_user_id'
  AND NOT EXISTS (SELECT 1 FROM stog_app.trip_member m WHERE m.user_id=u.id)
  AND NOT EXISTS (SELECT 1 FROM stog_app.trip_item i WHERE i.created_by_user_id=u.id)
  AND NOT EXISTS (SELECT 1 FROM stog_app.request_execution e WHERE e.principal_user_id=u.id);
COMMIT;
SELECT (SELECT count(*) FROM stog_app.auth_identity WHERE provider='KAKAO' AND subject='991001')||'|'||
       (SELECT count(*) FROM stog_app.auth_login_exchange WHERE provider='KAKAO' AND subject='991001');
"@
    [Environment]::SetEnvironmentVariable('PGPASSWORD', $password, 'Process')
    [Environment]::SetEnvironmentVariable('STOG_QA_SQL', $sql, 'Process')
    $wsl = (($old['WSLENV'] -split ':' | Where-Object { $_ -and ($_ -split '/')[0] -notin @('PGPASSWORD','STOG_QA_SQL') }) + @('PGPASSWORD/u','STOG_QA_SQL/u')) -join ':'
    [Environment]::SetEnvironmentVariable('WSLENV', $wsl, 'Process')
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = 'wsl.exe'
    $psi.Arguments = '-- bash -lc "printf ''%s'' \"${STOG_QA_SQL}\" | psql -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -U stog_backend_app -d stog_backend_dev -Atq -f -"'
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($psi)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'Fixture cleanup timed out.' }
    if ($process.ExitCode -ne 0) {
        $safeError = $stderr.GetAwaiter().GetResult().Trim()
        throw "Fixture cleanup failed: $safeError"
    }
    $remaining = $stdout.GetAwaiter().GetResult().Trim()
    if ($remaining -ne '0|0') { throw "Fixture cleanup left unexpected rows: $remaining" }
    $result = [ordered]@{ fixtureSubjectRowsRemaining=0; fixtureExchangeRowsRemaining=0; durableDatabaseRetained=$true; credentialOutput='none' }
    [IO.File]::WriteAllText((Join-Path $PSScriptRoot 'fixture-cleanup-result.json'), (($result | ConvertTo-Json) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
} finally {
    $password = $null
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $old[$name], 'Process') }
}
