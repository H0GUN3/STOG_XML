# ANDROID-TRIPITEM-GET-001: protected current TripItem local read

## Status

`DONE / IMPLEMENTED / LOCAL FIXTURE + REAL BACKEND VERIFIED`

## Purpose and scope

Connect the existing `GET /api/trips/{trip_id}/trip-items/{trip_item_id}` to the signed-in Java/XML Android shell. This task does not change backend production code/schema/dependencies and does not add travel CRUD/list/delete/schedule/SSE, a provider, public exposure, or a redesign.

The screen keeps the existing auth shell. Because no navigation source currently supplies IDs, the signed-in surface adds clearly labelled trip UUID and trip-item UUID fields plus one read action. Inputs survive recreation/back through Android view state; credentials and response payloads do not enter saved state, logs, screenshots, or docs.

## Decisions

- Extend the existing platform-HTTP `AuthApiClient`; do not add a generic network/repository framework.
- Send the memory-only STOG access token as `Authorization: Bearer`. On one GET `401`, coalesce through the existing serialized session client, rotate once with the Keystore refresh token, and retry that GET once only.
- A refresh failure or second `401` clears local credentials, invalidates stale callbacks, and moves auth to signed-out/auth-ended. Logout, close, cancellation, and a newer read attempt invalidate older results; stale work cannot restore access credentials.
- Parse the existing flat success body and `{error:{code,message}}` envelope. Only `TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE` and `TRIP_ITEM_NOT_FOUND` receive distinct not-found states; malformed, transport, redirect, unexpected HTTP, and bounded-response failures are retryable without internal detail.
- The content state shows the returned IDs, state, schedule date/order nullable values, and plan version. It does not infer or mutate domain state.
- Real local verification uses an explicit literal-loopback Kakao provider fixture, a generated synthetic provider credential/subject, normal backend exchange/session/Bearer filters, and generated task-owned user/trip/place/item rows in the durable local development DB. It must not use a runtime principal bypass, real Kakao, the native key, public networking, or destructive database initialization.

## Deterministic acceptance

| ID | Acceptance |
| --- | --- |
| AC-01 | Canonical UUID input starts a loading state, sends one protected GET, and renders the exact success fields. |
| AC-02 | Blank/malformed/non-canonical IDs render invalid input without network access. |
| AC-03 | Missing or nonmember trip renders the same trip-not-found/non-disclosure state; an authorized missing item renders item-not-found. |
| AC-04 | Transport/unexpected/malformed/redirect/oversize failures render retryable error; retry starts a newer request and stale results cannot replace it. |
| AC-05 | A GET `401` performs at most one refresh and one GET retry. Concurrent/queued reads do not trigger duplicate refresh; refresh failure or retry `401` clears credentials and renders auth-ended/signed-out. |
| AC-06 | Logout, cancellation, close, and a newer attempt suppress prior callbacks and cannot revive memory or stored credentials. Existing redirect denial, missing-refresh unconfirmed logout, exchange-failure cleanup, and blank-local precedence remain passing. |
| AC-07 | Rotation and large-font device QA retain inputs and render loading/content/error states without clipping or secret/private data. Back exits the one-screen shell normally. |
| AC-08 | Emulator UI through `adb reverse` reaches the real local backend and isolated generated fixture for success, trip/nonmember 404, item 404, forced access expiry then one refresh/read retry, and logout with no stale content. Owned rows/processes/reverse/settings are removed; pre-existing DB/config/DPAPI/resources remain. |
| AC-09 | Original behavioral-first RED: `NOT_VERIFIED`; final LSP, Android unit/instrumentation, Lint and assemble gate: `VERIFIED`. |

AC-01 through AC-08 are `VERIFIED`. AC-09 is deliberately split: the preserved initial failure was a missing `TripItemReadState` scaffold compile failure, not a behavioral assertion, so original behavioral-first TDD is permanently `NOT_VERIFIED`. The 2026-09-29 review fixes do have fresh assertion/policy RED before their edits and targeted GREEN afterward. AC-07's large-font/recreation and Back observations are device evidence; loading and recoverable error rendering are also covered by the state/UI regressions. No claim extends to the `NOT_RUN` surfaces listed below.

## Changed paths

