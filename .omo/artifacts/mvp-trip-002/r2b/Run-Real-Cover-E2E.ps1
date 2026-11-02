[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$artifact = $PSScriptRoot
$root = (Resolve-Path (Join-Path $artifact '../../../..')).Path
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$emulatorExe = Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'
$credentialPath = Join-Path $env:LOCALAPPDATA 'STOG\local-backend\stog_backend_app.password.clixml'
$jdk = 'C:\Program Files\Java\jdk-17'
$database = 'stog_backend_test'
$fixtureRole = 'stog_mvp_trip_' + [Guid]::NewGuid().ToString('N')
$fixtureSchema = $fixtureRole
$fixtureDbPassword = -join ((1..64) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) })
$fixtureProvisioned = $false
$metadataBefore = $null
$metadataAfter = $null
$fixture = $null
$backend = $null
$emulator = $null
$emulatorStarted = $false
$owned = [ordered]@{
    memberSubject = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString() + (Get-Random -Minimum 100 -Maximum 499).ToString())
    nonmemberSubject = ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString() + (Get-Random -Minimum 500 -Maximum 999).ToString())
}
$memberCredential = ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N'))
$ownerUiCredential = ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N'))
$nonmemberCredential = ([Guid]::NewGuid().ToString('N') + [Guid]::NewGuid().ToString('N'))
$oldEnvironment = @{}
$oldFontScale = $null
$oldAccelerometerRotation = $null
$oldUserRotation = $null
$fontAfter = $null
$accelerometerAfter = $null
$userRotationAfter = $null
$reverseBefore = $null
$reverseAfter = $null
$deviceSerial = $null
$fixturePort = $null
$proxyPort = $null
$beforeCounts = $null
$instrumentOutput = $null
$backendLauncherPid = $null
$backendListenerPid = $null
$backendListenerProcess = $null
$fixturePid = $null
$emulatorPid = $null
$reverse8080Added = $false
$reverseFixtureAdded = $false
$emulatorProcessIdsBefore = @(Get-Process emulator -ErrorAction SilentlyContinue | ForEach-Object { $_.Id })
$emulatorShutdownHelpersAbsent = $true
$mediaRoot = Join-Path ([IO.Path]::GetTempPath()) ('stog-r2b-e2e-media-' + [Guid]::NewGuid().ToString('N'))
$mediaRootCleanup = $false

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
    $stdout = Join-Path $artifact 'logs/emulator-e2e.stdout.log'
    $stderr = Join-Path $artifact 'logs/emulator-e2e.stderr.log'
    return Start-Process -FilePath $emulatorExe -ArgumentList '-avd Pixel_8 -port 5560 -no-window -no-audio -no-boot-anim -no-snapshot-load' -WorkingDirectory $root -NoNewWindow -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
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
        [void](Read-Ready $logcat 'boot_progress_enable_screen' 120 (Join-Path $artifact 'logs/boot-e2e.log'))
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
function Invoke-Psql([string]$sql, [switch]$Admin) {
    $oldPassword = [Environment]::GetEnvironmentVariable('PGPASSWORD', 'Process')
    $oldWsl = [Environment]::GetEnvironmentVariable('WSLENV', 'Process')
    try {
        if (-not $Admin) {
            [Environment]::SetEnvironmentVariable('PGPASSWORD', $script:fixtureDbPassword, 'Process')
            $parts = @($oldWsl -split ':' | Where-Object { $_ -and ($_ -split '/')[0] -ne 'PGPASSWORD' })
            [Environment]::SetEnvironmentVariable('WSLENV', (($parts + 'PGPASSWORD') -join ':'), 'Process')
            $output = $sql | & wsl.exe -- psql -X -v ON_ERROR_STOP=1 -Atq -h 127.0.0.1 -U $script:fixtureRole -d $script:database -f -
        } else {
            $output = $sql | & wsl.exe -u postgres -- psql -X -v ON_ERROR_STOP=1 -Atq -d $script:database -f -
        }
        if ($LASTEXITCODE -ne 0) { throw 'Bounded fixture database operation failed.' }
        return ($output -join "`n").Trim()
    } finally {
        [Environment]::SetEnvironmentVariable('PGPASSWORD', $oldPassword, 'Process')
        [Environment]::SetEnvironmentVariable('WSLENV', $oldWsl, 'Process')
    }
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

    if ($fixtureRole.Length -ge 63 -or $fixtureRole -notmatch '^stog_mvp_trip_[0-9a-f]{32}$') { throw 'Generated database identifier is unsafe.' }
    $identity = Invoke-Psql "SELECT current_database()||'|'||current_setting('server_version_num');" -Admin
    if ($identity -ne "$database|180006") { throw "Unexpected PostgreSQL identity: $identity" }
    $absence = Invoke-Psql "SELECT (SELECT count(*) FROM pg_roles WHERE rolname='$fixtureRole')||'|'||(SELECT count(*) FROM pg_namespace WHERE nspname='$fixtureSchema');" -Admin
    if ($absence -ne '0|0') { throw 'Generated role/schema collision.' }
    $metadataBefore = Invoke-Psql "SELECT md5(COALESCE(string_agg(row_to_json(x)::text,E'\n' ORDER BY x.rolname),'')) FROM (SELECT rolname,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolcanlogin FROM pg_roles WHERE rolname<>'$fixtureRole') x; SELECT md5(COALESCE(string_agg(row_to_json(x)::text,E'\n' ORDER BY x.nspname),'')) FROM (SELECT nspname,pg_get_userbyid(nspowner) owner,nspacl::text acl FROM pg_namespace WHERE nspname<>'$fixtureSchema') x;" -Admin
    Invoke-Psql "BEGIN; CREATE ROLE `"$fixtureRole`" LOGIN PASSWORD '$fixtureDbPassword' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION; CREATE SCHEMA `"$fixtureSchema`" AUTHORIZATION `"$fixtureRole`"; GRANT USAGE,CREATE ON SCHEMA `"$fixtureSchema`" TO `"$fixtureRole`"; GRANT CONNECT ON DATABASE `"$database`" TO `"$fixtureRole`"; COMMIT;" -Admin | Out-Null
    $fixtureProvisioned = $true

    Set-Env @{
        JAVA_HOME=$jdk
        SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/$database`?currentSchema=$fixtureSchema"
        SPRING_DATASOURCE_USERNAME=$fixtureRole
        SPRING_DATASOURCE_PASSWORD=$fixtureDbPassword
        SPRING_DATASOURCE_HIKARI_SCHEMA=$fixtureSchema
        SPRING_FLYWAY_DEFAULT_SCHEMA=$fixtureSchema
        SPRING_FLYWAY_SCHEMAS=$fixtureSchema
        SPRING_FLYWAY_CREATE_SCHEMAS='false'
        SERVER_ADDRESS='127.0.0.1'
        SERVER_PORT='8080'
        STOG_FIXTURE_MEMBER_CREDENTIAL=$memberCredential
        STOG_FIXTURE_OWNER_UI_CREDENTIAL=$ownerUiCredential
        STOG_FIXTURE_NONMEMBER_CREDENTIAL=$nonmemberCredential
        STOG_FIXTURE_MEMBER_SUBJECT=$owned.memberSubject
        STOG_FIXTURE_NONMEMBER_SUBJECT=$owned.nonmemberSubject
        STOG_FIXTURE_DB_ROLE=$fixtureRole
        STOG_FIXTURE_DB_SCHEMA=$fixtureSchema
        STOG_FIXTURE_DB_PASSWORD=$fixtureDbPassword
        STOG_MEDIA_ROOT=$mediaRoot
    }
    [IO.Directory]::CreateDirectory($mediaRoot) | Out-Null
    $fixture = Start-Captured 'python.exe' ('"' + (Join-Path $artifact 'provider_and_cover_loss_proxy.py') + '"')
    $fixturePid = $fixture.Id
    $match = Read-Ready $fixture '^READY ([0-9]+) ([0-9]+)$' 10 (Join-Path $artifact 'logs/provider-fixture.log')
    $fixturePort = [int]$match[1]
    $proxyPort = [int]$match[2]

    Set-Env @{
        STOG_AUTH_KAKAO_ENABLED='true'
        STOG_AUTH_KAKAO_EXPECTED_APP_ID='12345'
        STOG_AUTH_KAKAO_FIXTURE_MODE='true'
        STOG_AUTH_KAKAO_TOKEN_INFO_URI="http://127.0.0.1:$fixturePort/token-info"
        STOG_AUTH_KAKAO_USER_INFO_URI="http://127.0.0.1:$fixturePort/user-info"
    }
    $backendJar = Join-Path $root 'backend/build/libs/stog-backend-0.0.1-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $backendJar)) { throw 'Corrected backend bootJar is missing.' }
    $backend = Start-Captured (Join-Path $jdk 'bin/java.exe') ('-jar "' + $backendJar + '"')
    $backendLauncherPid = $backend.Id
    [void](Read-Ready $backend 'Started StogBackendApplication' 120 (Join-Path $artifact 'logs/backend-e2e.log'))
    $beforeCounts = Invoke-Psql "SELECT json_build_object('users',count(*)) FROM $fixtureSchema.stog_user; SELECT json_build_object('trips',count(*)) FROM $fixtureSchema.trip; SELECT json_build_object('members',count(*)) FROM $fixtureSchema.trip_member; SELECT json_build_object('executions',count(*)) FROM $fixtureSchema.trip_create_execution; SELECT json_build_object('identities',count(*)) FROM $fixtureSchema.auth_identity;"
    $collision = Invoke-Psql "SELECT count(*) FROM $fixtureSchema.auth_identity WHERE provider='KAKAO' AND subject IN ('$($owned.memberSubject)','$($owned.nonmemberSubject)');"
    if ($collision -ne '0') { throw 'Generated fixture subjects collided.' }
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
    & $adb -s $deviceSerial reverse tcp:8080 "tcp:$proxyPort"
    if ($LASTEXITCODE -ne 0) { throw 'Backend reverse setup failed.' }
    $reverse8080Added = $true
    & $adb -s $deviceSerial reverse "tcp:$fixturePort" "tcp:$fixturePort"
    if ($LASTEXITCODE -ne 0) { throw 'Fixture reverse setup failed.' }
    $reverseFixtureAdded = $true
    & $adb -s $deviceSerial shell rm -rf /sdcard/Pictures/STOG-R2b-Real-E2E

    Push-Location $root
    try {
        & .\gradlew.bat --no-daemon --rerun-tasks -PstogLocalInstrumentationFixture=true :app:assembleDebug :app:assembleDebugAndroidTest
        if ($LASTEXITCODE -ne 0) { throw 'Fixture Android APK build failed.' }
    } finally { Pop-Location }
    & $adb -s $deviceSerial install -r (Join-Path $root 'app/build/outputs/apk/debug/app-debug.apk') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'App install failed.' }
    & $adb -s $deviceSerial install -r (Join-Path $root 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed.' }
    & $adb -s $deviceSerial logcat -c
    if ($LASTEXITCODE -ne 0) { throw 'Device logcat reset failed.' }

    $instrumentOutput = & $adb -s $deviceSerial shell am instrument -w -r `
        -e class com.stog.app.auth.RealBackendTripCoverUiTest `
        -e ownerProviderToken $memberCredential `
        -e ownerUiProviderToken $ownerUiCredential `
        -e memberProviderToken $nonmemberCredential `
        -e controlPort $fixturePort com.stog.app.test/androidx.test.runner.AndroidJUnitRunner
    if ($LASTEXITCODE -ne 0 -or ($instrumentOutput -join "`n") -notmatch 'OK \(1 test\)') {
        ($instrumentOutput -join "`n") | Set-Content -Encoding utf8 (Join-Path $artifact 'logs/e2e-instrumentation-failure.log')
        $failureFacts = [ordered]@{
            provider = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$fixturePort/facts").Content | ConvertFrom-Json
            database = (Invoke-Psql "SELECT json_build_object('identities',(SELECT count(*) FROM $fixtureSchema.auth_identity),'sessions',(SELECT count(*) FROM $fixtureSchema.auth_session),'trips',(SELECT count(*) FROM $fixtureSchema.trip),'members',(SELECT count(*) FROM $fixtureSchema.trip_member),'covers',(SELECT count(*) FROM $fixtureSchema.trip_cover),'cover_executions',(SELECT count(*) FROM $fixtureSchema.trip_command_execution WHERE command_type IN ('SET_TRIP_COVER_V1','REMOVE_TRIP_COVER_V1')),'current_cover_pointers',(SELECT count(*) FROM $fixtureSchema.trip WHERE cover_id IS NOT NULL));") | ConvertFrom-Json
        }
        $failureFacts | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $artifact 'logs/e2e-runtime-failure-facts.json')
        $failedScreenshots = Join-Path $artifact 'screenshots/failed-run'
        Remove-Item -Recurse -Force $failedScreenshots -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $failedScreenshots | Out-Null
        $pull = Start-Process -FilePath $adb -ArgumentList @('-s', $deviceSerial, 'pull', '/sdcard/Pictures/STOG-R2b-Real-E2E/.', $failedScreenshots) -NoNewWindow -Wait -PassThru
        if ($pull.ExitCode -ne 0) { Set-Content -Encoding utf8 (Join-Path $artifact 'logs/failed-screenshot-pull.txt') 'No failed-run screenshots were available.' }
        throw 'Real local Android instrumentation failed; inspect preserved instrumentation and runtime facts.'
    }
    ($instrumentOutput -join "`n") -replace $memberCredential,'[REDACTED]' -replace $ownerUiCredential,'[REDACTED]' -replace $nonmemberCredential,'[REDACTED]' | Set-Content -Encoding utf8 (Join-Path $artifact 'logs/e2e-instrumentation.log')
    $crashScan = ((& $adb -s $deviceSerial logcat -d -v threadtime 'AndroidRuntime:E' '*:S') -join "`n")
    $crashScan | Set-Content -Encoding utf8 (Join-Path $artifact 'logs/e2e-crash-scan.log')
    if ($crashScan -match 'FATAL EXCEPTION' -or $crashScan -match 'Process: com\.stog\.app') {
        throw 'App crash detected after passing instrumentation.'
    }

    New-Item -ItemType Directory -Force -Path (Join-Path $artifact 'screenshots') | Out-Null
    Remove-Item -Force (Join-Path $artifact 'screenshots/*.png') -ErrorAction SilentlyContinue
    & $adb -s $deviceSerial pull /sdcard/Pictures/STOG-R2b-Real-E2E/. (Join-Path $artifact 'screenshots') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Safe STOG screenshots could not be pulled.' }

    $providerFacts = (Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$fixturePort/facts").Content | ConvertFrom-Json
    $dbFacts = Invoke-Psql "WITH fixture_users AS (SELECT user_id,subject FROM $fixtureSchema.auth_identity WHERE provider='KAKAO' AND subject IN ('$($owned.memberSubject)','$($owned.nonmemberSubject)')) SELECT json_build_object('identity_rows',(SELECT count(*) FROM fixture_users),'trips',(SELECT count(*) FROM $fixtureSchema.trip),'soft_deleted',(SELECT count(*) FROM $fixtureSchema.trip WHERE deleted_at IS NOT NULL),'covers',(SELECT count(*) FROM $fixtureSchema.trip_cover),'cover_executions',(SELECT count(*) FROM $fixtureSchema.trip_command_execution WHERE command_type IN ('SET_TRIP_COVER_V1','REMOVE_TRIP_COVER_V1')),'current_cover_pointers',(SELECT count(*) FROM $fixtureSchema.trip WHERE cover_id IS NOT NULL),'set_request_rows',(SELECT count(*) FROM $fixtureSchema.trip_command_execution WHERE command_type='SET_TRIP_COVER_V1'),'remove_request_rows',(SELECT count(*) FROM $fixtureSchema.trip_command_execution WHERE command_type='REMOVE_TRIP_COVER_V1'),'unchanged_rows',(SELECT count(*) FROM $fixtureSchema.trip_command_execution WHERE command_type='REMOVE_TRIP_COVER_V1' AND result_json->>'result'='UNCHANGED'),'main_plan_version',(SELECT plan_version FROM $fixtureSchema.trip WHERE deleted_at IS NOT NULL),'invalid_plan_version',(SELECT plan_version FROM $fixtureSchema.trip WHERE deleted_at IS NULL));"
    $dbObject = $dbFacts | ConvertFrom-Json
    $coverRoot = Join-Path $mediaRoot 'covers'
    $mediaFacts = [ordered]@{directories=@(Get-ChildItem -LiteralPath $coverRoot -Directory -ErrorAction SilentlyContinue).Count;displayFiles=@(Get-ChildItem -LiteralPath $coverRoot -Recurse -File -Filter 'display.jpg' -ErrorAction SilentlyContinue).Count;thumbnailFiles=@(Get-ChildItem -LiteralPath $coverRoot -Recurse -File -Filter 'thumbnail.jpg' -ErrorAction SilentlyContinue).Count}
    $result = [ordered]@{
        result='PASS'
        surface='native Android document picker -> response-loss proxy -> real Spring backend -> isolated PostgreSQL V1-V5/private media root'
        provider='literal-loopback synthetic Kakao fixture through normal exchange/security filter; real Kakao NOT_RUN'
        instrumentationTests=1
        replacementResponseLossReplayExactlyOne=($providerFacts.create_requests -eq 9 -and $providerFacts.create_responses_dropped -eq 1 -and @($providerFacts.dropped_cover_statuses).Count -eq 1 -and $providerFacts.dropped_cover_statuses[0] -eq 201 -and $dbObject.set_request_rows -eq 4)
        privateCoverLifecycle=($dbObject.identity_rows -eq 2 -and $dbObject.covers -eq 4 -and $dbObject.cover_executions -eq 7 -and $dbObject.soft_deleted -eq 1 -and $dbObject.main_plan_version -eq 9)
        exactNoopReplay=($dbObject.remove_request_rows -eq 3 -and $dbObject.unchanged_rows -eq 1 -and $dbObject.invalid_plan_version -eq 0)
        exactFilePairs=($mediaFacts.directories -eq 4 -and $mediaFacts.displayFiles -eq 4 -and $mediaFacts.thumbnailFiles -eq 4)
        noOpCallbackContract='backend 111-test gate TripCoverPostgresIntegrationTest asserts event count unchanged on UNCHANGED/replay'
        nativePickerAndExpectedPixels=$true
        removedMemberAndDeletedTripDenied=$true
        invalidInputsLeaveNoPointer=$true
        softDeleteRetainsPrivatePointer=($dbObject.current_cover_pointers -ge 1)
        logoutNoStaleContent=$true
        crashScanClean=$true
        providerCalls=$providerFacts
        database=$dbObject
        privateFiles=$mediaFacts
        databaseFixture="generated isolated non-superuser role/schema in $database, migrated V1-V5"
        generatedFixtureIdsRecorded='subjects are generated fixture IDs; tokens omitted'
        credentialsRecorded=$false
    }
    $result | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $artifact 'real-local-e2e-candidate.json')
    $requiredChecks = @($result.replacementResponseLossReplayExactlyOne,$result.privateCoverLifecycle,$result.exactNoopReplay,$result.exactFilePairs,$result.nativePickerAndExpectedPixels,$result.removedMemberAndDeletedTripDenied,$result.invalidInputsLeaveNoPointer,$result.softDeleteRetainsPrivatePointer,$result.logoutNoStaleContent,$result.crashScanClean)
    if ($requiredChecks -contains $false) { throw 'Real cover E2E postconditions failed; inspect real-local-e2e-candidate.json.' }
    $result | ConvertTo-Json -Depth 5 | Set-Content -Encoding utf8 (Join-Path $artifact 'real-local-e2e-result.json')
} finally {
    $cleanupErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $memberCredential = $null
    $ownerUiCredential = $null
    $nonmemberCredential = $null
    $settingsRestored = $false
    $reverseRestored = $false
    $backendLauncherAbsent = $false
    $backendListenerPidAbsent = $false
    $backendListenerAbsent = $false
    $fixtureProcessAbsent = $false
    $emulatorProcessAbsent = -not $emulatorStarted
    $emulatorDeviceAbsent = -not $emulatorStarted
    $ownedRowsRemaining = $null
    $aggregateCountsRestored = $false

    if ($null -ne $deviceSerial) {
        if ($null -ne $oldFontScale) { & $adb -s $deviceSerial shell settings put system font_scale $oldFontScale 2>$null | Out-Null }
        if ($null -ne $oldAccelerometerRotation) { & $adb -s $deviceSerial shell settings put system accelerometer_rotation $oldAccelerometerRotation 2>$null | Out-Null }
        if ($null -ne $oldUserRotation) { & $adb -s $deviceSerial shell settings put system user_rotation $oldUserRotation 2>$null | Out-Null }
        if ($reverse8080Added) { try { & $adb -s $deviceSerial reverse --remove tcp:8080 2>$null | Out-Null } catch {} }
        if ($reverseFixtureAdded -and $null -ne $fixturePort) { try { & $adb -s $deviceSerial reverse --remove "tcp:$fixturePort" 2>$null | Out-Null } catch {} }
        & $adb -s $deviceSerial shell rm -rf /sdcard/Pictures/STOG-R2b-Real-E2E 2>$null | Out-Null
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

    if ($fixtureProvisioned) {
        Invoke-Psql "BEGIN; DROP SCHEMA `"$fixtureSchema`" CASCADE; REVOKE CONNECT ON DATABASE `"$database`" FROM `"$fixtureRole`"; DROP ROLE `"$fixtureRole`"; COMMIT;" -Admin | Out-Null
        $ownedRowsRemaining = Invoke-Psql "SELECT (SELECT count(*) FROM pg_roles WHERE rolname='$fixtureRole')||'|'||(SELECT count(*) FROM pg_namespace WHERE nspname='$fixtureSchema');" -Admin
        $metadataAfter = Invoke-Psql "SELECT md5(COALESCE(string_agg(row_to_json(x)::text,E'\n' ORDER BY x.rolname),'')) FROM (SELECT rolname,rolsuper,rolcreatedb,rolcreaterole,rolreplication,rolcanlogin FROM pg_roles WHERE rolname<>'$fixtureRole') x; SELECT md5(COALESCE(string_agg(row_to_json(x)::text,E'\n' ORDER BY x.nspname),'')) FROM (SELECT nspname,pg_get_userbyid(nspowner) owner,nspacl::text acl FROM pg_namespace WHERE nspname<>'$fixtureSchema') x;" -Admin
        $aggregateCountsRestored = $metadataBefore -eq $metadataAfter
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
        $shutdownHelpers = @(Get-CimInstance Win32_Process -Filter "Name='emulator.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.ProcessId -notin $emulatorProcessIdsBefore -and $_.CommandLine -match '(?:^|\s)-kill\s+\d+' })
        foreach ($helper in $shutdownHelpers) {
            $helperProcess = Get-Process -Id $helper.ProcessId -ErrorAction SilentlyContinue
            if ($null -ne $helperProcess -and -not $helperProcess.WaitForExit(30000)) {
                Stop-Process -Id $helper.ProcessId -Force -ErrorAction SilentlyContinue
                [void]$helperProcess.WaitForExit(10000)
            }
        }
        $emulatorShutdownHelpersAbsent = @((Get-CimInstance Win32_Process -Filter "Name='emulator.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.ProcessId -notin $emulatorProcessIdsBefore -and $_.CommandLine -match '(?:^|\s)-kill\s+\d+' })).Count -eq 0
    }
    Restore-Env
    if ($mediaRoot -and (Test-Path -LiteralPath $mediaRoot)) { Remove-Item -LiteralPath $mediaRoot -Recurse -Force -ErrorAction SilentlyContinue }
    $mediaRootCleanup = -not (Test-Path -LiteralPath $mediaRoot)
    $fixtureDbPassword = $null

    $receipt = [ordered]@{
        databaseFixture='generated isolated non-superuser role/schema migrated V1-V5'
        privateMediaRootRemoved=$mediaRootCleanup
        ownedRoleAndSchemaRemaining=$ownedRowsRemaining
        preexistingMetadataRestored=$aggregateCountsRestored
        backend=[ordered]@{launcherPid=$backendLauncherPid; launcherAbsent=$backendLauncherAbsent; listenerPid=$backendListenerPid; listenerPidAbsent=$backendListenerPidAbsent; port8080ListenerAbsent=$backendListenerAbsent}
        providerFixture=[ordered]@{pid=$fixturePid; processAbsent=$fixtureProcessAbsent}
        device=[ordered]@{serial=$deviceSerial; emulatorPid=$emulatorPid; processAbsent=$emulatorProcessAbsent; shutdownHelpersAbsent=$emulatorShutdownHelpersAbsent; adbConnectedDeviceAbsent=$emulatorDeviceAbsent; reverseBefore=$reverseBefore; reverseAfter=$reverseAfter; reverseRestored=$reverseRestored; fontScaleBefore=$oldFontScale; fontScaleAfter=$fontAfter; fontScaleRestored=($fontAfter -eq $oldFontScale); accelerometerRotationBefore=$oldAccelerometerRotation; accelerometerRotationAfter=$accelerometerAfter; userRotationBefore=$oldUserRotation; userRotationAfter=$userRotationAfter; rotationRestored=($accelerometerAfter -eq $oldAccelerometerRotation -and $userRotationAfter -eq $oldUserRotation)}
        durableDatabaseRetained=$true
        dpapiCredentialRetained=(Test-Path $credentialPath)
        preexistingEmulatorTouched=$false
    }
    $receipt | ConvertTo-Json -Depth 6 | Set-Content -Encoding utf8 (Join-Path $artifact 'cleanup-receipt.json')
    $ErrorActionPreference = $cleanupErrorActionPreference
    $cleanupPassed = $ownedRowsRemaining -eq '0|0' -and $aggregateCountsRestored -and $mediaRootCleanup -and $backendLauncherAbsent -and $backendListenerPidAbsent -and $backendListenerAbsent -and $fixtureProcessAbsent -and $settingsRestored -and $reverseRestored -and $emulatorProcessAbsent -and $emulatorShutdownHelpersAbsent -and $emulatorDeviceAbsent
    if (-not $cleanupPassed) { throw 'Owned E2E cleanup post-check failed; inspect cleanup-receipt.json.' }
}
