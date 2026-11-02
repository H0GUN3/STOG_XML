# MVP-TRIP-001 final integrated Back fix evidence

## Repaired cause

`MainActivity` inflated the static `MainFragment` host but never registered that fragment as the support `FragmentManager` primary navigation fragment. AndroidX therefore could not route the Activity `OnBackPressedDispatcher` callback through `MainFragment` into its child trip back stack. The real verifier and the new instrumentation regression both observed `Espresso.pressBack()` finish the Activity while the recreated detail child stack contained exactly one entry.

The regression also made a deterministic existing race visible: create success starts a list refresh, then detail/basket requests advance the shared authenticated transport read generation. The list response can consequently be discarded while `TripViewModel.list` remains `LOADING`. `TripListFragment` now performs one fresh list load only when a restored Back destination is still `IDLE` or `LOADING`; existing CONTENT pages/anchor are not reloaded.

## Production change

- `MainActivity` registers the root fragment with `setPrimaryNavigationFragment(...).commitNow()`. AndroidX's lifecycle-aware built-in dispatcher consumes Back only while the nested child stack is non-empty; with zero child entries its callback is disabled and normal Activity Back remains.
- `TripListFragment` recovers a stale create refresh on Back by reloading only `IDLE`/`LOADING`; existing CONTENT/EMPTY/error state behavior is unchanged.

## Behavioral RED

Command:

```text
./gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.auth.TripFlowUiTest#emptyListCreateAndEmptyBasketRenderAsOneProductJourney'
```

Before the production fix, the test failed at the actual system action:

```text
androidx.test.espresso.NoActivityResumedException: Pressed back and killed the app
at com.stog.app.auth.TripFlowUiTest... (TripFlowUiTest.java:120)
BUILD FAILED
```

Full log: `back-fix-red.log`.

## Focused GREEN

Final affected command:

```text
./gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:connectedDebugAndroidTest :app:assembleDebug '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.auth.TripFlowUiTest,com.stog.app.auth.ConfiguredAuthUiTest'
```

Result: 4 instrumentation tests finished, `BUILD SUCCESSFUL`; debug Java compilation and `assembleDebug` succeeded. Full log: `back-fix-affected-green.log`.

The retained regression `TripFlowUiTest.createDetailRecreationSystemBackReturnsOneTripThenRootBackFinishes` verifies through real Espresso/system Back:

1. create success leaves exactly one detail-to-list child entry;
2. Activity recreation retains that entry;
3. `Espresso.pressBack()` returns to the rendered list;
4. the created trip appears exactly once;
5. the child stack becomes zero;
6. `Espresso.pressBackUnconditionally()` at root destroys the Activity, observed by a lifecycle callback subscribed before the action.

`TripFlowUiTest.loadedPagesAndScrollAnchorAreWiredThroughTheRenderedList` now also uses Espresso Back instead of direct `popBackStackImmediate()`.

Changed Java diagnostics: 0 for `MainActivity.java`, `TripListFragment.java`, and `TripFlowUiTest.java`.

Fresh rendered evidence: `back-fix-list-after-back.png` (1080x2400 PNG) was pulled from the final focused test and inspected. It shows the signed-in `내 여행` list with exactly one `제주 여행` row after system Back; no redesign was made.

## Boundary and cleanup

- No backend, canonical docs, migration, dependency, external provider, account, DPAPI, public network, TLS, or service change.
- No real backend rerun and no full canonical Android gate; dependent verifier owns the full rerun because production changed.
- Cleanup: `back-fix-cleanup.log`, `back-fix-cleanup-receipt.json`. Owned emulator stopped, package/reverse counts are zero, font/rotation restored, owned test media removed, pre-existing media preserved.
- Original R1 API/UI preimplementation RED remains `NOT_VERIFIED`; this correction's Back RED is genuine and does not rewrite that history.
