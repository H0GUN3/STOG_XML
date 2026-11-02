# Android root-cause recovery evidence

## Root cause

`ConfiguredAuthUiTest` held the fake restore callback until after `ActivityScenario.launch()` returned. On the owned API 36.1 AVD, launch did not return while the RESTORING surface's indeterminate progress indicator continuously rendered frames, so the callback needed to leave RESTORING was unreachable.

Observed before the fix:

- `root-cause-focused-repro.log`: the exact single test started and emitted no finish before the 150-second bounded runner termination.
- `root-cause-hung-screen.png`: the live app was visibly stuck on `Checking sign-in` / RESTORING.
- `root-cause-activity-state.log`: `MainActivity` was RESUMED, visible, reported drawn and idle.
- `root-cause-gfxinfo.log`: 5,627 frames continued rendering while the test made no progress.
- `root-cause-java-thread-dump.log`: after the bounded SIGQUIT evidence action, Espresso initialization at `ConfiguredAuthUiTest.java:45` proceeded and the complete success/logout/error journey finished immediately. No credential or sensitive payload was logged.

The test now launches on a bounded executor, subscribes to the exact fake-session restore request before launch, verifies the RESUMED activity renders RESTORING on the main thread, completes restore, and awaits launch completion with a 10-second bound. The old success, TripItem error, logout and auth-error assertions remain.

## Behavioral RED

- `root-cause-restoration-red.log`: `processRestorationReloadsSavedPageDepthUntilTheStableAnchorIsPresent` failed with `restoration must reload through the saved page depth` before `TripViewModel` persisted/reloaded page depth.
- Pre-fix runtime emitted `RecyclerView: No layout manager attached; skipping layout` in `root-cause-java-thread-dump.log`, proving the rendered list could not supply usable scroll/anchor behavior.
- The new rendered-list test then failed during the first wiring attempt because scrolling to the end did not request continuation (`scrolling to the rendered page end must request the continuation`). The listener was corrected to the actual RecyclerView scroll owner. This intermediate raw Gradle log was overwritten by the subsequent focused rerun; this chronology is recorded here and is not represented as historical preimplementation evidence.
- Original R1 API/UI preimplementation RED remains `NOT_VERIFIED`; this correction does not retroactively claim it.

## Implemented correction

- `TripViewModel` persists only bounded page depth (maximum 50), anchor ID/offset and existing small state; it does not save responses, cursors or tokens.
- Process restoration starts at page one and follows newly issued cursors until the saved depth is reached or pages end. One invalid restoration cursor causes one fresh bounded restart; a repeated invalid cursor terminates in ERROR.
- `TripListFragment` now has an actual `LinearLayoutManager`, requests continuation from real RecyclerView end-scroll, captures stable trip ID plus decorated pixel offset, restores it before draw, and uses `DiffUtil` instead of remove/reinsert-all updates. Create/detail Back uses the existing FragmentManager stack.
- Auth-ended clears page depth and anchor together with the other private trip state.

## RESTORING saved-state correction

Parent source review identified that `MainFragment.render` called `TripViewModel.authEnded()` for every non-signed-in state. That included transient `RESTORING`, so a recreated Fragment could restore draft/pending/anchor/page depth and immediately erase it before secure refresh completed.

`ConfiguredAuthUiTest.restoredTripStateSurvivesAuthRestoringHiddenAndClearsOnLogout` exercises the real Activity/Fragment path rather than directly constructing a `TripViewModel`: it seeds state through the live Fragment-owned model, clears the Activity and Fragment ViewModelStores to force replacement model instances, recreates the Activity, and holds the second auth restore callback pending. `restoring-state-red.log` failed because the replacement model's restored draft was already empty. This is a bounded process-loss simulation through Android SavedStateHandle/auth restoration; it is not an OS process-kill harness.

The corrected boundary keeps `trip_product_container` GONE during RESTORING but does not clear small private state. Clear occurs only for `MISSING_CONFIGURATION`, `SIGNED_OUT`, `LOGGING_OUT`, and `SIGNED_OUT_REVOKE_UNCONFIRMED`. The same regression completes restore successfully, verifies a distinct restored model retains draft/anchor, then logs out and verifies draft/anchor are cleared and remain hidden. `restoring-state-green.log` is the focused GREEN.

## Focused GREEN

Command:

```powershell
./gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:testDebugUnitTest --tests com.stog.app.trip.TripFlowStateTest :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.auth.ConfiguredAuthUiTest,com.stog.app.trip.TripViewModelRecoveryTest,com.stog.app.trip.TripApiClientTest,com.stog.app.auth.TripFlowUiTest' :app:assembleDebug
```

Latest result: `BUILD SUCCESSFUL` in `restoring-final-focused-green.log`.

- JVM: `com.stog.app.trip.TripFlowStateTest` 2/2, failures/errors/skips 0.
- Instrumentation: 11/11, failures/errors/skips 0.
  - `com.stog.app.auth.ConfiguredAuthUiTest`: 2
  - `com.stog.app.trip.TripViewModelRecoveryTest`: 4
  - `com.stog.app.trip.TripApiClientTest`: 3
  - `com.stog.app.auth.TripFlowUiTest`: 2
- Java LSP: no diagnostics in all changed Java files. XML LSP is unavailable in this workstation; Android resource compile and `assembleDebug` passed.
- Parser regression mapping remains `com.stog.app.trip.TripApiClientTest`; nullable legacy detail and exact basket key guards passed in the focused 3 tests.

This is local loopback fixture evidence, not actual backend/provider/public/TLS proof and not the full canonical Android gate.
