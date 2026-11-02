# Native username/password auth + real app UI contract

Date: 2026-11-02  
Status: **PROPOSED / DECISION-COMPLETE / IMPLEMENTATION NOT STARTED**

## 1. Confirmed product decision and boundary

- Current sign-in choice is **username + password** (`아이디와 비밀번호`).
- Signup also collects a required display name.
- No email, SMS, paid identity service, external login, account merge, auth bypass, or public listener is part of this slice.
- Existing Kakao identity/session rows, historical evidence, domain data, and applied Flyway V1-V6 remain untouched.
- The Android current flow no longer presents or calls Kakao. The dormant backend Kakao endpoint may remain for data/history compatibility, but native users are separate accounts.
- A native signup never links to the preserved Kakao user. With no merge policy, the preserved Kakao-only account and its data remain dormant and are not reachable from the native account.

## 2. Repository evidence and smallest reusable path

Graphify was queried first, then conclusions were verified in source.

- `AuthService` already owns hash-only 256-bit opaque access/refresh tokens, 15-minute access, 30-day refresh inactivity, 90-day absolute session lifetime, refresh rotation/replay family revoke, current-session logout, and access authentication.
- `AuthController` already returns the required five-field token response with `Cache-Control: no-store` and `Pragma: no-cache`.
- `OpaqueAccessTokenFilter` converts only a valid DB-backed Bearer token into `AuthenticatedUser`.
- `AuthApiClient` already keeps access in memory, refresh in `SecureRefreshStore`, restores by refresh, performs one refresh/retry for protected calls, and drives the real trip/list/detail/search APIs.
- `MainFragment` gates the existing real `TripListFragment` on signed-in state. `TripViewModel.loadTrips()` calls the backend; a new user with no memberships receives the real empty state and create CTA.
- V1 defines `stog_user` with only a UUID. V2 adds Kakao identity and reusable session tables. V3-V6 add travel/catalog data without changing auth identity.
- Source and retained-development evidence show only V1-V6 applied. No V7 file exists.
- Read-only Gradle `dependencyInsight` completed with exit 0 and confirmed `spring-security-crypto:7.0.3` is already on the runtime classpath through Spring Boot 4.0.3. No new crypto dependency is needed.

Ponytail's reuse ladder supports this shape: reuse the existing session model, Android transport, platform autofill, and installed Spring encoder before adding any library or new auth framework.

## 3. Chosen credential design

### Username

- Request field: `username`.
- Accepted input: 4-32 ASCII characters.
- Canonical form: Unicode-edge trim, then `Locale.ROOT` lowercase.
- Canonical pattern: `^[a-z0-9][a-z0-9._-]{2,30}[a-z0-9]$`.
- Reserved canonical names include at least `admin`, `administrator`, `root`, `stog`, `support`, and `system`.
- The canonical value is the stored/login key. There is no separate case-preserving username.

This deliberately avoids Unicode-confusable username policy and a new PostgreSQL extension such as `citext`.

### Display name

- Request field: `display_name`.
- NFC normalize, trim Unicode edge whitespace, reject ISO controls.
- Required for native signup; 1-40 Unicode code points.
- Stored as nullable on `stog_user` so the existing preserved Kakao user requires no fabricated backfill.
- Native signup enforces non-null in service logic.

### Password

- Request field: `password`.
- NFC normalize on the server before blocklist comparison and hashing.
- Do not trim, lowercase, or apply composition rules.
- Accept spaces, paste, password managers, and Unicode.
- Minimum 15 Unicode code points; maximum 128 code points and 1024 UTF-8 bytes.
- Client-side confirmation is required in signup UI but `password_confirmation` is never sent to the server.
- Reject passwords found in a local repository-owned offline blocklist containing at least 10,000 common/compromised values, plus context-specific values such as the username, `stog`, and direct derivatives. The implementation records the list source, pinned revision, and license; there is no runtime breach/API lookup.

### Password storage

Use the already installed Spring Security `Pbkdf2PasswordEncoder`:

- PBKDF2-HMAC-SHA-256
- 16-byte random salt per password
- 600,000 iterations
- 256-bit derived hash
- no pepper in this slice
- stored through a `DelegatingPasswordEncoder` identifier such as `{pbkdf2-stog-v1}`

Reason: Argon2 would add an uninstalled provider/dependency; bcrypt has a 72-byte input boundary that conflicts with Unicode/passphrase support. PBKDF2 is already installed, accepts the full bounded password, and the chosen work factor matches current OWASP PBKDF2-HMAC-SHA-256 guidance. Implementation must record a local benchmark, but must not silently lower the 600,000-iteration contract. A future parameter change gets a new encoder id and opportunistic rehash after successful login.

