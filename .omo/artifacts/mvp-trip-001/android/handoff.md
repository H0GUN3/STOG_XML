# MVP-TRIP-001 Android handoff

## Logout/list teardown correction delivered

- `TripListFragment.captureAnchor()` now ignores `AUTH_ENDED`, `NO_POSITION`, and positions outside the adapter's current item count. Every caller, including `onDestroyView`, therefore shares the same stale-layout guard and cannot read or re-save a row after the adapter has been cleared.
- Deferred anchor restoration is now retained as a lifecycle-owned pre-draw listener. `AUTH_ENDED` and `onDestroyView` remove it; if it does run, it first verifies that the saved anchor still exists at the captured adapter position before scrolling. Cleared logout identity cannot be reapplied.
- Added `TripFlowUiTest.authEndedAfterRenderedContentClearsAnchorAndDestroysListWithoutCrash`, which waits on exact signed-in/content/pre-draw signals, renders one row, clears through `authEnded()`, destroys the list while RecyclerView still reports position 0, and asserts that the private anchor remains null. It uses bounded signals and no sleeps.

Behavioral RED: `android/logout-fix/red.log` failed with the verified `IndexOutOfBoundsException` at `TripAdapter.tripId(0) -> captureAnchor() -> onDestroyView()`.

Focused GREEN command:

```powershell
./gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:connectedDebugAndroidTest :app:assembleDebug '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.auth.TripFlowUiTest,com.stog.app.auth.ConfiguredAuthUiTest'
```

Result: 5/5 instrumentation tests passed and `assembleDebug` passed in one run (`android/logout-fix/focused-green.log`). LSP diagnostics were zero for both changed Java files. The owned Pixel_8 emulator, app/test packages, reverse mappings, and three screenshots created by the focused configured-auth tests were removed; font scale and rotation settings were restored before shutdown. No backend, canonical contract, key/provider, database, real-user, physical-device, or feature change was made.

Changed paths in this correction:

- `app/src/main/java/com/stog/app/trip/TripListFragment.java`
- `app/src/androidTest/java/com/stog/app/auth/TripFlowUiTest.java`
- `.omo/artifacts/mvp-trip-001/android/{handoff.md,logout-fix/*}`

The final verifier still owns the full Android gate and real-local flow. Original historical API/UI preimplementation RED remains `NOT_VERIFIED`; this correction does not rewrite that history.

## Final integrated Back fix delivered

- `MainActivity` now sets its static `MainFragment` as the support FragmentManager primary navigation fragment. AndroidX's lifecycle-aware `OnBackPressedDispatcher` therefore reaches the nested trip child stack; it consumes Back only when that stack has an entry, while root-list Back retains normal Activity exit.
- `TripListFragment` performs one fresh load when Back restores it in stale `IDLE`/`LOADING`. This closes the deterministic create/list race exposed by the regression without reloading preserved CONTENT pages or disturbing anchor restoration.
- Create success still produces one detail-to-list stack entry. After Activity recreation, real Espresso Back returns to a list containing the new trip exactly once; a second root Back destroys the Activity. Draft Back and prior page/anchor behavior remain on the same native FragmentManager path.
- The pagination/SavedState/parser/auth/deadlock corrections from prior rounds remain unchanged.

## Exact tests and evidence

Behavioral RED: `.omo/artifacts/mvp-trip-001/android/back-fix-red.log`

```text
TripFlowUiTest.emptyListCreateAndEmptyBasketRenderAsOneProductJourney
androidx.test.espresso.NoActivityResumedException: Pressed back and killed the app
BUILD FAILED
```

The retained test was renamed after broadening its assertions:

```text
com.stog.app.auth.TripFlowUiTest
  createDetailRecreationSystemBackReturnsOneTripThenRootBackFinishes
  loadedPagesAndScrollAnchorAreWiredThroughTheRenderedList
com.stog.app.auth.ConfiguredAuthUiTest
  configuredFixtureRendersSuccessLogoutAndErrorStates
  configuredFixtureRendersProviderCleanupFailureAndRetry
```

Final focused command:

```powershell
./gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:connectedDebugAndroidTest :app:assembleDebug '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.auth.TripFlowUiTest,com.stog.app.auth.ConfiguredAuthUiTest'
```

Result: 4/4 instrumentation tests finished, `BUILD SUCCESSFUL`; production/test Java compiled and `assembleDebug` passed. Changed Java LSP diagnostics were 0. Log: `back-fix-affected-green.log`.

Fresh native render: `back-fix-list-after-back.png` (Pixel_8 API 36.1, 1080x2400) shows exactly one `제주 여행` row after system Back and was inspected. This is fixture UI evidence, not backend proof. Full mechanism/evidence: `back-fix-evidence.md`.

## Changed paths in this correction

- `app/src/main/java/com/stog/app/MainActivity.java`
- `app/src/main/java/com/stog/app/trip/TripListFragment.java`
- `app/src/androidTest/java/com/stog/app/auth/TripFlowUiTest.java`
- `.omo/artifacts/mvp-trip-001/android/{handoff.md,back-fix-*}`

No Gradle dependency, backend, canonical document, migration, provider/account/config, DPAPI, public/TLS, or service change.

## Cleanup and verification boundary

`back-fix-cleanup-receipt.json` records owned emulator stopped, app/test packages absent, reverse empty, font scale `1.0`, auto-rotation `1`, user rotation `0`, owned media removed, and pre-existing media preserved.

The dependent verifier must rerun the full Android/real-backend integrated acceptance because production changed. This producer did not rerun the full canonical suite or backend and does not claim full MVP/R1 verification. Original historical API/UI preimplementation RED remains `NOT_VERIFIED`; only this correction's Back RED is verified.
