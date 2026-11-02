# NATIVE-AUTH-UI-001: native username/password auth and real app entry

## 1. Identity and status

2026-11-02 splash theme lint fix: moved the two API-31 splash items out of `values/themes.xml` and `values-night/themes.xml` into new `values-v31` and `values-night-v31` theme overlays on a renamed `Base.Theme.STOG`. Process-local JDK 17 `:app:assembleDebug :app:lintDebug --console=plain`: exit 0. Pixel_8 `adb install -r` Success, cold start ok, no crash-log lines for the app, signed-in Home rendered in the light theme. The splash itself, dark theme, tests and API below 31 were NOT_RUN.

2026-11-02 native auth communication-failure repair (user authorized):
the approved `127.0.0.1:8080` backend had no listener while Pixel_8 and its
`adb reverse tcp:8080 tcp:8080` mapping were present. A native auth exception
called `clearLocal(generation)`, incrementing `authGeneration`; its own
`completeNativeAuth` then rejected the failure callback as stale, leaving
signup/login loading indefinitely despite 10-second socket timeouts.
Only native auth exception cleanup now clears credentials under the existing
generation guard without advancing that generation. Existing protected-request
invalidation, close/logout cancellation and completion generation checks remain.
`NativeAuthFailureTest.communicationFailureExitsSignupAndLoginLoading` uses
isolated preferences and loopback connection resets, with event latches and no
real backend credentials or account creation.

The first test-APK assembly was blocked by pre-existing `ConfiguredAuthUiTest`
references to removed `auth_signup_mode` / `auth_login_mode` IDs. No unrelated
test was edited. The artifact-only
`.omo/artifacts/native-auth-ui-20261102/android/focused-native-auth.init.gradle`
limits test-APK compilation to this single regression. With process-local JDK
17, `./gradlew.bat -I` that script followed by
`:app:assembleDebug :app:assembleDebugAndroidTest --console=plain` exited 0.
The existing `backend/scripts/Start-LocalBackend.ps1` launcher restored a
loopback-only listener (PID 39140); anonymous `GET /api/trips` returned 401.
No backend source, API, schema, account or operator configuration was changed.
`adb -s emulator-5554 install -r` succeeded for both
`app/build/outputs/apk/debug/app-debug.apk` and the focused
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, without
clearing app data. `adb -s emulator-5554 shell am instrument -w -e class
com.stog.app.auth.NativeAuthFailureTest#communicationFailureExitsSignupAndLoginLoading
com.stog.app.test/androidx.test.runner.AndroidJUnitRunner` exited 0:
`OK (1 test)`, covering both signup and login transport failures. The test used
its own preferences, not the user's session store. No sleeps or new dependency.
After `adb -s emulator-5554 shell am start -W -n com.stog.app/.MainActivity`,
UI metadata showed enabled login/signup-entry actions and no visible
`auth_progress`. No input values or screenshots were emitted. App update may
reset the current form; no draft values were read or copied and no auth action
was submitted or retried. Real signup completion, full suites and lint were
NOT_RUN. The unrelated stale-ID test compilation problem remains.

2026-11-02 heading/conditional-validation follow-up: login heading is exactly
`우리의 여행이 시작되는 곳`; signup stays `회원가입`, without subtitle/wordmark.
`MainFragment` now uses authoritative `AuthViewModel` validators for edited-field
blur and local submit errors; pristine fields have no conditions, corrections
clear their errors, and returning to login clears signup validation state.
Native server failures retain their signup/login mode and banner. Existing
username normalization, password 8-128 limits, nickname/confirmation and wire,
logo dimensions and Pretendard styling are preserved. Process-local JDK 17
`./gradlew.bat :app:testDebugUnitTest --tests
com.stog.app.auth.AuthStateMachineTest.signupErrorRetainsContractCodeAndMode
:app:assembleDebug --console=plain` exited 0; focused check 1/1, zero failures.
`adb install -r` succeeded on actual `Pixel_8` AVD (`emulator-5554`, Android 16;
generic model `sdk_gphone64_x86_64`). Blank signup, synthetic invalid
username/password focus loss, correction and return to login were observed
without submission/network authentication or account changes. Captures:
`.omo/artifacts/native-auth-ui-20261102/android/auth-scoped-login.png`,
`auth-scoped-signup-blank.png`, `auth-scoped-signup-invalid.png` and
`auth-scoped-signup-corrected.png` in that directory. Lint, full suites, backend,
real credentials and server submission were NOT_RUN.

