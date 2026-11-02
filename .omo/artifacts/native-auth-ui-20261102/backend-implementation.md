# NATIVE-AUTH-UI-001 backend implementation evidence

Date: 2026-11-02  
Scope: backend, additive V7, retained local migration, loopback runtime handoff

## RESULT

- Native `POST /api/auth/signup` and `POST /api/auth/login` are implemented.
- PBKDF2-HMAC-SHA-256 uses 600,000 iterations, 16-byte random salt, 256-bit
  output and `{pbkdf2-stog-v1}` storage through installed Spring Security
  Crypto 7.0.3.
- V7 adds nullable legacy-safe display name, native credential and durable
  known/unknown username throttle tables. V1-V6 were not edited.
- Existing opaque access/refresh, replay revoke, current-session logout,
  restricted-owner behavior, Kakao rows and protected principal filter are
  reused.
- Local startup now supplies a persistent private non-reparse media root and
  remains exact loopback.

## TEST EVIDENCE

- Focused non-DB security/web/launcher tests: PASS.
- Focused isolated PostgreSQL native + Kakao session + restricted-owner gate:
  PASS after updating the canonical additive table safety set; cleanup `0|0`.
- Full canonical isolated runner:
  - 22 test classes
  - 135 tests
  - failures 0
  - errors 0
  - skipped 0
  - `bootJar` PASS
  - generated role/schema remaining `0|0`
  - preexisting metadata unchanged
  - generated media root removed
- Runner receipt:
  `full-gate/test-runner-result.json`.

The initial focused command-line attempt failed before DB access because the
PowerShell array parameter was passed incorrectly. The first actual focused DB
run then failed closed because the established safety gate still listed only
V1-V6 tables; it cleaned its generated resources. Adding the exact two V7
tables to that safety set produced the focused green and final full green.

## RETAINED DATABASE

- Preflight Flyway: V1-V6 success.
- Preflight existing aggregate:
  `1 user | 1 Kakao identity | 0 login exchanges | 2 sessions | 2 refresh rows |
  0 trip/domain rows`.
- New private backup:
  `/var/lib/postgresql/stog-private-backups/stog_backend_dev-before-native-auth-20261102-062012.dump`
  - owner `postgres:postgres`
  - mode `600`
  - size `51955`
  - SHA-256
    `50d822c58d35dc9c37eed793f46dba79eb8885799c55bb909664999ac45753ed`
  - `pg_restore --list` PASS
- V7 applied once through `Start-LocalBackend.ps1`.
- Post-migration before QA data:
  Flyway V1-V7 success; original user, Kakao identity and both session
  fingerprints exactly matched preflight; existing domain counts remained zero.
- Restore was not run.

## REAL LOOPBACK FLOW

- Binding: only `127.0.0.1:8080`.
- Normal configuration: restricted-owner disabled; Kakao provider disabled by
  default; no real Kakao call or exchange row.
- Fresh nonpersonal native account:
  - signup `201`
  - initial real My Trips empty
  - authenticated trip create `201`
  - first logout `204`
  - native login `200`
  - same persisted trip visible
  - final logout `204`
- Current retained shape:
  `2 users | 1 preserved Kakao identity | 1 native credential | 1 trip |
  1 membership | 4 sessions`, with the two native QA sessions revoked.
- Original Kakao user/identity/preexisting-session fingerprints remain exact.

## FRONTEND HANDOFF

- Backend is running in the task-owned persistent terminal and must remain
  available for frontend QA.
- Base URL: `http://127.0.0.1:8080`.
- Persistent media root exists under same-user local application data and is
  not a reparse point.
- Fresh QA credentials are stored only as a same-user DPAPI `SecureString`:
  `%LOCALAPPDATA%\STOG\local-backend\native-qa-account.clixml`.
- Protected content format after same-user decryption is two lines:
  canonical username, then password. The values must be consumed in-process
  and never printed, logged, screenshotted or copied into repository evidence.
- Android source/UI/device verification is not claimed by this backend result.

## REMAINING

- Frontend integrates native auth and the real Home + My Trips two-destination
  shell, then performs final Android QA against this runtime.
- Password recovery, account deletion, public listener/TLS and R3c V8 remain
  outside this increment.
