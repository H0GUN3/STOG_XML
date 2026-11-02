# Native username/password auth UI proposal

**Status:** PROPOSED ONLY — no Android, backend, database, provider, or runtime configuration changes are included in this artifact.

**Decision captured:** the first-party auth surface is Korean **아이디와 비밀번호** login, with **아이디 / 비밀번호 / 표시 이름** registration. Do not add email, SMS, Kakao, Naver, or any other provider control to this flow. Kakao's already-verified local implementation and existing user rows remain untouched until a separately approved migration/linking decision.

## 1. What will be built after the shared API contract lands

The smallest cohesive product flow is:

```text
launch
  -> restore STOG session
  -> native 로그인 / 회원가입
  -> 홈
  -> 내 여행
  -> existing TripList -> TripCreate -> TripDetail
       -> TripEdit | TripMembers | invitation join | PlaceSearch -> PlaceDetail -> basket save
```

This is deliberately **two real destinations**, not a fake five-tab shell:

- **홈** is a signed-in landing surface backed by the existing authenticated trip list state. It shows only an honest loading, empty, error, or the most recent actual trip; it never renders sample travel cards or fabricated counts.
- **내 여행** hosts the already implemented `TripListFragment`, whose create/detail/member/place flows already call protected APIs.

The current UUID “Read a saved trip item” tool is verification scaffolding, not a product destination. It should not appear in the normal signed-in shell. Its tested transport code can remain unchanged until a separately scoped cleanup proves that no test or diagnostic path uses it.

## 2. Blocking shared API contract (required before code)

Current source only exposes `POST /api/auth/kakao/exchange`, `/refresh`, and `/logout` (`backend/src/main/java/com/stog/backend/AuthController.java`), while `stog_user` only contains `id` and V2 permits only `KAKAO` identities (`backend/src/main/resources/db/migration/V1__basket_save_foundation.sql`, `V2__kakao_opaque_auth.sql`). There is no username/password/signup contract in either Android or backend source.

The following must be jointly fixed in one auth contract before UI implementation starts:

1. **Routes and payloads**
   - `POST /api/auth/signup` with exactly `username`, `password`, and `display_name`.
   - `POST /api/auth/login` with exactly `username` and `password`.
   - A successful response must retain the existing token envelope consumed by `AuthApiClient`: `token_type`, `access_token`, `access_expires_in`, `refresh_token`, and `refresh_expires_in`.
   - Existing `/refresh` and `/logout` semantics remain the session/revocation source of truth.

2. **Validation and disclosure**
   - Define canonical username alphabet, case normalization, minimum/maximum length, and whether Korean characters are permitted in an ID.
   - Define display-name normalization and length. It is a registration field, not a fabricated home-profile value.
   - Define password length/strength policy and the exact field-level error code vocabulary.
   - Wrong ID/password must be non-enumerating. Duplicate signup can have a stable, user-actionable conflict code without leaking more account data.
   - All credential routes require the same HTTPS/debug-literal-loopback transport rule currently enforced by `AppConfig`.

3. **Identity and existing data**
   - Add first-party credential storage using a modern slow password hash and a unique canonical username.
   - Preserve all existing `stog_user`, `auth_identity`, `auth_session`, trip, member, and content rows.
   - Do **not** auto-link a new native ID to an existing Kakao identity: there is no email/phone proof and automatic merging would risk joining two people’s trips. A future, explicitly authorized authenticated linking/migration flow is required if old Kakao-owned data must become reachable through a native account.
   - Confirm that a successful native signup/login yields the same internal `principal_user_id` authorization boundary used by all existing trip APIs.

4. **Failure/recovery semantics**
   - Specify malformed request, duplicate ID, invalid credentials, rate limit/temporary failure, password-hash failure, session refresh failure, and logout behavior.
   - Keep refresh rotation/replay revoke and memory-only access / Keystore-encrypted refresh behavior. The client must not retain a password after the HTTP request completes.

Until this contract is approved, no Android source should guess endpoint names, JSON fields, password policy, errors, migration schema, or account-linking behavior.

## 3. Native screen architecture

### Auth gate

`MainFragment` remains the single activity-hosted root and keeps its view-binding lifecycle discipline. Its UI has two mutually exclusive regions:

1. **Signed out / restoring:** a Korean native-auth gate.
2. **Signed in:** a real app shell containing Home or My Trips.

The gate has a compact textual brand header using the existing `stog_logo`, then a segmented text action (`로그인` / `회원가입`) that swaps fields in place rather than navigating to an external activity.

