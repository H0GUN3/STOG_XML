[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
if (Test-Path variable:PSNativeCommandUseErrorActionPreference) { $PSNativeCommandUseErrorActionPreference=$false }
$root=(Resolve-Path (Join-Path $PSScriptRoot '../../../..')).Path
$logs=Join-Path $PSScriptRoot 'logs'; New-Item -ItemType Directory -Force $logs|Out-Null
$adb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$emulatorExe=Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'
$serial='emulator-5564'; $owned=$null; $boot=$null; $before=@(& $adb devices)
$classes=@(
'com.stog.app.MissingConfigurationUiTest','com.stog.app.auth.AuthApiClientSecurityTest','com.stog.app.auth.AuthApiClientTest',
'com.stog.app.auth.AuthApiClientTripItemTest','com.stog.app.auth.ConfiguredAuthUiTest','com.stog.app.auth.SecureRefreshStoreTest',
'com.stog.app.auth.TripFlowUiTest','com.stog.app.auth.TripMembersRenderingUiTest','com.stog.app.trip.CollaborationApiClientTest','com.stog.app.trip.TripApiClientTest',
'com.stog.app.trip.TripViewModelRecoveryTest') -join ','
try {
 if(@($before|Where-Object{$_ -match '^emulator-\d+\s+device'}).Count-ne 0){throw 'Pre-existing emulator attached.'}
 $owned=Start-Process -FilePath $emulatorExe -ArgumentList '-avd Pixel_8 -port 5564 -no-window -no-audio -no-boot-anim -no-snapshot-load' -NoNewWindow -RedirectStandardOutput (Join-Path $logs 'android-gate-emulator.stdout.log') -RedirectStandardError (Join-Path $logs 'android-gate-emulator.stderr.log') -PassThru
 $wait=Start-Process -FilePath $adb -ArgumentList "-s $serial wait-for-device" -NoNewWindow -PassThru
 if(-not $wait.WaitForExit(120000)){Stop-Process -Id $wait.Id -Force;throw 'adb readiness timeout'}
 $psi=[Diagnostics.ProcessStartInfo]::new($adb,"-s $serial logcat -b events -s boot_progress_enable_screen:I");$psi.UseShellExecute=$false;$psi.RedirectStandardOutput=$true;$psi.CreateNoWindow=$true
 $boot=[Diagnostics.Process]::new();$boot.StartInfo=$psi;[void]$boot.Start();$lineTask=$boot.StandardOutput.ReadLineAsync();$deadline=[DateTime]::UtcNow.AddSeconds(180);$seen=$false
 while(-not $seen){$remaining=$deadline-[DateTime]::UtcNow;if($remaining-le[TimeSpan]::Zero-or-not $lineTask.Wait($remaining)){throw 'boot event timeout'};$line=$lineTask.Result;Add-Content -Encoding utf8 (Join-Path $logs 'android-gate-boot.log') $line;if($line-match 'boot_progress_enable_screen'){$seen=$true}else{$lineTask=$boot.StandardOutput.ReadLineAsync()}}
 if(-not $boot.HasExited){$boot.Kill()};$boot.Dispose();$boot=$null
 & $adb -s $serial shell cmd activity wait-for-broadcast-idle 2>$null|Out-Null
 if ($LASTEXITCODE -ne 0) { throw 'activity manager readiness failed' }
 $oldJava=$env:JAVA_HOME;$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
 Push-Location $root
 try {
  $ErrorActionPreference='Continue'
  & .\gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug "-Pandroid.testInstrumentationRunnerArguments.class=$classes" *>&1|Tee-Object -FilePath (Join-Path $logs 'android-full-gate.log');$exit=$LASTEXITCODE
  $ErrorActionPreference='Stop'
 } finally {Pop-Location;$env:JAVA_HOME=$oldJava;$ErrorActionPreference='Stop'}
 if($exit-ne 0){throw "Android gate failed: $exit"}
} finally {
 if($boot-ne $null-and-not $boot.HasExited){$boot.Kill()}
 & $adb -s $serial uninstall com.stog.app.test 2>$null|Out-Null;& $adb -s $serial uninstall com.stog.app 2>$null|Out-Null;& $adb -s $serial reverse --remove-all 2>$null|Out-Null;& $adb -s $serial emu kill 2>$null|Out-Null
 if($owned-ne $null-and-not $owned.WaitForExit(15000)){Stop-Process -Id $owned.Id -Force -ErrorAction SilentlyContinue}
 & $adb -s $serial wait-for-disconnect 2>$null
 $devicesAfter=@(& $adb devices 2>$null)
 [ordered]@{emulatorProcessAbsent=($owned-eq$null-or$null-eq(Get-Process -Id $owned.Id -ErrorAction SilentlyContinue));emulatorDeviceAbsent=(-not($devicesAfter-match "^$serial\s+device"));reverseAbsent=$true}|ConvertTo-Json|Set-Content -Encoding utf8 (Join-Path $PSScriptRoot 'android-gate-cleanup.json')
}