## 4. Exact HTTP contract

All auth request bodies use `application/json`, reject unknown fields, duplicate keys, nulls, type coercion, and over-limit bodies, and never accept identity through headers/query parameters.

### `POST /api/auth/signup`

Request:

```json
{
  "username": "traveler01",
  "password": "<user-entered password>",
  "display_name": "여행자"
}
```

Success: `201 Created`

```json
{
  "token_type": "Bearer",
  "access_token": "<opaque>",
  "access_expires_in": 900,
  "refresh_token": "<opaque>",
  "refresh_expires_in": 2592000
}
```

Server behavior:

1. Validate/normalize username, display name, and password.
2. Check the offline password blocklist.
3. Derive the password hash before opening the write transaction.
4. In one transaction insert exactly one `stog_user`, one `auth_password_credential`, one `auth_session`, and one current `auth_refresh_token`.
5. A username collision rolls the entire transaction back.
6. Do not create a Trip, TripMember, Place, TripItem, fixture row, or Kakao identity.

### `POST /api/auth/login`

Request:

```json
{
  "username": "traveler01",
  "password": "<user-entered password>"
}
```

Success: `200 OK`, with the same five-field token response as signup/refresh.

Server behavior:

1. Normalize/validate the username and bound the password.
2. Load the native credential and throttle row.
3. Always perform one PBKDF2 verification. Unknown usernames use one process-start dummy hash generated with the same encoder.
4. Unknown username, wrong password, blocked username, native credential absence, and restricted-owner mismatch all return the same `401 AUTHENTICATION_REQUIRED` envelope and message.
5. A valid login clears the throttle row and inserts a new existing-style session/refresh family. It does not create or change the user.

### Existing endpoints, unchanged

- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- Bearer access on all protected routes

Their body, token response, rotation/replay behavior, current-session logout, expiration, headers, and Android Keystore/memory handling remain unchanged.

### Existing Kakao endpoint

`POST /api/auth/kakao/exchange` and V2 data remain unchanged for preservation. The Android production/current flow has no Kakao button, SDK initialization, callback activity, or call to this endpoint.

## 5. Error contract

All error responses retain the existing envelope:

```json
{
  "error": {
    "code": "AUTHENTICATION_REQUIRED",
    "message": "Authentication is required"
  }
}
```

| HTTP | code | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | malformed JSON, field/type/pattern/length/control-character failure |
| 400 | `PASSWORD_NOT_ACCEPTABLE` | password is too short/long or is on the offline blocklist; evaluated before username collision |
| 401 | `AUTHENTICATION_REQUIRED` | unknown username, wrong password, throttled username, native credential missing, or restricted-owner mismatch |
| 409 | `ACCOUNT_NOT_CREATED` | signup could not create a unique account; no partial user/credential/session |
| 503 | `AUTH_REGISTRATION_UNAVAILABLE` | signup attempted while restricted-owner mode is enabled |
| 503 | `DATABASE_BUSY` | existing lock/busy mapping |
| 503 | `DATABASE_OPERATION_FAILED` | existing generic database failure |

Login does not return `USERNAME_NOT_FOUND`, `WRONG_PASSWORD`, lock state, retry count, or `Retry-After`. Signup success is inherently distinguishable from collision in an immediate no-email flow; the generic `ACCOUNT_NOT_CREATED` response avoids naming an existing account but cannot eliminate that registration availability oracle.

All signup/login success and error responses use `Cache-Control: no-store` and `Pragma: no-cache`.

## 6. Abuse prevention

Add a durable throttle keyed by canonical username, including unknown usernames:

- five failed login attempts in one 15-minute window set `blocked_until = now + 15 minutes`;
- attempts while blocked still perform the dummy/real PBKDF2 verification and return the same generic 401;
- successful login deletes the throttle row;
- stale rows older than 24 hours are deleted opportunistically, at most 100 rows per login transaction;
- malformed usernames do not create throttle rows;
- no IP/device fingerprint is stored.

This is the current server-side online-guessing control. The listener remains `127.0.0.1`; no proxy, public bind, DNS, TLS, firewall, or external rate-limit configuration is approved here. Any future public listener requires a separate edge-rate-limit/TLS/recovery gate.

Credentials, hashes, Authorization values, password lengths, username/password pairs, and blocklist matches must not be logged, included in exception messages, screenshots, fixtures, command arguments, or evidence.

## 7. Additive V7 schema contract

Native auth owns the next free migration:

`backend/src/main/resources/db/migration/V7__native_password_auth.sql`

The unimplemented R3c trigram index must move from proposed V7 to V8 when that work resumes. Applied V1-V6 are never edited.