2026-11-02 signup logo follow-up: increased only `app/src/main/res/values/dimens.xml`
`signup_logo_size` from 72dp to 96dp; login, launcher artwork, auth behavior,
dependencies, API and schema are unchanged. Process-local JDK 17
`.\gradlew.bat :app:assembleDebug :app:lintDebug --console=plain` assembled the
APK but exited 1 because Lint found four API-31-only splash theme items in
`values/themes.xml` and `values-night/themes.xml`; none is in the changed
dimension. Pixel_8 `emulator-5554` installed the APK without clearing data.
The signed-out signup screen showed the 96dp logo in light and dark themes,
and with font scale 1.3 the fields and actions remained visible. Evidence:
`../../.omo/artifacts/native-auth-ui-20261102/android/signup-logo-96dp-light.png`
and `signup-logo-96dp.png` in the same directory. Font scale 1.0 and automatic
night mode were restored; no credentials were entered. Submission, tests,
keyboard and other devices were NOT_RUN. Lint is FAILED, not VERIFIED.

2026-11-02 signup presentation follow-up: revised the existing signup form
without changing validation or the auth API. `:app:assembleDebug` passed;
the debug APK was installed with `adb install -r` on Pixel_8. Login to signup
and the return-to-login action were observed without entering credentials.
Clean signup screenshot: `.omo/artifacts/native-auth-ui-20261102/android/signup-approved.png`.
Keyboard overlap, signup submission, tests, lint and device matrix were not verified.

2026-11-02 signup-link follow-up: removed the auth mode toggle and placed
`회원가입하기` below login, with `로그인으로 돌아가기` on the existing signup form.
`:app:assembleDebug` passed; the APK was installed without clearing data on
Pixel_8, and the signed-out login and signup layouts were observed without
entering credentials. Screenshots: `android/auth-login-link.png` and
`android/auth-signup-link.png` under `.omo/artifacts/native-auth-ui-20261102/`.
Submission, return-link activation, tests, lint and broader device matrix were
not run.

2026-11-02 monochrome design amendment: existing logo is used in-app and as the adaptive/legacy launcher icon; shared Material palette, native sans, and compact detail management actions were updated. `:app:assembleDebug` passed and the APK was installed with `adb install -r` on Pixel 8. Correction: the previously reported missing logo after a live appearance switch was a mistaken visual reading. The logo crop in the original light screenshot is pixel-identical to a visible-logo reference, as is the dark screenshot crop. A fresh light-to-dark reproduction after build/install produced `android/logo-appearance-verified.png` under `.omo/artifacts/native-auth-ui-20261102/`; its logo crop is pixel-identical to `android/monochrome-dark-verified.png`. No app code change was needed; emulator appearance was restored to auto. No signed-in session was available, so Home, trips, detail and their current action layout were NOT_VERIFIED; no credentials or account were created. Tests, lint, and broader matrix NOT_RUN for this amendment.

| Item | Value |
| --- | --- |
| User decision | `아이디와 비밀번호` (username + password), confirmed 2026-11-02 |
| Authorization | Completed native baseline; 2026-11-02 extension limited to existing core-flow/layout repair by the frontend owner |
| Current status | BASELINE COMPLETED / REPAIR IMPLEMENTATION COMPLETE / BOUNDED UI PASS |
| Backend verification | Focused 26/26; full PostgreSQL 145/145 + bootJar; corrected loopback runtime VERIFIED |
| Android verification | Baseline evidence preserved; final repair assembleDebug + lintDebug PASS and bounded visible flow observed; Place detail/basket-save NOT_VERIFIED this pass |
| Public exposure | EXCLUDED |
| Prior slot | `MVP-TRIP-005` deferred without losing its contract/evidence |

The approved wire/security design is
[backend-contract.md](../../.omo/artifacts/native-auth-ui-20261102/backend-contract.md).
This TASK owns implementation and concise evidence; it does not duplicate that
contract in full.

