$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'LocalPrivateDirectory.ps1')
$icacls = Join-Path $env:SystemRoot 'System32\icacls.exe'
$cmd = Join-Path $env:SystemRoot 'System32\cmd.exe'

$root = Join-Path ([IO.Path]::GetTempPath()) ('stog-private-acl-' + [Guid]::NewGuid().ToString('N'))
$target = Join-Path ([IO.Path]::GetTempPath()) ('stog-private-target-' + [Guid]::NewGuid().ToString('N'))
try {
    [IO.Directory]::CreateDirectory($root) | Out-Null
    [IO.File]::WriteAllText((Join-Path $root 'preserved.txt'), 'preserved')
    & $icacls $root /grant '*S-1-1-0:(OI)(CI)R' /T /C /Q | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Test ACL setup failed.' }

    Ensure-PrivateLocalDirectory $root
    if ([IO.File]::ReadAllText((Join-Path $root 'preserved.txt')) -ne 'preserved') {
        throw 'Existing private-directory content was not preserved.'
    }

    [IO.Directory]::CreateDirectory($target) | Out-Null
    $junction = Join-Path $root 'junction'
    & $cmd /c "mklink /J `"$junction`" `"$target`"" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Test junction setup failed.' }
    $reparseRejected = $false
    try { Ensure-PrivateLocalDirectory $root } catch { $reparseRejected = $true }
    if (-not $reparseRejected) { throw 'Reparse-point directory was not rejected.' }
    Write-Output 'PASS'
} finally {
    if (Test-Path -LiteralPath (Join-Path $root 'junction')) {
        & $cmd /c "rmdir `"$((Join-Path $root 'junction'))`"" | Out-Null
    }
    if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
    if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force }
}