Logical V7:

```sql
ALTER TABLE stog_user
  ADD COLUMN display_name VARCHAR(40) NULL;

CREATE TABLE auth_password_credential (
  user_id UUID PRIMARY KEY REFERENCES stog_user(id) ON DELETE RESTRICT,
  username VARCHAR(32) NOT NULL UNIQUE,
  password_hash VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  password_changed_at TIMESTAMPTZ NOT NULL,
  CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,30}[a-z0-9]$'),
  CHECK (character_length(password_hash) BETWEEN 1 AND 255),
  CHECK (password_changed_at >= created_at)
);

CREATE TABLE auth_password_attempt (
  username VARCHAR(32) PRIMARY KEY,
  window_started_at TIMESTAMPTZ NOT NULL,
  failure_count SMALLINT NOT NULL CHECK (failure_count BETWEEN 1 AND 5),
  blocked_until TIMESTAMPTZ NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CHECK (blocked_until IS NULL OR blocked_until >= window_started_at)
);
CREATE INDEX auth_password_attempt_cleanup_idx
  ON auth_password_attempt(updated_at);
```

The migration must add a DB check that nullable `stog_user.display_name` has 1-40 characters when present. Existing rows remain null and unchanged. No `auth_identity`, auth session, domain FK, retained row, or provider subject is rewritten.

## 8. Restricted-owner compatibility

- Existing access/refresh `ownerAllowed` behavior remains.
- Native signup is fail-closed with `503 AUTH_REGISTRATION_UNAVAILABLE` while restricted-owner mode is enabled.
- Native login succeeds only if the credential's `user_id` equals the configured restricted owner; every other result is generic 401.
- Normal local startup remains the path for native signup/development.
- No operator credential-provisioning shortcut, default password, test principal, or auth bypass is added.

## 9. Android real UI contract

The native auth screen is a real server-backed gate, not a fixture:

- Configuration requires only `stog.server.baseUrl` / `STOG_SERVER_BASE_URL`.
- Remove Kakao from the current Android build/flow: SDK dependency, key build field, SDK initialization, callback activity, provider-specific states/text, and `KakaoLoginClient`.
- Preserve release HTTPS-only and debug literal `http://127.0.0.1[:port]` validation plus existing no-redirect HTTP behavior.
- Login mode fields: username, password.
- Signup mode fields: username, display name, password, password confirmation.
- Login autofill hints: `username`, `password`.
- Signup autofill hints: `newUsername`, `name`, `newPassword`.
- Password fields use `textPassword`, allow paste/password managers, offer show/hide, and set `saveEnabled=false`.
- Form mode, username, and display name may survive recreation; passwords and responses never enter SavedState, Fragment arguments, logs, screenshots, or ordinary preferences.
- `AuthApiClient` calls `/api/auth/login` and `/api/auth/signup`, then reuses current token acceptance, refresh store, logout, protected request, cancellation, and stale-callback logic.
- Signed-in state opens the existing real `TripListFragment`, which loads `/api/trips`. A new account sees the real empty state and can create its first trip.
- Remove the manual UUID TripItem diagnostic card from the normal product surface; focused tests may retain a non-production seam.
- Do not implement the planned five-tab shell, map, camera, CELL, Discover, MY, external login, or dummy home data in this auth slice.

## 10. Implementation ownership

Backend:

- `AuthController`: add signup/login DTOs and routes; reuse token response.
- `AuthService`: add native signup/login and extract one private session-issuance path shared with Kakao exchange; retain refresh/logout/access semantics.
- `SecurityConfig`: permit signup/login and define the custom PBKDF2 `PasswordEncoder` bean.
- `ApiErrorHandler` / `AuthException`: add only the listed native-auth errors.
- `application.properties`: no public address change; only bounded auth policy values if configuration is necessary.
- `V7__native_password_auth.sql`: additive schema above.
- `backend/src/main/resources/security/common-passwords.txt`: reviewed offline blocklist; no runtime network dependency.

Android:

- `AppConfig`, `StogApplication`, manifest, `app/build.gradle`: server-only auth configuration; remove current Kakao build/runtime surface.
- `AuthStateMachine`, `AuthViewModel`, `AuthApiClient`: credential submit/restore/logout states and calls; reuse session storage/transport.
- `MainFragment`, `fragment_main.xml`, strings/resources: login/signup form and real product transition.
- Delete provider-only `KakaoLoginClient` after its dependency is removed.

Tests:

- add focused backend native-auth tests rather than a second auth framework;
- adapt existing auth/UI fixtures from provider exchange to credential submit;
- preserve backend Kakao/session/domain regression tests; replace Android provider-SDK tests with native credential tests while retaining historical Kakao evidence.