## 2. Observable outcome

A new nonpersonal user can use the real native signup/login API, receive the
existing opaque STOG session, read the real empty My Trips response, create a
trip, log out, log in again, and read the same trip. The current Android app
uses this server path rather than a dummy account or external provider.

## 3. Scope

### Scoped repair (approved 2026-11-02; implementation complete, bounded verification)

This is an increment of this single TASK, not a new product implementation.
The native baseline and its recorded completion remain completed.

- Frontend owns `app/**` and `docs/ANDROID.md`; this documentation reconciliation
  owns only README, PLAN, this TASK and SCREEN_FLOW_REQUIREMENTS.
- Repair consistent screen gutters, theme, header/Back behavior and compact trip
  cover presentation, plus discovered defects within existing native auth, Home,
  My Trips, create, detail, Place search/detail and basket save/read flows.
- Preserve real API data, native signup/login/refresh/logout, two real root
  destinations, privacy, request replay and existing navigation/state recovery.
- Do not add whole-product screens, five-tab shell, itinerary/schedule/reorder or
  basket-delete expansion, R3c ranked search/V8, providers, dependencies, API/schema
  changes, public exposure, AI, camera/recording or CELL. Instagram remains design
  only. Mapbox remains conditional on verified account/free-tier/forced-no-charge
  controls and a separately approved map task; no map integration is authorized.

Acceptance for this increment:

- Existing screens use consistent gutters/theme/header, usable Back navigation
  and compact covers without hiding existing actions or content.
- Existing native/home/trips/create/detail/search/basket flows remain reachable
  and preserve their current contracts; no fabricated product completion.
- With process-local JDK 17, `.\gradlew.bat assembleDebug lintDebug` exits 0,
  followed by one short visible flow through the affected existing screens.
  Record the actual observed path and any unverified screens in one small handoff.
  No broad test suite or dark/device matrix is required by this increment.

Final repair handoff:

- [UI evidence](../../.omo/artifacts/native-auth-ui-20261102/android/ui-repair-evidence.md):
  process-local JDK 17 `:app:assembleDebug :app:lintDebug --console=plain` passed
  after fixing the Back crash and search guidance clipping.
- Pixel Home/My Trips/trip detail/empty basket/empty cover/Back and search-empty
  were observed. Screenshots in the same directory:
  `ui-repair-{home,trips,detail,cover-empty,back,search-empty}.png`.
- Query `cafe` returned no results; Place detail and basket-save remain
  `NOT_VERIFIED` in this pass. No fake/import data was added and no full-product
  completion is claimed.
- A synthetic QA password appeared in a local tool transcript. No credential
  values are recorded here. Rotation/revoke permission has been requested and is
  pending; no account changes were made. The signed-in session was left active;
  prior server session status is unknown.
- Single next proposal: `MVP-TRIP-005` R3c remains deferred, not authorized to start.

### Completed baseline: backend owner

- `POST /api/auth/signup` and `POST /api/auth/login`.
- Installed Spring Security Crypto PBKDF2-HMAC-SHA-256 encoder with the approved
  work parameters and versioned stored id.
- Additive `V7__native_password_auth.sql`; V1-V6 and existing rows are not
  modified.
- Username/display-name/password validation, offline blocklist, durable
  indistinguishable login throttling, atomic signup/session issuance.
- Existing refresh rotation/replay revoke, current-session logout, Bearer
  principal filter, restricted-owner gate, Kakao rows and dormant backend
  exchange are preserved.
- Existing runner tests, full backend gate, `bootJar`, retained-dev backup,
  aggregate preservation proof and exact loopback runtime.
- Minimal `Start-LocalBackend.ps1` persistent private media-root correction so
  the normal local application can actually start without an ad-hoc
  `STOG_MEDIA_ROOT`.

### Completed baseline: frontend owner (outside the original backend execution)

- `app/**` and `docs/ANDROID.md`.
- Native credential form/state/client integration.
- Real Home + My Trips two-destination shell under the frontend proposal. It is
  not a fake five-tab implementation.
- Android test replacement and final device UI QA.

### Excluded