- `AuthApiClient` now performs the bounded protected GET, exact response/error parsing, serialized/coalesced refresh, one retry, and generation-based stale-result/credential invalidation. A 200 response becomes content only when both IDs are lowercase canonical UUIDs, date is a real canonical ISO date, and BASKET/SCHEDULED nullability and positive-order invariants match the API.
- `AuthStateMachine`, `AuthViewModel`, and `TripItemReadState` connect auth-ended behavior and one observable read state to the existing Activity-scoped screen owner.
- `MainFragment`, `fragment_main.xml`, and strings add the signed-in UUID lookup card and loading/content/invalid/trip-404/item-404/retryable/auth-ended rendering.
- Focused JVM/instrumentation regressions cover UUID validation, auth transition, success/error parsing, redirect/oversize denial, one-refresh boundary, queued reads, second 401, logout races, existing auth regressions, and UI input/error rendering.
- `app/build.gradle` applies `stogLocalInstrumentationFixture=true` overrides only to the debug build type and fails configuration for any release task using that flag. Normal release builds use normal configuration. `AuthViewModel` fixture install and clear entry points both fail closed in release bytecode before mutation.
- `.omo/artifacts/android-tripitem-get-001/` contains the bounded provider/DB fixture, launcher, RED/GREEN logs, sanitized result, cleanup receipt, and safe screenshots.

Backend production source, schema/migrations, dependencies, durable configuration, and existing user data were unchanged. The recalled login-footer request was explicitly cancelled and no footer/signup/ID lookup/password lookup UI was applied.

## Evidence and cleanup

- Historical chronology correction: `red-proof.json` now classifies the untouched `logs/red-unit.log` accurately as a scaffold compile failure. Original GET behavioral-first RED is `NOT_VERIFIED`; no retrospective behavioral failure was invented.
- Review RED/GREEN: current correct GET behavior first passed 6/6. New malformed-payload assertions then observed six incorrect `SUCCESS` outcomes before parsing edits and passed afterward as `RETRYABLE_ERROR`; valid BASKET/SCHEDULED fields are asserted exactly. Before the Gradle edit, fixture-flag `assembleRelease` incorrectly passed; afterward it fails closed while normal `assembleRelease` passes. See `review-fix/red-green.json`.
- LSP: the five review-changed Java files have 0 diagnostics.
- Final Android gate: 13 JVM tests and 17 Pixel_8 API-36 instrumentation tests, 0 failures/errors/skips; Lint 0 errors with the two pre-existing Gradle-version/application-icon warnings; `assembleDebug` passed. The first final attempt's API-26 Lint failure and API-23-compatible recovery are preserved. See `final-verification.json`, `review-fix/logs/final-android-gate.log`, and `review-fix/logs/final-android-gate-pass.log`.
- Release boundary: fixture-flag release fails configuration, normal unsigned release assembles, and inspected release bytecode makes both fixture mutation methods unconditionally throw before field mutation. No key value was printed and no provider was called.
- Real local surface: one instrumentation test passed through emulator UI, `adb reverse`, the existing Spring backend, and PostgreSQL. It used a generated synthetic credential with the backend's explicit literal-loopback Kakao fixture mode and normal exchange/session/Bearer filter. It verified successful item GET, missing and nonmember trip as the same 404, missing item 404, forced access expiry with exactly one refresh rotation and successful read retry, rotation/1.30 font, and logout with no stale card. See `real-local-e2e.json`, `logs/e2e-instrumentation.log`, and six inspected files under `screenshots/`.
- Refresh evidence: one auth session was revoked by logout; two refresh rows existed with exactly one used row, proving one rotation. Provider token-info and user-info were each called once. No credential/token value was written to evidence.
- Cleanup: fresh `cleanup.json` records owned rows `0|0|0|0`, aggregate counts restored, exact backend launcher/listener PIDs absent, port 8080 listener absent, provider fixture PID absent, owned emulator process/connected device absent, reverse list restored, and font plus accelerometer/user rotation restored before emulator shutdown. The durable DB and DPAPI credential remain; the pre-existing unauthorized physical device was not touched. Failed launcher attempts and their exact recovery are preserved in `review-fix/runtime-failures.json`, not relabeled as successful receipts. Back evidence remains in `logs/back-behavior.log`.
- Unchanged backend suite was intentionally `NOT_RUN`. Real Kakao/provider calls and native key use, public/production HTTPS, physical device, API 23/37 runtime, TalkBack, travel CRUD/list/schedule/SSE, and release operations remain `NOT_RUN`.
