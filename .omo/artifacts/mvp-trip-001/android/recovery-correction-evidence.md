# MVP-TRIP-001 Android recovery correction evidence

## Scope

- `GET /api/trips` maps `400 INVALID_CURSOR` to a distinct outcome. `TripViewModel.loadNextTrips()` discards the stale continuation and starts a fresh first-page read; ordinary errors still retain the cursor for user-directed retry.
- Basket `400 INVALID_CURSOR` and `409 PLAN_VERSION_CONFLICT` each trigger one fresh detail/basket read for the selected trip. A second recovery failure is rendered as `ERROR`, avoiding an automatic loop.
- No navigation, XML, theme, dependency, backend, provider, account, TLS, or public-runtime change was made.

## Genuine RED

```powershell
./gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.trip.TripViewModelRecoveryTest'
```

`recovery-correction-red.log` records two behavioral timeouts on owned `Pixel_8` AVD (`emulator-5572`, API 36), after observers were subscribed before the actions:

- an invalid continuation cursor remained in the list error path instead of producing a second fresh first-page request;
- a first basket `PLAN_VERSION_CONFLICT` remained detail `ERROR` instead of producing a second detail/basket read.

## GREEN

```powershell
./gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.trip.TripViewModelRecoveryTest,com.stog.app.trip.TripApiClientTest'
```

`recovery-correction-green.log` records 6/6 passing instrumentation tests. `TripViewModelRecoveryTest` verifies one fresh list request after `INVALID_CURSOR`, one fresh detail/basket read after `PLAN_VERSION_CONFLICT`, and the same recovery after basket `INVALID_CURSOR`. `TripApiClientTest` retains the legacy-null/detail and malformed-200 parser guards.

LSP diagnostics for `AuthApiClient.java`, `TripViewModel.java`, and `TripViewModelRecoveryTest.java` were clean before GREEN.

## Process recreation limitation

`SavedStateHandle` persists only the draft/pending tuple, selected ID, and anchor ID/offset. It does not persist loaded list pages, opaque cursors, or full response payloads. After process death, `TripListFragment` performs its ordinary fresh first-page load; it does not page forward to the saved anchor. Therefore loaded pages and exact anchor restoration after process death are `NOT_IMPLEMENTED / NOT_VERIFIED`, not preserved-state behavior.