## 11. Concrete verification plan

### Backend unit/web boundary

- malformed, duplicate-key, unknown-field, type-coercion, control-character, and exact size boundaries;
- username normalization/reserved names; display-name NFC/trim/code-point bounds;
- password 15/128 code-point and 1024-byte bounds, spaces, Unicode NFC, paste-equivalent values, blocklist;
- error headers/envelopes and no raw credential in captured output;
- unknown and known-user login both invoke the same encoder path using a mock/counting encoder.

### PostgreSQL integration through existing runner

- V1-V7 migration under generated non-superuser schema;
- signup atomically creates `stog_user + credential + session + current refresh`;
- stored hash has the expected encoder id and never equals/contains the password;
- duplicate/case-variant concurrent signup produces one account and no orphan user/session;
- late session insert failure rolls back user and credential;
- valid login creates a new session for the same user and does not create another user;
- unknown/wrong/blocked/owner-mismatch responses are identical 401;
- five-failure throttle, block window, success reset, unknown-username throttle, stale cleanup;
- existing refresh rotation/replay revoke/logout tests remain green;
- restricted-owner signup/login behavior;
- new native user receives `GET /api/trips` 200 with an empty list, creates one real trip, logs out, logs in, and reads the same trip;
- V7 leaves preserved Kakao identity/session/domain aggregates unchanged;
- full backend test gate and `bootJar`, generated role/schema cleanup `0|0`, media cleanup, and preexisting metadata unchanged.

Before applying V7 to retained `stog_backend_dev`, take a new private backup and record only aggregate before/after counts. Do not print usernames, hashes, user IDs, tokens, or credentials.

### Android

- JVM state tests: restore, login, signup, validation, stale callback, auth-ended, logout revoke-unconfirmed;
- HTTP instrumentation: exact signup/login JSON, 201/200 token acceptance, 400/401/409/503 mapping, malformed success rejection, redirect non-forwarding, refresh coalescing, logout cleanup;
- UI instrumentation: mode switching, field visibility, password confirmation local-only, show/hide, autofill hints, password not restored after recreation, generic login failure, signup success, empty real trip list, logout/private-state clear;
- exact changed gate: `:app:testDebugUnitTest`, selected `:app:connectedDebugAndroidTest`, `:app:lintDebug`, `:app:assembleDebug`;
- real local E2E on Pixel_8: emulator -> native signup -> Spring -> isolated PostgreSQL V1-V7 -> empty trips -> create trip -> logout -> native login -> same trip -> forced access expiry/one refresh -> final logout;
- assert zero Kakao provider/callback traffic and no provider dependency in the APK dependency graph;
- manual portrait/dark/font-1.30/recreation/Back/keyboard/autofill inspection and crash scan;
- remove owned app data, reverse, backend, generated role/schema, and temporary media; preserve retained development DB/data.

No fixed sleeps are allowed; tests subscribe to the exact state/request/event before triggering and use bounded timeouts.

## 12. User-only decisions

No further user decision blocks this local implementation:

- username + password is confirmed;
- display name is accepted as a routine signup field;
- no email/SMS, external login, merge, bypass, paid service, or public listener is confirmed.

Future real-user/public release still needs a user-owned decision for lost-password recovery and account deletion. Recommended current boundary: no reset endpoint and no security questions; a lost password is not recoverable in this slice. Any owner-assisted reset must be a separate audited local operation and must not merge the preserved Kakao account.

## 13. Primary references reviewed

- Spring Security 7 password storage: https://docs.spring.io/spring-security/reference/7.0/features/authentication/password-storage.html
- Spring Security `Pbkdf2PasswordEncoder`: https://docs.spring.io/spring-security/site/docs/7.0.x/api/org/springframework/security/crypto/password/Pbkdf2PasswordEncoder.html
- OWASP Password Storage Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html
- OWASP Authentication Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html
- NIST SP 800-63B-4 password authenticators: https://pages.nist.gov/800-63-4/sp800-63b/authenticators/
- Android Autofill optimization: https://developer.android.com/identity/autofill/autofill-optimize
- Android input method types: https://developer.android.com/develop/ui/views/touch-and-input/keyboard-input/style
- Ponytail minimal-change ladder: https://ponytail.dev/

## 14. Recommended implementation scope

Implement exactly V7 native credentials, the two new endpoints, reuse of current opaque sessions, the native Android auth gate, transition into the existing real trip UI, focused/full verification, and sanitized local E2E. Do not combine R3c, full shell redesign, password reset, account merge, public networking, or dormant Kakao data cleanup with this increment.
