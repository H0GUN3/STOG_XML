$ErrorActionPreference = 'Stop'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$emulator = Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'
$artifact = $PSScriptRoot
$logs = Join-Path $artifact 'logs'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$devices = & $adb devices
$devices | Set-Content -Encoding utf8 (Join-Path $logs 'revalidation-devices-before.log')
if (@($devices | Where-Object { $_ -match '^emulator-\d+\s+device' }).Count -ne 0) {
    throw 'Pre-existing emulator attached; refusing to touch it.'
}
if (Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue) {
    throw 'Pre-existing 8080 listener; refusing gate setup.'
}
$owned = Start-Process -FilePath $emulator -ArgumentList '-avd Pixel_8 -port 5562 -no-window -no-audio -no-boot-anim' -NoNewWindow -RedirectStandardOutput (Join-Path $logs 'revalidation-emulator.stdout.log') -RedirectStandardError (Join-Path $logs 'revalidation-emulator.stderr.log') -PassThru
$owned.Id | Set-Content (Join-Path $artifact 'revalidation-emulator.pid')
try {
    $wait = Start-Process -FilePath $adb -ArgumentList '-s emulator-5562 wait-for-device' -PassThru -NoNewWindow
    if (-not $wait.WaitForExit(120000)) {
        Stop-Process -Id $wait.Id -Force
        throw 'adb device readiness timeout'
    }
    $bootLog = Join-Path $logs 'revalidation-boot.log'
    $boot = Start-Process -FilePath $adb -ArgumentList '-s emulator-5562 logcat -b events -s boot_progress_enable_screen:I' -RedirectStandardOutput $bootLog -PassThru -NoNewWindow
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    do {
        if ((Test-Path $bootLog) -and (Get-Content -Raw $bootLog) -match 'boot_progress_enable_screen') { break }
        if ($boot.HasExited) { throw 'boot event monitor exited' }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    if ([DateTime]::UtcNow -ge $deadline) { throw 'boot event timeout' }
    Stop-Process -Id $boot.Id -Force -ErrorAction SilentlyContinue
    & $adb -s emulator-5562 shell cmd activity wait-for-broadcast-idle | Out-Null

    $env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
    Push-Location (Resolve-Path (Join-Path $artifact '../../..'))
    try {
        & .\gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.MissingConfigurationUiTest,com.stog.app.auth.AuthApiClientSecurityTest,com.stog.app.auth.AuthApiClientTest,com.stog.app.auth.AuthApiClientTripItemTest,com.stog.app.auth.ConfiguredAuthUiTest,com.stog.app.auth.SecureRefreshStoreTest,com.stog.app.trip.TripApiClientTest,com.stog.app.auth.TripFlowUiTest' *>&1 | Tee-Object -FilePath (Join-Path $logs 'android-exact-gate-revalidation.log')
        $gateExit = $LASTEXITCODE
    } finally { Pop-Location }
    if ($gateExit -ne 0) { throw "Canonical Android gate failed with exit $gateExit" }
} finally {
    & $adb -s emulator-5562 uninstall com.stog.app.test 2>$null | Out-Null
    & $adb -s emulator-5562 uninstall com.stog.app 2>$null | Out-Null
    & $adb -s emulator-5562 reverse --remove-all 2>$null | Out-Null
    & $adb -s emulator-5562 emu kill 2>$null | Out-Null
    if (-not $owned.WaitForExit(15000)) { Stop-Process -Id $owned.Id -Force -ErrorAction SilentlyContinue }
    & $adb -s emulator-5562 wait-for-disconnect 2>$null
    $post = [ordered]@{
        emulatorProcessAbsent = $null -eq (Get-Process -Id $owned.Id -ErrorAction SilentlyContinue)
        emulatorDeviceAbsent = -not ((& $adb devices) -match '^emulator-5562\s+device')
        listener8080Absent = @((Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)).Count -eq 0
    }
    $post | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $artifact 'android-revalidation-cleanup.json')
}