#### Login hierarchy

```text
ScrollView
  centered content, existing 24dp / 32dp screen padding
    STOG logo (decorative)
    “여행의 계획을 함께 만들어요” headline
    “로그인” title
    TextInputLayout: 아이디
    TextInputLayout: 비밀번호 + password visibility toggle
    live error/status region
    full-width “로그인” primary Material button
    “계정이 없나요? 회원가입” text action
```

#### Signup hierarchy

```text
same header and scroll rhythm
  “회원가입” title
  TextInputLayout: 아이디
  TextInputLayout: 표시 이름
  TextInputLayout: 비밀번호 + password visibility toggle
  contract-defined password guidance/error region
  full-width “회원가입” primary Material button
  “이미 계정이 있나요? 로그인” text action
```

There is no email field, phone/SMS field, provider divider, Kakao button, consent pretext that implies a provider, or external-login fallback.

### Signed-in app shell

The shell retains the existing FragmentManager ownership and `trip_product_container` so the verified trip fragments continue to navigate without a rewrite.

```text
vertical shell
  Material top app bar: STOG wordmark
  FrameLayout id=trip_product_container
    HomeFragment OR existing TripListFragment
    existing TripCreate/TripDetail/member/place fragments are back-stack children
  Material BottomNavigationView
    홈
    내 여행
```

- Selecting **홈** or **내 여행** changes a real root destination; it does not create placeholder tabs.
- The My Trips root reuses `TripViewModel` list data and its current anchor restoration. Existing create/detail/member/place entry paths remain actual authenticated API flows.
- The Home root observes that same real list state. Loading, empty, retryable error, and authenticated content render distinctly. With content, it may render one actual latest trip and a “내 여행 보기” action; with no content it shows a primary “첫 여행 만들기” action into the existing create flow.
- Home does not greet by display name until a stable authenticated profile/read contract exists. The supplied registration name is not cached as an invented profile source.
- System Back first closes existing detail/create/member/place child screens; at a root it follows standard activity behavior. Tab switches do not create fake back-stack history.

## 4. State, security, and restoration

Replace the Kakao-specific state machine with native states that preserve the current finite-state rendering approach:

```text
MISSING_CONFIGURATION
RESTORING
SIGNED_OUT_LOGIN
SIGNED_OUT_SIGNUP
SUBMITTING_LOGIN
SUBMITTING_SIGNUP
SIGNED_IN
ERROR_LOGIN
ERROR_SIGNUP
LOGGING_OUT
SIGNED_OUT_REVOKE_UNCONFIRMED
```

- `AuthViewModel` remains activity-scoped for session ownership; `TripViewModel` remains scoped as today for trip state.
- The native client operations carry an attempt/generation identity just as current restore/read/place calls do. A completion from an old submit, logout, replacement submit, or destroyed view is ignored.
- A successful login/signup feeds the existing `AuthApiClient` token acceptance path. Access remains memory-only; the rotated refresh token remains only in `SecureRefreshStore` AES/GCM storage.
- Password values are never put in `SavedStateHandle`, a Bundle, `SharedPreferences`, logs, test screenshots, analytics, error text, or navigation arguments. They are cleared on submit completion, mode change, logout, and view recreation.
- Configuration changes keep the server-owned session/restoring state and selected auth mode. The ID/display-name draft can be restored only if the final privacy policy accepts it; the secure default is to clear all auth fields on process death. Password is always re-entered.
- `onDestroyView` clears listeners and binding exactly as current fragments do. The existing `MainFragment` invitation handling remains signed-in-only; auth-end/logout clears private trip selection and pending private state.

## 5. Visual system to extend, not replace

The app already has a coherent small Material 3 system:

- `Theme.STOG` is `Theme.Material3.DayNight.NoActionBar` with seed colors in `app/src/main/res/values/themes.xml` and `colors.xml`.
- The available spacing rhythm is 8/12/20/32dp; controls use 48dp minimum height; cards use 20dp padding and 20dp corners (`dimens.xml`).
- Existing screens use `MaterialTextView`, `TextInputLayout`, `MaterialButton`, `MaterialCardView`, Material status/progress components, semantic `?attr/color…` references, and accessibility live regions.
- Existing imagery is a single `drawable-nodpi/stog_logo.png`; no navigation-icon set is present.

The implementation therefore uses only:

