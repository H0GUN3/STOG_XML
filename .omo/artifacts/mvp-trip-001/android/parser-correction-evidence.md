# MVP-TRIP-001 Android parser correction evidence

## Scope

- `AuthApiClient` separates create/replay's required `name`/`region_label` from detail's nullable legacy metadata while preserving the flat 10-key detail/create shape and required `as_of_date`.
- Basket item parsing now requires exactly `trip_item_id`, `place_id`, `state`, `schedule_date`, `sort_order`, and `created_at`; a same-length unknown replacement cannot be treated as a null optional field.
- No XML, theme, dependency, backend, provider, account, TLS, or public-runtime change was made in this correction.

## Genuine RED

Command:

```powershell
./gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.trip.TripApiClientTest'
```

`parser-correction-red.log` records two behavioral failures on owned `Pixel_8` AVD (`emulator-5572`, API 36):

- `legacyDetailAllowsNullMetadataButCreateStillUsesTheSameExactWire`: expected `SUCCESS`, got `RETRYABLE_ERROR`.
- `basket200WithMissingRequiredItemKeysIsNeverContent`: expected `RETRYABLE_ERROR`, got `SUCCESS` when `schedule_date` was absent and replaced by an unknown sixth key.

This is the only new valid RED chronology. The original R1 API/UI preimplementation RED evidence remains `NOT_VERIFIED`, as already recorded.

## GREEN

The same command completed successfully after the correction: 3/3 `com.stog.app.trip.TripApiClientTest` instrumentation tests passed. See `parser-correction-green.log`.

Changed-file LSP diagnostics were clean before GREEN:

- `app/src/main/java/com/stog/app/auth/AuthApiClient.java`
- `app/src/androidTest/java/com/stog/app/trip/TripApiClientTest.java`

The loopback fixture is local, test-only, and closes its socket/executor in each test. It is not backend or provider evidence.
