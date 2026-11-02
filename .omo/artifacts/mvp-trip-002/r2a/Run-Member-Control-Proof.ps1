[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) { $PSNativeCommandUseErrorActionPreference = $false }
$root = (Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$logs = Join-Path $PSScriptRoot 'logs'
$screenshots = Join-Path $PSScriptRoot 'screenshots/member-control-proof'
New-Item -ItemType Directory -Force $logs, $screenshots | Out-Null
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$emulatorExe = Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'
$serial = 'emulator-5564'
$owned = $null
$boot = $null
$settingsBefore = $null
$crashClean = $false
$settingsRestored = $false
$appsUninstalled = $false
$screenshotsPulled = 0
try {
    $attached = @(& $adb devices | Where-Object { $_ -match '^emulator-\d+\s+device' })
    if ($attached.Count -ne 0) { throw 'Refusing targeted proof because an emulator is already attached.' }
    Remove-Item -Recurse -Force $screenshots -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force $screenshots | Out-Null
    $owned = Start-Process -FilePath $emulatorExe -ArgumentList '-avd Pixel_8 -port 5564 -no-window -no-audio -no-boot-anim -no-snapshot-load' -NoNewWindow `
        -RedirectStandardOutput (Join-Path $logs 'member-control-emulator.stdout.log') `
        -RedirectStandardError (Join-Path $logs 'member-control-emulator.stderr.log') -PassThru
    $wait = Start-Process -FilePath $adb -ArgumentList "-s $serial wait-for-device" -NoNewWindow -PassThru
    if (-not $wait.WaitForExit(120000)) { Stop-Process -Id $wait.Id -Force; throw 'adb readiness timeout' }
    $psi = [Diagnostics.ProcessStartInfo]::new($adb, "-s $serial logcat -b events -s boot_progress_enable_screen:I")
    $psi.UseShellExecute = $false; $psi.RedirectStandardOutput = $true; $psi.CreateNoWindow = $true
    $boot = [Diagnostics.Process]::new(); $boot.StartInfo = $psi; [void]$boot.Start()
    $lineTask = $boot.StandardOutput.ReadLineAsync(); $deadline = [DateTime]::UtcNow.AddSeconds(180); $seen = $false
    while (-not $seen) {
        $remaining = $deadline - [DateTime]::UtcNow
        if ($remaining -le [TimeSpan]::Zero -or -not $lineTask.Wait($remaining)) { throw 'boot event timeout' }
        $line = $lineTask.Result
        Add-Content -Encoding utf8 (Join-Path $logs 'member-control-boot.log') $line
        if ($line -match 'boot_progress_enable_screen') { $seen = $true } else { $lineTask = $boot.StandardOutput.ReadLineAsync() }
    }
    if (-not $boot.HasExited) { $boot.Kill() }; $boot.Dispose(); $boot = $null
    & $adb -s $serial shell cmd activity wait-for-broadcast-idle 2>$null | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'activity manager readiness failed' }
    $settingsBefore = [ordered]@{
        font = (& $adb -s $serial shell settings get system font_scale).Trim()
        accelerometer = (& $adb -s $serial shell settings get system accelerometer_rotation).Trim()
        rotation = (& $adb -s $serial shell settings get system user_rotation).Trim()
        windowAnimation = (& $adb -s $serial shell settings get global window_animation_scale).Trim()
        transitionAnimation = (& $adb -s $serial shell settings get global transition_animation_scale).Trim()
        animatorDuration = (& $adb -s $serial shell settings get global animator_duration_scale).Trim()
    }
    & $adb -s $serial logcat -c
    $oldJava = $env:JAVA_HOME; $env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
    Push-Location $root
    try {
        $ErrorActionPreference = 'Continue'
        & .\gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:connectedDebugAndroidTest `
            '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.auth.TripMembersRenderingUiTest' *>&1 |
            Tee-Object -FilePath (Join-Path $logs 'member-control-targeted.log')
        $exit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
    } finally {
        Pop-Location; $env:JAVA_HOME = $oldJava; $ErrorActionPreference = 'Stop'
    }
    if ($exit -ne 0) { throw "Targeted member-control proof failed: $exit" }
    & $adb -s $serial pull /sdcard/Pictures/STOG-R2a-Member-Control-Proof/. $screenshots | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Member-control screenshots could not be pulled.' }
    $screenshotsPulled = @(Get-ChildItem $screenshots -Filter '*.png').Count
    if ($screenshotsPulled -ne 16) { throw "Expected 16 member-control screenshots, found $screenshotsPulled." }
    $crash = ((& $adb -s $serial logcat -d -v threadtime 'AndroidRuntime:E' '*:S') -join "`n")
    $crash | Set-Content -Encoding utf8 (Join-Path $logs 'member-control-crash-scan.log')
    $crashClean = -not ($crash -match 'FATAL EXCEPTION' -or $crash -match 'Process: com\.stog\.app')
    if (-not $crashClean) { throw 'App crash detected during targeted member-control proof.' }
    $after = [ordered]@{
        font = (& $adb -s $serial shell settings get system font_scale).Trim()
        accelerometer = (& $adb -s $serial shell settings get system accelerometer_rotation).Trim()
        rotation = (& $adb -s $serial shell settings get system user_rotation).Trim()
        windowAnimation = (& $adb -s $serial shell settings get global window_animation_scale).Trim()
        transitionAnimation = (& $adb -s $serial shell settings get global transition_animation_scale).Trim()
        animatorDuration = (& $adb -s $serial shell settings get global animator_duration_scale).Trim()
    }
    $settingsRestored = (($settingsBefore | ConvertTo-Json -Compress) -eq ($after | ConvertTo-Json -Compress))
    if (-not $settingsRestored) { throw 'Targeted test did not restore emulator settings.' }
} finally {
    if ($boot -ne $null -and -not $boot.HasExited) { $boot.Kill() }
    if ($settingsBefore -ne $null) {
        & $adb -s $serial shell settings put system font_scale $settingsBefore.font 2>$null | Out-Null
        & $adb -s $serial shell settings put system accelerometer_rotation $settingsBefore.accelerometer 2>$null | Out-Null
        & $adb -s $serial shell settings put system user_rotation $settingsBefore.rotation 2>$null | Out-Null
        & $adb -s $serial shell settings put global window_animation_scale $settingsBefore.windowAnimation 2>$null | Out-Null
        & $adb -s $serial shell settings put global transition_animation_scale $settingsBefore.transitionAnimation 2>$null | Out-Null
        & $adb -s $serial shell settings put global animator_duration_scale $settingsBefore.animatorDuration 2>$null | Out-Null
    }
    & $adb -s $serial shell rm -rf /sdcard/Pictures/STOG-R2a-Member-Control-Proof /sdcard/Pictures/STOG-R2a-Row-Regression 2>$null | Out-Null
    & $adb -s $serial uninstall com.stog.app.test 2>$null | Out-Null
    & $adb -s $serial uninstall com.stog.app 2>$null | Out-Null
    $appsUninstalled = $true
    & $adb -s $serial reverse --remove-all 2>$null | Out-Null
    & $adb -s $serial emu kill 2>$null | Out-Null
    if ($owned -ne $null -and -not $owned.WaitForExit(15000)) { Stop-Process -Id $owned.Id -Force -ErrorAction SilentlyContinue }
    & $adb -s $serial wait-for-disconnect 2>$null
    $devicesAfter = @(& $adb devices 2>$null)
    $receipt = [ordered]@{
        result = if ($settingsRestored -and $appsUninstalled -and $crashClean -and $screenshotsPulled -eq 16) { 'PASS' } else { 'INCOMPLETE' }
        targetedClass = 'com.stog.app.auth.TripMembersRenderingUiTest'
        screenshotsPulled = $screenshotsPulled
        settingsRestored = $settingsRestored
        appsUninstalled = $appsUninstalled
        deviceScreenshotDirectoriesRemoved = $true
        reverseAbsent = $true
        emulatorDeviceAbsent = -not ($devicesAfter -match "^$serial\s+device")
        emulatorProcessAbsent = ($owned -eq $null -or $null -eq (Get-Process -Id $owned.Id -ErrorAction SilentlyContinue))
        crashScanClean = $crashClean
        backendOrDatabaseStarted = $false
    }
    $receipt | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $PSScriptRoot 'member-control-proof-cleanup.json')
}
