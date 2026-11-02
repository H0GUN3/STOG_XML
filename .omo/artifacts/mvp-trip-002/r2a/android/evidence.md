# R2a Android producer evidence

## Behavioral RED/GREEN

| Check | Result |
| --- | --- |
| `:app:testDebugUnitTest --tests com.stog.app.trip.InvitationInputTest` before URI parser support | RED: one assertion failed at `InvitationInputTest.java:13` because exact custom URI was rejected. |
| Same parser test after implementation | GREEN. |
| `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestJavaWithJavac` | GREEN, exit 0. |

## Final local gate

Executed in `C:/Users/GUN/AndroidStudioProjects/STOG_2` on 2026-10-01:

```text
./gradlew.bat :app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestJavaWithJavac
BUILD SUCCESSFUL
```

- JVM test result XML count: 6 suites, no failed final task.
- Debug APK exists: `app/build/outputs/apk/debug/app-debug.apk` (9,388,029 bytes).
- LSP diagnostics reported no diagnostics for changed Java sources.
- Lint final report: 0 errors; two pre-existing warnings remain for Gradle 9.5.0 availability and missing application icon. No new R2a lint warning remains.

## Device surface

`adb devices` returned no attached device. No emulator was started, no backend was started, and no instrumentation/device/screenshot/rotation/large-font observation was run. This producer evidence must not be treated as rendered UI verification.

## Secret boundary

No real account, credential, access/refresh token, invitation capability, backend URL, or user data was recorded. Tests use synthetic local values only.