- existing Material semantic theme attributes for light/dark colors;
- existing dimensional tokens, adding a named dimension only when the system cannot express a required repeated value;
- Material headline/title/body text appearances already used by trip/place surfaces;
- two self-contained Material-style vector icons for the only real navigation actions (home and trips), with no remote image, map, or icon-library dependency;
- cards only where they group actual state. Empty/error surfaces are plain, explanatory, and actionable rather than simulated content.

No Compose, custom font, hardcoded pixel/color values, map SDK, provider SDK, or provider-branded asset is needed.

## 6. Planned Android ownership

The following is the exact Android file set proposed after the API contract is approved. “Modify/remove” is a plan, not a current edit.

| Path | Planned responsibility |
| --- | --- |
| `app/build.gradle` | Remove the Kakao SDK and Kakao BuildConfig/manifest-placeholder inputs; retain only server base URL and existing Java/XML/Material dependencies. |
| `app/src/main/AndroidManifest.xml` | Remove the Kakao callback activity and provider callback scheme; retain the launcher and existing invitation deep link. |
| `app/src/main/java/com/stog/app/AppConfig.java` | Make configuration server-only; preserve release-HTTPS/debug-literal-loopback validation. |
| `app/src/main/java/com/stog/app/MainFragment.java` | Render native auth state, own real Home/My Trips root selection, retain signed-in invitation routing and child FragmentManager boundaries. |
| `app/src/main/java/com/stog/app/auth/AuthStateMachine.java` | Replace provider-cleanup states/callbacks with native login/signup/restore/logout state transitions and stale-attempt rejection. |
| `app/src/main/java/com/stog/app/auth/AuthViewModel.java` | Expose native form/submit state and invoke the revised session client without Views or credentials retained by the model. |
| `app/src/main/java/com/stog/app/auth/AuthApiClient.java` | Add contract-approved signup/login calls; reuse existing token parsing, refresh-once protected transport, secure refresh storage, cancellation, and logout behavior. |
| `app/src/main/java/com/stog/app/auth/KakaoLoginClient.java` | Remove once all production and test references are replaced; do not leave an unused provider client. |
| `app/src/main/res/layout/fragment_main.xml` | Replace the single English Kakao/status shell and UUID tool surface with the auth gate plus signed-in shell containers. |
| `app/src/main/java/com/stog/app/home/HomeFragment.java` | New actual home root reading current authenticated trip-list state only. |
| `app/src/main/res/layout/fragment_home.xml` | New honest loading/content/empty/error Home layout, including an existing-flow create/My Trips CTA. |
| `app/src/main/res/menu/menu_main_navigation.xml` | New two-item navigation menu only: 홈 and 내 여행. |
| `app/src/main/res/drawable/ic_home_24.xml` | New local vector icon for the real Home destination. |
| `app/src/main/res/drawable/ic_trips_24.xml` | New local vector icon for the real My Trips destination. |
| `app/src/main/res/values/strings.xml` | Replace English/provider-facing auth copy with Korean native-auth, Home, state, error, and accessibility copy. |
| `app/src/main/res/values/dimens.xml` | Add only named, repeated dimensions required by the final signed-in shell; retain the established spacing scale. |
| `app/src/test/java/com/stog/app/auth/AuthStateMachineTest.java` | Replace Kakao transition assertions with native restore/login/signup/logout/stale-result tests. |
| `app/src/androidTest/java/com/stog/app/auth/ConfiguredAuthUiTest.java` | Replace provider fixtures with native contract fixtures and verify Korean login/signup rendering. |
| `app/src/androidTest/java/com/stog/app/auth/MissingConfigurationUiTest.java` | Keep server configuration failure coverage, updated for the server-only configuration requirement. |
| `app/src/androidTest/java/com/stog/app/auth/TripFlowUiTest.java` | Update the signed-in entry setup only if required; retain real trip flow behavior assertions. |

Existing trip fragments, adapters, layouts, `TripViewModel`, `SecureRefreshStore`, and protected trip/place APIs are intentionally not redesigned. The proposal preserves their binding teardown, list identity, generation invalidation, anchor recovery, role visibility, and actual API behavior.

## 7. Implementation sequence after contract approval

1. Land and test the native backend contract/migration separately, without deleting or rewriting existing Kakao user/session data.
2. Replace Android provider bootstrap/configuration and state machine with the native session seam.
3. Implement the login/signup gate and client parsing against the exact shared wire.
4. Add the two-destination signed-in shell and Home root, then attach existing My Trips without changing its API behavior.
5. Remove the unused Kakao app dependency/client/callback after tests prove there are no residual references.
6. Update Android tests and run the required gates and hands-on emulator QA.

