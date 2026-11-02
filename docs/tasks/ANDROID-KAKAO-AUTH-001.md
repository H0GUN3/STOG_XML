# ANDROID-KAKAO-AUTH-001: Android Kakao login and STOG session client

## Status

`IMPLEMENTED / LOCAL FIXTURE + MISSING-CONFIG DEVICE VERIFIED / REAL KAKAO LOOPBACK RUNTIME VERIFIED, BOUNDED / PROTECTED DOMAIN AND PRODUCTION E2E NOT_RUN`

The user authorized the official Kakao Android SDK and bounded Android auth implementation. This task adds no real key, account, provider call, public network, backend/schema change, or new product screen.

## Scope and decisions

- Java/XML remains the app source policy. `com.kakao.sdk:v2-user:2.25.0` is the only direct provider dependency; `v2-all`, Rx, and a direct HTTP stack were not added.
- `local.properties` takes precedence over environment for `stog.kakao.nativeAppKey` / `STOG_KAKAO_NATIVE_APP_KEY` and `stog.server.baseUrl` / `STOG_SERVER_BASE_URL`. Property presence decides precedence: an explicit blank local value disables environment fallback.
- The Native App Key is an APK-visible identifier, not a backend secret. Kakao Admin Keys and client secrets never enter this build. Invalid/missing input becomes an empty generated value and a disabled actionable UI; no fallback server exists.
- A configured manifest registers `com.kakao.sdk.auth.AuthCodeHandlerActivity` for exact `kakao{NATIVE_APP_KEY}://oauth`. Without a valid 32-hex key the activity is disabled and Kakao SDK initialization/login are unreachable.
- Release accepts HTTPS only. Debug additionally accepts only literal `http://127.0.0.1[:port]`; its network-security config denies other cleartext on API 24+, while the same application validation remains fail-closed on API 23 where that platform config is unavailable.
- Kakao Talk login falls back to Kakao Account only for non-cancellation failure. Attempt identity rejects stale callbacks. Activity-scoped ViewModel state survives recreation; view/context callback ownership is attached only for the Fragment view lifetime.
- The Kakao access token is exchanged from memory and then SDK logout performs provider-token cleanup. STOG access remains memory-only. STOG refresh is AES/GCM ciphertext in private preferences under a non-exportable Android Keystore key; backup/device transfer remain disabled. Decrypt/key failure clears ciphertext and key, then signs out.
- Startup refreshes once with concurrent restore calls coalesced. Logout without a usable refresh token cannot prove server revocation, so missing/corrupt storage clears memory/local credentials and exposes revoke-unconfirmed. No unlink/global Kakao logout is called.
- Android auth HTTP disables redirects per connection; 3xx never forwards a credential body or authorization header. Exchange failure/response loss performs Kakao SDK cleanup before retry is enabled. Cleanup failure has a distinct warning/retry state; stale cleanup callbacks cannot reopen login or re-exchange the consumed token.

## Changed paths

`settings.gradle`, `app/build.gradle`, Android manifest/debug network config, `AppConfig`, `StogApplication`, auth Java classes, `MainFragment`, auth layout/resources, focused unit/instrumentation tests, canonical docs, and `.omo/artifacts/android-kakao-auth-001/`.

## Acceptance and evidence

- RED before behavior: missing `AuthStateMachine` seam caused `compileDebugUnitTestJavaWithJavac` failure; `red-proof.json` records the bounded result.
- Review RED proves redirect following, false logout confirmation, missing exchange cleanup, and blank-local environment fallthrough independently. GREEN is `.omo/artifacts/android-kakao-auth-001/review-fix/red-green.json`.
- Final Android gate: 9 JVM tests and 9 Pixel_8 API-36 instrumentation tests, failures/errors/skips 0; Lint 0 errors and the same two explainable warnings; `assembleDebug` passed. HTTP tests use literal-loopback fixtures, not Kakao/backend/provider evidence.
- Instrumentation verifies 302/307/308 target isolation, 400/401/503/malformed responses, missing/corrupt-refresh logout, exchange/refresh/logout success, coalesced restore, access-memory behavior, Keystore behavior, configured UI transitions, and missing-config recreation.
- Configured debug fixture rendering covers signed-in success, successful logout/signed-out, and cleaned exchange error on the real emulator UI. Captures and action order are under `.omo/artifacts/android-kakao-auth-001/review-fix/`; no production dummy fallback or test principal exists. Existing missing-config portrait/landscape/large-font/light-dark evidence remains valid.
- Kakao 2.25.0 AAR declares minSdk 23 and compiled from Java against the used overloads. Its POM declares Apache-2.0; dependency evidence records the expected transitive Kotlin, coroutines, serialization, Retrofit/OkHttp, Material/AppCompat graph. Application HTTP still uses platform `HttpURLConnection`.

## NOT_RUN and blockers

At implementation close, real Native App Key/app ID, Kakao account/console, `kapi.kakao.com`, real callback, approved HTTPS STOG URL, public network, physical device, API-23 runtime, TalkBack, and production login/logout E2E were `NOT_RUN`. The later loopback real-login result is recorded below. That result verifies only the bounded STOG local flow and cleanup, not protected TripItem domain requests, approved HTTPS, public network, physical/API-23/TalkBack coverage, interactive consent, or production E2E. Backend tests were intentionally not rerun because backend source/schema did not change.

Only the user can privately supply the real Native App Key and base URL, configure the backend expected app ID, confirm account ownership/key hashes and zero-cost quota/billing policy, and approve release deletion/recovery/session policy. No key value belongs in this TASK or evidence.

## 2026-09-29 real-login runtime follow-up

A configured debug APK was assembled and installed on `Pixel_8` (`emulator-5554`). The retained DPAPI-backed backend ran only on `127.0.0.1:8080` with Kakao enabled, expected app ID `1244167`, fixture mode false, and the exact official token-info/user-info endpoints; `adb reverse tcp:8080 tcp:8080` was active. The first `Continue with Kakao` completed through an existing provider session and rendered `Signed in` without account/credential/consent interaction.

The approved follow-up captured a STOG-only signed-in screen, verified by aggregate-only DB queries that the matched identity mapped to one internal user with an active session, invoked normal app logout (not unlink/global logout), and captured the STOG-only signed-out screen. The latest server session was revoked, no active session remained for that matched user, and identity/user rows were preserved. One relogin attempt then opened Kakao's Custom Tab and stopped at the user-owned interactive gate without inspecting, capturing, selecting, entering, or accepting its contents.

After the user reported completion, direct STOG UI and aggregate-only DB verification showed `Signed in`, the same internal identity, a distinct new active session, the prior session revoked, and no duplicate identity/user. A safe STOG-only relogin screenshot was retained. Final normal logout returned to `Sign in to STOG`; the newest session was revoked, no active session remained for the matched user, and identity/user rows remained preserved. The owned reverse, backend process tree, and emulator processes were then removed with bounded process-exit waits; no `127.0.0.1:8080` listener or owned PID remained. The dedicated DB, DPAPI credential, and real identity/user data were preserved. Runtime and sanitized cleanup evidence are in ignored `.omo/artifacts/real-kakao-login-runtime/`. Production HTTPS and protected domain E2E remain `NOT_RUN`.
