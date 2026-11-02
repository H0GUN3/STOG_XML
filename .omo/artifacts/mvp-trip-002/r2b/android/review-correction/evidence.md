# R2b Android review correction and portrait-only evidence

## Result

- `app/src/main/AndroidManifest.xml` locks the only STOG-owned screen host, `.MainActivity`, with `android:screenOrientation="portrait"`. The Kakao SDK callback Activity and system document picker are unchanged.
- `TripDetailFragment` now invalidates late image callbacks and clears/recycles fragment-owned downloaded private bitmaps on loading, missing/error terminal detail, auth end, membership denial, replacement and view destruction.
- `CoverInputTest` validates EXIF cleanup and exact asymmetric corner placement for horizontal/vertical flip, transpose and transverse orientations.
- Delayed picker preparation is deterministically rejected and its private temp removed after logout, view destruction and trip change.
- Direction-based recreation tests were replaced by device rotations 1 and 3 that assert STOG remains portrait. Independent `ActivityScenario.recreate()` coverage remains.

## Behavioral RED/GREEN

- RED: `logs/focused-behavioral-red-3.log` ran 12 tests; the two visible-cover denial tests failed because the old `BitmapDrawable` remained. The other 10 tests, including EXIF cleanup, mirrored pixel placement and all delayed-selection paths, passed.
- GREEN: `focused-green-results.xml` records 12/12 pass after the fragment-owned bitmap invalidation fix.
- Portrait focused GREEN: `portrait-focused-results.xml` records 14/14 for `TripFlowUiTest` plus `TripMembersRenderingUiTest`, including both device landscape rotations while `MainActivity` remained portrait.

## Final Android gate

Exact command:

```text
./gradlew --no-daemon -PstogLocalInstrumentationFixture=true :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug -Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.MissingConfigurationUiTest,com.stog.app.auth.AuthApiClientSecurityTest,com.stog.app.auth.AuthApiClientTest,com.stog.app.auth.AuthApiClientTripItemTest,com.stog.app.auth.ConfiguredAuthUiTest,com.stog.app.auth.SecureRefreshStoreTest,com.stog.app.auth.TripFlowUiTest,com.stog.app.auth.TripMembersRenderingUiTest,com.stog.app.trip.CollaborationApiClientTest,com.stog.app.trip.CoverInputTest,com.stog.app.trip.TripApiClientTest,com.stog.app.trip.TripViewModelRecoveryTest
```

- `logs/android-portrait-final-gate.log`: exit 0.
- JVM: 16/16, failure/error/skip 0.
- Pixel_8 API 36.1 explicit instrumentation: 44/44, failure/error/skip 0; machine result `android-portrait-final-instrumentation-results.xml`.
- Lint and assemble: PASS. Lint has four warnings and no errors. Two warnings explicitly document that fixed orientation can be ignored on Android 16 large-screen/multi-window environments; the verified guarantee is the owned Pixel_8 phone configuration, not every large-screen host policy.
- Merged manifest contains `com.stog.app.MainActivity android:screenOrientation="portrait"`; `final-observations.txt`.
- Java LSP diagnostics: zero findings on all changed Java production/tests. XML LSP is unavailable; manifest merge, instrumentation, Lint and assemble validate the XML.
- Crash scan: empty; `logs/final-crash-scan.log`.

## Visual inspection and cleanup

- Inspected portrait light/dark preview/content, auth-ended clearing and membership-denied clearing under `screenshots/`; hashes and dimensions are in `visual-and-static-receipt.txt`. Historical landscape producer captures are superseded and are not current portrait evidence.
- Final cleanup restored font `1.0`, auto rotation `1`, user rotation `0`, animation scales `1/1/1`; removed app/test packages, reverse mappings, on-device generated screenshots and private temp; terminated owned emulator PIDs 23392/25652. Receipts: `cleanup-before-emulator.json`, `emulator-cleanup.json`, `devices-after-cleanup.txt`.

## Boundary

The HTTP UI fixture is Android-only and is not real V1-V5 backend E2E. No backend, provider, account, credential, public network or real-user operation was performed by this Android lane.