- Email/SMS, external identity provider in the current app flow, account merge,
  password recovery/reset, account deletion, profile endpoint, MFA, auth bypass,
  public listener/TLS/proxy/firewall, R3c search implementation, and full shell
  redesign.

## 4. Confirmed decisions

| ID | Decision |
| --- | --- |
| NA-01 | Username + password is the current login method. |
| NA-02 | Signup requires a display name; no separate profile endpoint is added. |
| NA-03 | Existing Kakao identity/data and V1-V6 remain intact; native signup creates a separate user and never merges. |
| NA-04 | Access/refresh/session lifetimes and refresh/logout behavior are reused unchanged. |
| NA-05 | Native auth owns V7; deferred R3c uses future V8. |
| NA-06 | Backend remains exact loopback-only and uses normal, non-restricted configuration for frontend QA. |
| NA-07 | No credential, username/hash pair, token, internal user id or private data appears in evidence. |

## 5. Changed paths

Planned backend and documentation paths:

- `backend/src/main/java/com/stog/backend/{AuthController,AuthService,AuthException,SecurityConfig}.java`
- `backend/src/main/resources/db/migration/V7__native_password_auth.sql`
- `backend/src/main/resources/security/`
- `backend/src/test/`
- `backend/scripts/Start-LocalBackend.ps1`
- `README.md`
- `docs/{PLAN,API,DATABASE,BACKEND,QUALITY_OPERATIONS}.md`
- `docs/tasks/{NATIVE-AUTH-UI-001,MVP-TRIP-005}.md`
- `.omo/artifacts/native-auth-ui-20261102/`

No `app/**` or `docs/ANDROID.md` edit belongs to this backend task.

## 6. Security, data and failure boundary

- Signup commits user, native credential and initial session family atomically.
- Unknown username, wrong password, blocked username and owner mismatch are the
  same `401 AUTHENTICATION_REQUIRED`.
- Every syntactically valid login performs one same-parameter PBKDF2 check;
  unknown users use a process-start dummy encoded value.
- Five failures in 15 minutes block the canonical username for 15 minutes while
  retaining the same external 401.
- Passwords are NFC-normalized, never trimmed, bounded to 8-128 code points and
  1024 UTF-8 bytes, and checked against the pinned offline blocklist.
- No runtime breach/password API, new service, paid fallback, IP/device
  fingerprint or public listener is added.
- Retained development V7 is applied only after a private backup and aggregate
  pre-check. Existing rows are never deleted.

## 7. Acceptance and testing

| ID | Acceptance |
| --- | --- |
| NA-A1 | Exact signup/login wire and error headers/envelopes pass web boundary tests. |
| NA-A2 | V1-V7 generated non-superuser migration and native auth PostgreSQL tests pass with cleanup `0|0`. |
| NA-A3 | Concurrency, rollback, hash non-disclosure, throttle, restricted-owner and existing session regression pass. |
| NA-A4 | Full existing backend PostgreSQL suite passes once reliably; `bootJar` succeeds. |
| NA-A5 | Retained dev backup is readable; V7 changes only the approved schema and aggregate identity/domain counts remain preserved. |
| NA-A6 | Exact loopback runtime completes native signup -> empty trips -> create -> logout -> login -> same trip without Kakao calls. |
| NA-A7 | A fresh nonpersonal QA account and secure normal loopback runtime are handed to the frontend owner without exposing credentials. |

The existing runner is the test authority. Fixed sleeps, weakened assertions,
skipped tests and credential-bearing logs/evidence are forbidden. Any existing
R3c behavioral RED remains historical/preexisting and is not part of the native
auth green gate.

## 8. Recovery and remaining release decisions

- Code rollback does not roll V7 or user data backward. A failed migration
  stops before frontend handoff and uses the new private backup for operator
  recovery planning; restore is not performed without separate authorization.
- Password recovery and account deletion remain future user-owned release
  decisions. There is no reset endpoint or security-question fallback.
- Public exposure remains a separate task.

## 9. Evidence

- Focused generated auth/session/restricted PostgreSQL gate: PASS, cleanup
  `0|0`.
- Full generated non-superuser V1-V7 gate: 135/135, failure/error/skip 0,
  `bootJar` PASS, cleanup `0|0`, metadata unchanged.
