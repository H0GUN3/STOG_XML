[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$artifact = $PSScriptRoot
$root = (Resolve-Path (Join-Path $artifact '../../..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$emulatorExe = Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'
$credentialPath = Join-Path $env:LOCALAPPDATA 'STOG\local-backend\stog_backend_app.password.clixml'
$jdk = 'C:\Program Files\Java\jdk-17'
$fixture = $null
$backend = $null
$emulator = $null
$emulatorStarted = $false
$plain = $null
$pointer = [IntPtr]::Zero
$owned = [ordered]@{
    subject = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString() + (Get-Random -Minimum 10 -Maximum 99).ToString())
    trip = [Guid]::NewGuid().ToString()
    item = [Guid]::NewGuid().ToString()
    place = [Guid]::NewGuid().ToString()
    nonmemberTrip = [Guid]::NewGuid().ToString()
    missingTrip = [Guid]::NewGuid().ToString()
    missingItem = [Guid]::NewGuid().ToString()
}
$providerCredential = ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N'))
$oldEnvironment = @{}
$oldFontScale = $null
$oldAccelerometerRotation = $null
$oldUserRotation = $null
$reverseBefore = $null
$deviceSerial = $null
$fixturePort = $null
$beforeCounts = $null
$instrumentOutput = $null
$backendLauncherPid = $null
$backendListenerPid = $null
$backendListenerProcess = $null
$fixturePid = $null
$emulatorPid = $null
$reverse8080Added = $false
$reverseFixtureAdded = $false

function Set-Env([hashtable]$values) {
    foreach ($name in $values.Keys) {
        if (-not $script:oldEnvironment.ContainsKey($name)) {
            $script:oldEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
        }
        [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process')
    }
}
function Restore-Env {
    foreach ($name in $script:oldEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $script:oldEnvironment[$name], 'Process')
    }
    $script:oldEnvironment.Clear()
}
function Start-Captured([string]$file, [string]$arguments, [string]$working = $root) {
    $psi = [Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $file
    $psi.Arguments = $arguments
    $psi.WorkingDirectory = $working
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $psi
    if (-not $process.Start()) { throw "Could not start owned process: $file" }
    return $process
}
function Start-OwnedEmulator {
    $stdout = Join-Path $artifact 'review-fix/logs/emulator-e2e.stdout.log'
    $stderr = Join-Path $artifact 'review-fix/logs/emulator-e2e.stderr.log'
    return Start-Process -FilePath $emulatorExe -ArgumentList '-avd Pixel_8 -port 5560 -no-window -no-audio -no-boot-anim' -WorkingDirectory $root -NoNewWindow -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
}
function Wait-AdbDevice([string]$serial) {
    $wait = Start-Captured $adb ("-s $serial wait-for-device")
    try {
        if (-not $wait.WaitForExit(120000)) { Stop-Owned $wait; throw 'Emulator adb readiness event timed out.' }
        if ($wait.ExitCode -ne 0) { throw 'Emulator adb readiness failed.' }
    } finally {
        if ($null -ne $wait -and $wait.HasExited) { $wait.Dispose() }
    }
}
function Wait-AndroidBoot([string]$serial) {
    $logcat = Start-Captured $adb ("-s $serial logcat -b events -s boot_progress_enable_screen:I")
    try {
        [void](Read-Ready $logcat 'boot_progress_enable_screen' 120 (Join-Path $artifact 'review-fix/logs/boot-e2e.log'))
    } finally {
        Stop-Owned $logcat
    }
}
function Read-Ready([Diagnostics.Process]$process, [string]$pattern, [int]$seconds, [string]$logPath) {
    $writer = [IO.StreamWriter]::new($logPath, $false, [Text.UTF8Encoding]::new($false))
    try {
        $deadline = [DateTime]::UtcNow.AddSeconds($seconds)
        while ($true) {
            $remaining = $deadline - [DateTime]::UtcNow
            if ($remaining -le [TimeSpan]::Zero) { throw 'Owned process readiness event timed out.' }
            $lineTask = $process.StandardOutput.ReadLineAsync()
            if (-not $lineTask.Wait($remaining)) { throw 'Owned process emitted no readiness event.' }
            $line = $lineTask.Result
            if ($null -eq $line) { throw 'Owned process exited before readiness.' }
            $writer.WriteLine($line)
            if ($line -match $pattern) { return $Matches }
        }
    } finally { $writer.Dispose() }
}
function Invoke-Psql([string]$sql) {
    $output = $sql | & wsl.exe -- psql -X -v ON_ERROR_STOP=1 -Atq -h 127.0.0.1 -U stog_backend_app -d stog_backend_dev -f -
    if ($LASTEXITCODE -ne 0) { throw 'Bounded fixture database operation failed.' }
    return ($output -join "`n").Trim()
}
function Stop-Owned($process) {
    if ($null -ne $process -and -not $process.HasExited) {
        & taskkill.exe /PID $process.Id /T /F 2>&1 | Out-Null
        if (-not $process.WaitForExit(10000)) { throw "Owned process $($process.Id) did not exit." }
    }
    if ($null -ne $process) { $process.Dispose() }
}

try {
    $attachedEmulators = @(& $adb devices | Where-Object { $_ -match '^(emulator-\d+)\s+device$' })
    if ($attachedEmulators.Count -gt 1) { throw 'Refusing E2E because multiple emulators are attached.' }
    $attachedEmulator = $attachedEmulators.Count -eq 1
    if ($attachedEmulator) { throw 'Refusing E2E because a pre-existing emulator must be preserved.' }
    $listener = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue
    if ($null -ne $listener) { throw 'Refusing E2E because 127.0.0.1:8080 is occupied.' }

    $secure = Import-Clixml -LiteralPath $credentialPath
    if ($secure -isnot [Security.SecureString]) { throw 'Protected local backend credential is invalid.' }
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $oldWsl = [Environment]::GetEnvironmentVariable('WSLENV', 'Process')
    $parts = @($oldWsl -split ':' | Where-Object { $_ -and ($_ -split '/')[0] -ne 'PGPASSWORD' })
    Set-Env @{ PGPASSWORD=$plain; WSLENV=(($parts + 'PGPASSWORD') -join ':'); JAVA_HOME=$jdk }

    $beforeCounts = Invoke-Psql "SELECT json_build_object('users',count(*)) FROM stog_app.stog_user; SELECT json_build_object('trips',count(*)) FROM stog_app.trip; SELECT json_build_object('items',count(*)) FROM stog_app.trip_item; SELECT json_build_object('identities',count(*)) FROM stog_app.auth_identity;"
    $collision = Invoke-Psql "SELECT (SELECT count(*) FROM stog_app.auth_identity WHERE provider='KAKAO' AND subject='$($owned.subject)') + (SELECT count(*) FROM stog_app.trip WHERE id IN ('$($owned.trip)'::uuid,'$($owned.nonmemberTrip)'::uuid)) + (SELECT count(*) FROM stog_app.trip_item WHERE id='$($owned.item)'::uuid) + (SELECT count(*) FROM stog_app.place WHERE id='$($owned.place)'::uuid);"
    if ($collision -ne '0') { throw 'Generated fixture identifiers collided.' }

    Set-Env @{
        STOG_FIXTURE_CREDENTIAL=$providerCredential
        STOG_FIXTURE_SUBJECT=$owned.subject
        STOG_FIXTURE_TRIP=$owned.trip
        STOG_FIXTURE_ITEM=$owned.item
        STOG_FIXTURE_PLACE=$owned.place
        STOG_FIXTURE_NONMEMBER_TRIP=$owned.nonmemberTrip
    }
    $fixture = Start-Captured 'python.exe' ('"' + (Join-Path $artifact 'provider_and_db_fixture.py') + '"')
    $fixturePid = $fixture.Id
    $match = Read-Ready $fixture '^READY ([0-9]+)$' 10 (Join-Path $artifact 'logs/provider-fixture.log')
    $fixturePort = [int]$match[1]

    Set-Env @{
        STOG_AUTH_KAKAO_ENABLED='true'
        STOG_AUTH_KAKAO_EXPECTED_APP_ID='12345'
        STOG_AUTH_KAKAO_FIXTURE_MODE='true'
        STOG_AUTH_KAKAO_TOKEN_INFO_URI="http://127.0.0.1:$fixturePort/token-info"
        STOG_AUTH_KAKAO_USER_INFO_URI="http://127.0.0.1:$fixturePort/user-info"
    }
    $backend = Start-Captured 'powershell.exe' ("-NoProfile -ExecutionPolicy Bypass -File `"" + (Join-Path $root 'backend/scripts/Start-LocalBackend.ps1') + "`"")
    $backendLauncherPid = $backend.Id
    [void](Read-Ready $backend 'Started StogBackendApplication' 120 (Join-Path $artifact 'logs/backend-e2e.log'))
    $backendListener = Get-NetTCPConnection -State Listen -LocalAddress 127.0.0.1 -LocalPort 8080 -ErrorAction Stop
    $backendListenerPid = $backendListener.OwningProcess
    $backendListenerProcess = Get-Process -Id $backendListenerPid -ErrorAction Stop

    if (-not $attachedEmulator) {
        $deviceSerial = 'emulator-5560'
        $emulator = Start-OwnedEmulator
        $emulatorPid = $emulator.Id
        $emulatorStarted = $true
        Wait-AdbDevice $deviceSerial
        Wait-AndroidBoot $deviceSerial
    }
    & $adb -s $deviceSerial shell cmd activity wait-for-broadcast-idle | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Android activity manager was not ready.' }
    $oldFontScale = (& $adb -s $deviceSerial shell settings get system font_scale).Trim()
    $oldAccelerometerRotation = (& $adb -s $deviceSerial shell settings get system accelerometer_rotation).Trim()
    $oldUserRotation = (& $adb -s $deviceSerial shell settings get system user_rotation).Trim()
    $reverseBefore = ((& $adb -s $deviceSerial reverse --list) -join "`n").Trim()
    if ($reverseBefore -match 'tcp:8080' -or $reverseBefore -match "tcp:$fixturePort") {
        throw 'Refusing E2E because an owned reverse port already exists.'
    }
    & $adb -s $deviceSerial shell settings put system font_scale 1.30
    & $adb -s $deviceSerial reverse tcp:8080 tcp:8080
    if ($LASTEXITCODE -ne 0) { throw 'Backend reverse setup failed.' }
    $reverse8080Added = $true
    & $adb -s $deviceSerial reverse "tcp:$fixturePort" "tcp:$fixturePort"
    if ($LASTEXITCODE -ne 0) { throw 'Fixture reverse setup failed.' }
    $reverseFixtureAdded = $true
    & $adb -s $deviceSerial shell rm -rf /sdcard/Pictures/STOG-TripItem-QA

    Push-Location $root
    try {
        & .\gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw 'Fixture Android APK build failed.' }
    } finally { Pop-Location }
    & $adb -s $deviceSerial install -r (Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'App install failed.' }
    & $adb -s $deviceSerial install -r (Join-Path $root 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed.' }

    $instrumentOutput = & $adb -s $deviceSerial shell am instrument -w -r `
        -e class com.stog.app.auth.RealBackendTripItemUiTest `
        -e providerToken $providerCredential `
        -e tripId $owned.trip -e itemId $owned.item -e missingTripId $owned.missingTrip `
        -e nonmemberTripId $owned.nonmemberTrip -e missingItemId $owned.missingItem `
        -e controlPort $fixturePort com.stog.app.test/androidx.test.runner.AndroidJUnitRunner
    if ($LASTEXITCODE -ne 0 -or ($instrumentOutput -join "`n") -notmatch 'OK \(1 test\)') {
        ($instrumentOutput -join "`n") | Set-Content -Encoding utf8 (Join-Path $artifact 'logs/e2e-instrumentation-failure.log')
        throw 'Real local Android instrumentation failed.'
    }
    ($instrumentOutput -join "`n") -replace $providerCredential,'[REDACTED]' | Set-Content -Encoding utf8 (Join-Path $artifact 'logs/e2e-instrumentation.log')

    New-Item -ItemType Directory -Force -Path (Join-Path $artifact 'screenshots') | Out-Null
    & $adb -s $deviceSerial pull /sdcard/Pictures/STOG-TripItem-QA/. (Join-Path $artifact 'screenshots') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Safe STOG screenshots could not be pulled.' }

    $providerFacts = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$fixturePort/facts").Content | ConvertFrom-Json
    $dbFacts = Invoke-Psql "WITH u AS (SELECT user_id FROM stog_app.auth_identity WHERE provider='KAKAO' AND subject='$($owned.subject)'), s AS (SELECT id,revoked_at FROM stog_app.auth_session WHERE user_id=(SELECT user_id FROM u)), r AS (SELECT used_at FROM stog_app.auth_refresh_token WHERE session_id=(SELECT id FROM s)) SELECT json_build_object('identity_rows',(SELECT count(*) FROM u),'session_rows',(SELECT count(*) FROM s),'session_revoked',(SELECT count(*) FROM s WHERE revoked_at IS NOT NULL),'refresh_rows',(SELECT count(*) FROM r),'used_refresh_rows',(SELECT count(*) FROM r WHERE used_at IS NOT NULL));"
    $result = [ordered]@{
        result='PASS'
        surface='Android emulator UI -> adb reverse -> real local Spring backend -> PostgreSQL'
        provider='literal-loopback synthetic Kakao fixture; real Kakao NOT_RUN'
        instrumentationTests=1
        successRead=$true
        missingTrip404=$true
        nonmemberTrip404=$true
        missingItem404=$true
        accessExpiryRefreshSingleRetry=($providerFacts.token_info -eq 1 -and (($dbFacts | ConvertFrom-Json).refresh_rows -eq 2) -and (($dbFacts | ConvertFrom-Json).used_refresh_rows -eq 1))
        logoutNoStaleContent=$true
        providerCalls=[ordered]@{tokenInfo=$providerFacts.token_info; userInfo=$providerFacts.user_info}
        database=($dbFacts | ConvertFrom-Json)
        generatedIds=[ordered]@{trip=$owned.trip; item=$owned.item; nonmemberTrip=$owned.nonmemberTrip}
        credentialsRecorded=$false
    }
    $result | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $artifact 'real-local-e2e.json')
} finally {
    $providerCredential = $null
    $settingsRestored = $false
    $reverseRestored = $false
    $backendLauncherAbsent = $false
    $backendListenerPidAbsent = $false
    $backendListenerAbsent = $false
    $fixtureProcessAbsent = $false
    $emulatorProcessAbsent = $false
    $emulatorDeviceAbsent = $false
    $ownedRowsRemaining = $null
    $aggregateCountsRestored = $false

    if ($null -ne $deviceSerial) {
        if ($null -ne $oldFontScale) { & $adb -s $deviceSerial shell settings put system font_scale $oldFontScale 2>$null | Out-Null }
        if ($null -ne $oldAccelerometerRotation) { & $adb -s $deviceSerial shell settings put system accelerometer_rotation $oldAccelerometerRotation 2>$null | Out-Null }
        if ($null -ne $oldUserRotation) { & $adb -s $deviceSerial shell settings put system user_rotation $oldUserRotation 2>$null | Out-Null }
        if ($reverse8080Added) { try { & $adb -s $deviceSerial reverse --remove tcp:8080 2>$null | Out-Null } catch {} }
        if ($reverseFixtureAdded -and $null -ne $fixturePort) { try { & $adb -s $deviceSerial reverse --remove "tcp:$fixturePort" 2>$null | Out-Null } catch {} }
        & $adb -s $deviceSerial shell rm -rf /sdcard/Pictures/STOG-TripItem-QA 2>$null | Out-Null
        $fontAfter = (& $adb -s $deviceSerial shell settings get system font_scale 2>$null).Trim()
        $accelerometerAfter = (& $adb -s $deviceSerial shell settings get system accelerometer_rotation 2>$null).Trim()
        $userRotationAfter = (& $adb -s $deviceSerial shell settings get system user_rotation 2>$null).Trim()
        $reverseAfter = ((& $adb -s $deviceSerial reverse --list 2>$null) -join "`n").Trim()
        $settingsRestored = $fontAfter -eq $oldFontScale -and $accelerometerAfter -eq $oldAccelerometerRotation -and $userRotationAfter -eq $oldUserRotation
        $reverseRestored = $reverseAfter -eq $reverseBefore
    }

    Stop-Owned $backend
    $backendLauncherAbsent = $null -eq $backendLauncherPid -or $null -eq (Get-Process -Id $backendLauncherPid -ErrorAction SilentlyContinue)
    if ($null -ne $backendListenerProcess) {
        if (-not $backendListenerProcess.HasExited -and -not $backendListenerProcess.WaitForExit(10000)) {
            Stop-Process -InputObject $backendListenerProcess -Force -ErrorAction SilentlyContinue
            [void]$backendListenerProcess.WaitForExit(10000)
        }
        $backendListenerPidAbsent = $backendListenerProcess.HasExited
        $backendListenerProcess.Dispose()
    } else {
        $backendListenerPidAbsent = $true
    }
    $backendListenerAbsent = @((Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue)).Count -eq 0

    if ($null -ne $plain) {
        $ownedRowsRemaining = Invoke-Psql "BEGIN; DELETE FROM stog_app.trip WHERE id IN ('$($owned.trip)'::uuid,'$($owned.nonmemberTrip)'::uuid); DELETE FROM stog_app.place WHERE id='$($owned.place)'::uuid; DELETE FROM stog_app.auth_session WHERE user_id IN (SELECT user_id FROM stog_app.auth_identity WHERE provider='KAKAO' AND subject='$($owned.subject)'); DELETE FROM stog_app.auth_login_exchange WHERE provider='KAKAO' AND subject='$($owned.subject)'; WITH gone AS (DELETE FROM stog_app.auth_identity WHERE provider='KAKAO' AND subject='$($owned.subject)' RETURNING user_id) DELETE FROM stog_app.stog_user WHERE id IN (SELECT user_id FROM gone); COMMIT; SELECT (SELECT count(*) FROM stog_app.auth_identity WHERE provider='KAKAO' AND subject='$($owned.subject)')::text||'|'||(SELECT count(*) FROM stog_app.trip WHERE id IN ('$($owned.trip)'::uuid,'$($owned.nonmemberTrip)'::uuid))::text||'|'||(SELECT count(*) FROM stog_app.trip_item WHERE id='$($owned.item)'::uuid)::text||'|'||(SELECT count(*) FROM stog_app.place WHERE id='$($owned.place)'::uuid)::text;"
        $afterCounts = Invoke-Psql "SELECT json_build_object('users',count(*)) FROM stog_app.stog_user; SELECT json_build_object('trips',count(*)) FROM stog_app.trip; SELECT json_build_object('items',count(*)) FROM stog_app.trip_item; SELECT json_build_object('identities',count(*)) FROM stog_app.auth_identity;"
        $aggregateCountsRestored = $beforeCounts -eq $afterCounts
    }

    Stop-Owned $fixture
    $fixtureProcessAbsent = $null -eq $fixturePid -or $null -eq (Get-Process -Id $fixturePid -ErrorAction SilentlyContinue)
    if ($emulatorStarted -and $null -ne $emulator) {
        & $adb -s $deviceSerial uninstall com.stog.app.test 2>$null | Out-Null
        & $adb -s $deviceSerial uninstall com.stog.app 2>$null | Out-Null
        & $adb -s $deviceSerial emu kill 2>$null | Out-Null
        if (-not $emulator.WaitForExit(10000)) { Stop-Owned $emulator } else { $emulator.Dispose() }
        & $adb -s $deviceSerial wait-for-disconnect 2>$null
        $emulatorProcessAbsent = $null -eq $emulatorPid -or $null -eq (Get-Process -Id $emulatorPid -ErrorAction SilentlyContinue)
        $emulatorDeviceAbsent = -not ((& $adb devices) -match "^$deviceSerial\s+device(?:\s|$)")
    }
    Restore-Env
    $plain = $null
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }

    $receipt = [ordered]@{
        ownedRowsRemaining=$ownedRowsRemaining
        aggregateCountsRestored=$aggregateCountsRestored
        backend=[ordered]@{launcherPid=$backendLauncherPid; launcherAbsent=$backendLauncherAbsent; listenerPid=$backendListenerPid; listenerPidAbsent=$backendListenerPidAbsent; port8080ListenerAbsent=$backendListenerAbsent}
        providerFixture=[ordered]@{pid=$fixturePid; processAbsent=$fixtureProcessAbsent}
        device=[ordered]@{serial=$deviceSerial; emulatorPid=$emulatorPid; processAbsent=$emulatorProcessAbsent; adbConnectedDeviceAbsent=$emulatorDeviceAbsent; reverseBefore=$reverseBefore; reverseAfter=$reverseAfter; reverseRestored=$reverseRestored; fontScaleBefore=$oldFontScale; fontScaleAfter=$fontAfter; fontScaleRestored=($fontAfter -eq $oldFontScale); accelerometerRotationBefore=$oldAccelerometerRotation; accelerometerRotationAfter=$accelerometerAfter; userRotationBefore=$oldUserRotation; userRotationAfter=$userRotationAfter; rotationRestored=($accelerometerAfter -eq $oldAccelerometerRotation -and $userRotationAfter -eq $oldUserRotation)}
        durableDatabaseRetained=$true
        dpapiCredentialRetained=(Test-Path $credentialPath)
        preexistingEmulatorTouched=$false
    }
    $receipt | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $artifact 'cleanup.json')
    $cleanupPassed = $ownedRowsRemaining -eq '0|0|0|0' -and $aggregateCountsRestored -and $backendLauncherAbsent -and $backendListenerPidAbsent -and $backendListenerAbsent -and $fixtureProcessAbsent -and $settingsRestored -and $reverseRestored -and $emulatorProcessAbsent -and $emulatorDeviceAbsent
    if (-not $cleanupPassed) { throw 'Owned E2E cleanup post-check failed; inspect cleanup.json.' }
}