No backend startup, dev database change, provider runtime, public server, map dependency, or production account operation occurs during the UI-only work until its own approved task says so.

## 8. Visual and behavioral QA plan

Use the current verified Android evidence as the baseline: Pixel_8 API 36, portrait, font scale 1.30, light/dark rendering, Back/recreation, and empty crash scan were observed in `.omo/artifacts/mvp-trip-003/android/final-verification.json`. Execute the following only after code exists:

### Native auth

- Missing server configuration shows an actionable Korean local-configuration state and never starts an external provider.
- Login: empty/invalid fields, invalid credentials, recoverable transport failure, submit progress, success, and server-reported duplicate-safe errors.
- Signup: field validation, duplicate ID conflict, success, stale completion after a second submit or logout, and no provider UI.
- Rotate/recreate during each stable state; confirm password is blank and no private auth/trip content appears when signed out.
- Verify session restore, refresh rotation failure, logout, and revoke-unconfirmed messaging with the existing secure token storage behavior.

### Product flow

- On a phone-sized Pixel_8 portrait surface, complete login/signup -> Home -> My Trips -> new trip -> detail -> members -> place search/detail -> basket save using actual protected APIs/fixture boundary approved for that task.
- Confirm Home empty/error screens contain no sample cards; with actual trips, the visible card/CTA maps to real API data.
- Verify tab/root switching, existing list anchor restoration, system Back from every existing child surface, invitation routing after sign-in, logout, and no private content after auth-ended.

### Visual/accessibility matrix

- Default font and **1.30** font scale: no clipped auth labels, password affordance, bottom navigation labels, CTA, or trip controls; screen scrolls instead of overlapping.
- Light and dark themes: all text/icon/outline/status colors derive from Material semantic attributes; error/loading meaning is not color-only.
- TalkBack baseline: logo is decorative, inputs have labels/errors, password toggle is described by Material, progress/status uses live regions, and navigation items announce their Korean labels.
- Capture screenshots only after all data is scrubbed; do not include tokens, passwords, private IDs, raw provider values, or real user data.

## 9. Known blockers and non-goals

**Blockers**

1. No shared native username/password/signup API or database schema exists.
2. Username normalization, password policy/hash choice, duplicate-ID error behavior, display-name policy, and account-linking/migration policy are undecided.
3. Existing Kakao-backed accounts can remain intact without data loss, but they cannot safely become native accounts without a separately authorized proof/linking mechanism.

**Explicit non-goals**

- No Kakao/external login UI, callback, SDK, email, SMS, password reset, provider migration, map, Compose, fake tabs, mock cards, or unapproved API.
- No backend startup, local DB reset/change, dev-user creation, public networking, or modification of existing user data during this proposal stage.
- No claim that the planned Home, native auth, or a five-tab product shell is already implemented. The current shipped app remains the English Kakao auth shell plus verified trip/place foundations.

## 10. Source basis

- Current navigation/auth composition: `app/src/main/java/com/stog/app/MainActivity.java`, `MainFragment.java`, `auth/AuthViewModel.java`, `auth/AuthStateMachine.java`, `auth/AuthApiClient.java`.
- Existing system and assets: `app/src/main/res/values/{colors,dimens,themes,strings}.xml`, `app/src/main/res/layout/fragment_main.xml`, `app/src/main/res/drawable-nodpi/stog_logo.png`.
- Existing real trip UI patterns: `trip/TripListFragment.java`, `TripDetailFragment.java`, `TripMembersFragment.java`, `PlaceSearchFragment.java`, and their XML/item layouts.
- Existing Android lifecycle/security requirements: `docs/ANDROID.md`, `AGENTS.md`.
- Product/navigation boundary and no-fake-surface requirement: `docs/SCREEN_FLOW_REQUIREMENTS.md`.
- Graph-first architecture evidence: `graphify-out/GRAPH_REPORT.md` (2026-11-01; XML is an acknowledged coverage limitation, source above is authoritative).
- Latest applicable Android emulator audit: `.omo/artifacts/mvp-trip-003/android/final-verification.json`; the preceding cover audit is `.omo/artifacts/mvp-trip-002/r2b/final-verification.json`.
- Auth API/schema absence: `backend/src/main/java/com/stog/backend/{AuthController,AuthService}.java` and `backend/src/main/resources/db/migration/{V1__basket_save_foundation,V2__kakao_opaque_auth}.sql`.