- Retained pre-V7 private backup: postgres-owned mode 600, readable custom
  archive. Restore was not run.
- Retained V7: Flyway 1-7 success; preserved existing Kakao User/identity/session
  fingerprints exact.
- Real normal loopback: signup 201, empty trips, create 201, logout 204, login
  200, same persisted trip, final logout 204.
- Runtime: exact `127.0.0.1:8080`, persistent non-reparse media directory,
  protected DPAPI QA credential, no Kakao call/exchange and no public listener.

Sanitized summary:
[backend-implementation.md](../../.omo/artifacts/native-auth-ui-20261102/backend-implementation.md).
Independent review corrections:
[review-implementation.md](../../.omo/artifacts/native-auth-ui-20261102/review-implementation.md).
Android implementation/device QA remain frontend-owned and are not claimed here.

## Frontend completion

### Pretendard typography follow-up (2026-11-02)

Bundled official Pretendard 1.3.9 static Korean OTFs from
`https://github.com/orioncactus/pretendard/releases/tag/v1.3.9`:
Regular 1,574,352 bytes, Medium 1,584,068 bytes, SemiBold 1,583,704 bytes.
The upstream SIL Open Font License 1.1 is bundled at
`app/src/main/assets/licenses/Pretendard-LICENSE.txt`. The AppCompat font family
and Material text appearances use these packaged resources (400 body, 500 label,
600 heading); auth headings use HeadlineSmall. No runtime font service or new
dependency was introduced.

The follow-up also orders signup fields nickname, username, password, then
password confirmation, with Next/Done IME actions matching that order.

With process-local JDK 17, `./gradlew.bat :app:assembleDebug --console=plain`
reported BUILD SUCCESSFUL. `adb -s emulator-5554 install -r` succeeded without
clearing data; login and signup were visually inspected on Pixel_8, with Korean
text, logo, controls and helper copy visible without clipping at this viewport:
`../../.omo/artifacts/native-auth-ui-20261102/android/pretendard-login.png` and
`../../.omo/artifacts/native-auth-ui-20261102/android/pretendard-signup.png`.
No credentials, tests, lint, other screens or device matrix were exercised.

### Password minimum and copy follow-up (2026-11-02)

Latest explicit user decision: minimum 8 characters, fast implementation with
focused checks only. Server and Android minimum changed from 15 to 8; maximum
128, normalization, byte cap, blocklist, hashing and session behavior remain.
Existing accounts/passwords are preserved; no migration or reset is required.
Password hints retain useful length guidance. Reachable logout uncertainty copy
is concise Korean; basket presentation no longer includes internal item IDs.
Auth UI already maps server errors to fixed local resources rather than raw
exceptions. Unbound legacy technical resources were left unchanged.

Changed: `AuthService`, `AuthViewModel`, `strings.xml`, `TripDetailFragment`,
`NativeAuthSecurityTest`, `ConfiguredAuthUiTest`, focused
`AuthPasswordValidationTest`, and exact API/Android policy lines.
Status: IMPLEMENTED / FOCUSED TESTS + BUILD VERIFIED.
Executed from the root: `.\gradlew.bat -p backend test --tests
com.stog.backend.NativeAuthSecurityTest.nativeInputNormalizationIsExact
--console=plain`, then `.\gradlew.bat :app:testDebugUnitTest --tests
com.stog.app.auth.AuthPasswordValidationTest :app:assembleDebug --console=plain`.
Both Gradle runs reported BUILD SUCCESSFUL; each selected boundary test passed
1/1 with no failures/errors/skips. XML evidence:
`backend/build/test-results/test/TEST-com.stog.backend.NativeAuthSecurityTest.xml`
and `app/build/test-results/testDebugUnitTest/TEST-com.stog.app.auth.AuthPasswordValidationTest.xml`.
The PowerShell wrapper's process-local JAVA_HOME assignment was expanded away
and failed before Gradle ran, so this follow-up does not claim a JDK 17 launcher.
Backend retains its configured Java 17 toolchain. No repeated gate was run.
Delivery follow-up (2026-11-02): no existing `127.0.0.1:8080` listener was
present at the ownership check, so no process was stopped. The stale bootJar
was rebuilt with explicit process-local `JAVA_HOME=C:/Program Files/Java/jdk-17`
and matching PATH using `.\gradlew.bat --no-daemon -p backend bootJar
--console=plain`: BUILD SUCCESSFUL, exit 0. The approved
`backend/scripts/Start-LocalBackend.ps1` then started the updated code with
its existing protected configuration; anonymous `GET /api/trips` returned 401.
The task-owned launcher remains running (`bash_9`).
Existing `Pixel_8` / `emulator-5554` received the assembled debug APK using
`adb install -r`: Success, exit 0. `am start -n com.stog.app/.MainActivity`
launched it without credential entry; existing tcp:8080 reverse was preserved.
No account changes, signup, DB/data reset, or credential logging occurred.
Eight-character runtime signup is NOT_VERIFIED. No additional tests or lint
were run for delivery; full suites and instrumentation remain NOT_RUN.

