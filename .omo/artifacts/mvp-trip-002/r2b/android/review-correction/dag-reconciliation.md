# R2b Android correction DAG reconciliation

## RESULT

**PASS - independent read-only reconciliation.** The failed prior DAG Android node produced no work; the completed `st_01a10efd` correction is present in current source and is supported by executable machine evidence. This audit did not run Gradle, start an emulator, mutate backend/app source, or update canonical documents.

## Claim-to-source and result mapping

- **Portrait-only STOG host:** `app/src/main/AndroidManifest.xml` sets only `.MainActivity` to `android:screenOrientation="portrait"`. The current debug merged manifest contains `com.stog.app.MainActivity` with the same value. The Kakao callback declaration has no orientation override, and system picker ownership is external. `TripMembersRenderingUiTest.dRemainsPortraitAndReachesEveryMemberControlUnderBothLandscapeDeviceRotations` applies device rotations 1 and 3 and asserts portrait; `TripFlowUiTest` separately calls `ActivityScenario.recreate()`. Canonical portrait records exist in `docs/PLAN.md`, `docs/ANDROID.md`, and `docs/tasks/MVP-TRIP-002.md:456` and do not claim landscape STOG UI.
- **Stale selection and private-image clearing:** `TripDetailFragment` binds delayed preparation to selection generation plus selected trip ID, disposes stale prepared bitmap/file input, increments generations on view destruction, and invalidates/recycles fragment-owned downloaded bitmaps for missing/error/auth-ended/replacement/view-destruction paths. `TripViewModel.authEnded()` cancels cover requests and deletes/recycles pending private input. `TripFlowUiTest` deterministically covers delayed preparation after auth end, view destruction and trip change, plus rendered-cover clearing after auth end and membership denial using pre-subscribed latches and bounded waits.
- **Cover preparation correctness:** `CoverInput.copyAndPreview` deletes the copied private file and recycles a decoded bitmap after EXIF/decode failure. Its four mirrored orientation branches are exercised by `CoverInputTest` with asymmetric corner colors and exact dimensions, not dimensions alone.
- **Handoff-listed R2b paths:** `app/build.gradle` adds only ExifInterface for this image path. `AuthApiClient` contains bounded authenticated multipart cover commands, bounded no-redirect image reads and stale request cancellation. `TripViewModel`, `TripDetailFragment`, `TripListFragment`, detail/list layouts, dimensions and strings contain the stated cover state/UI behavior. `TripApiClientTest`, `TripFlowUiTest`, and `CoverInputTest` contain the stated transport, lifecycle and image regressions. No generic media framework, camera permission, WorkManager, or orientation rule for external hosts was found in these paths.

## Machine evidence reviewed

- Behavioral RED: `logs/focused-behavioral-red-3.log`, exit `1`, 12 tests with exactly the two old-drawable clearing failures; the other ten correction tests passed.
- Focused GREEN: `focused-green-results.xml`, exit `0`, 12/12 with failure/error/skip `0/0/0`.
- Portrait focused GREEN: `portrait-focused-results.xml`, exit `0`, 14/14 with failure/error/skip `0/0/0`.
- Final gate: `logs/android-portrait-final-gate.log` and `android-portrait-final-gate-exit-code.txt`, exit `0`, `BUILD SUCCESSFUL`; JVM machine XML totals 16/16 and `android-portrait-final-instrumentation-results.xml` records 44/44 with failure/error/skip `0/0/0`. `lintDebug` and `assembleDebug` completed.
- Lint: current `app/build/reports/lint-results-debug.txt` records `0 errors, 4 warnings`: Gradle update, missing app icon, locked orientation, and Android 16 fixed-orientation limitation. The latter two bound the portrait claim to the verified Pixel_8 phone configuration rather than universal large-screen/multi-window behavior.
- Historical failure preservation: `android-final-instrumentation-results.xml` records an earlier 44-test attempt with one member-rendering failure; it is superseded by, not substituted for, the later 44/44 portrait-final XML.
- Cleanup: `cleanup-before-emulator.json` records font `1.0`, auto rotation `1`, user rotation `0`, animation scales `1/1/1`, no packages/reverse/device screenshots; `private-temp-before-cleanup.txt` is empty, `emulator-cleanup.json` records absent emulator processes, `devices-after-cleanup.txt` has no attached device, and the crash scan is empty.
- Current portrait screenshots are 1080x2400 and were inspected for preview and cleared auth/membership states. Historical landscape screenshots are not used as portrait proof; manifest merge and rotation tests are the machine proof.

## Inspected source versions and stability

The following SHA-256 values were sampled before and after review and remained unchanged:

- Manifest `74e4824...9384f`; `AuthApiClient` `c56ee391...f194`; `CoverInput` `ee911100...418c`; `TripViewModel` `2ef1f002...ffda`; `TripDetailFragment` `a0edb26a...b8067`; `TripListFragment` `1fba6b39...10ce`.
- `TripFlowUiTest` `5632075a...55d`; `TripMembersRenderingUiTest` `52651001...809`; `CoverInputTest` `83517ecb...ab1a`; `TripApiClientTest` `40ff8014...d02e`.
- Handoff-listed Gradle/layout/value files were also stable during the audit.

With no Git directory, this receipt does not invent a repository diff from timestamps. Recently timestamped non-handoff paths were not attributed to this correction and are not treated as correction evidence.

## Explicit limits

This is a source/evidence bridge, not a fresh execution. The Android HTTP fixture is not actual V1-V5 backend/PostgreSQL E2E. Real picker -> corrected backend private-file behavior, two-user authorization/replay/recovery, API 23/37 hardware, large-screen orientation policy, real Kakao, public network and provider/account behavior remain outside this receipt and belong to the next verifier. R2b remains in-progress until that real E2E acceptance completes.