**Status: COMPLETED actual UI pass (2026-11-02).** Corrected loopback runtime에서 native signup -> Home -> real trip create/detail read -> My Trips -> logout -> relogin persistence를 Pixel에서 확인했고 앱은 My Trips에 남겼다. Real-flow에서 발견한 My Trips bottom-navigation stack overflow를 수정했다.

Sanitized visual evidence:

- `../../.omo/artifacts/native-auth-ui-20261102/android/final-home-empty.png`
- `../../.omo/artifacts/native-auth-ui-20261102/android/final-trip-detail.png`
- `../../.omo/artifacts/native-auth-ui-20261102/android/final-my-trips-relogin.png`

Prior JVM/lint/component checks are PASS; final navigation fix received `assembleDebug` only. Signup began before the explicit corrected-runtime notice; create/detail/logout/relogin followed it. Dark matrix is `NOT_RUN` by user scope reduction. R3c remains the one deferred proposed next slot; it is not implemented here.

Runtime follow-up (2026-11-02): the signup connection error came from a stopped local backend (no 127.0.0.1:8080 listener); the earlier launcher did not outlive its session. No signup code defect was found. `backend/scripts/Start-LocalBackend.ps1` was restarted with the fresh bootJar (anonymous `GET /api/trips` 401), and Pixel_8 app UI signup with a synthetic QA user and an eight-character password reached Home. Eight-character runtime signup is now VERIFIED.

Field-spacing fix: `MainFragment` now disables each field's error area when valid or cleared, because `setError(null)` left the space reserved. `:app:assembleDebug` with process-local JDK 17 succeeded and the APK was installed. Device measurement in pixels: fresh field heights 166 with gaps 32; blank submit shows errors; after correction heights 166 with gaps 32, and login password-to-button gap 84. The two latest crash-buffer entries predate this build (older line numbers, three hours earlier); no new crash entry appeared. Tests and lint are NOT_RUN for this fix.

Open: the QA password appeared once in a local tool transcript, so rotation or removal of the `qamvz8flaw` account awaits user permission. Backend remains task-owned in a background session and stops with it.

New-trip date follow-up (2026-11-02): `TripDateRangeView` (Java, framework views) replaces typed dates in `fragment_trip_create.xml`; `TripCreateFragment` keeps ISO dates in hidden draft fields, so the draft, API contract and existing UI-test field IDs are unchanged. The Material date picker was tried first and rejected because its full-screen surface rendered gray and hid the selected range in this monochrome theme. Signup and login titles now share centered alignment.

Evidence: `:app:assembleDebug` with process-local JDK 17 succeeded and the APK was installed on Pixel_8. Selecting Nov 15 and Nov 18 showed the filled range and 3 nights 4 days, then created a trip showing 2026-11-15 ~ 2026-11-18; no new crash entry appeared. NOT_RUN: rotation/recreation (the attempted rotation did not take effect), dark mode, month navigation by tap, instrumentation, unit tests, lint.

QA credential: with user approval in this conversation, the single password credential for `qamvz8flaw` was deleted (1 row; 0 active sessions, 0 attempt rows) and login with the exposed password returned 401. Its user row and two QA trips were kept because user references are RESTRICT. The older QA credential rotation noted in PLAN is a separate item and stays pending.
