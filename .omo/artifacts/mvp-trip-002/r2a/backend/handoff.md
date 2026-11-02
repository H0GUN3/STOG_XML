# MVP-TRIP-002 R2a backend handoff

## Scope delivered

R2a backend only. No Android, R2b cover/media V5, public host/TLS/provider installation, real-user data, canonical docs, or git commit was touched.

### HTTP endpoints

- `PUT /api/trips/{trip_id}`: ADMIN/MEMBER metadata edit, DB-date status, normalized no-op, scheduled-date conflict count.
- `GET /api/trips/{trip_id}/members`: ADMIN/MEMBER page, ADMIN-first UUID ordering, principal/trip/limit/version-bound 15-minute cursor.
- `PUT /api/trips/{trip_id}/members/{member_user_id}/role`: ADMIN role update/no-op and last-admin protection.
- `DELETE /api/trips/{trip_id}/members/{member_user_id}`: ADMIN removal, self-removal rejection, last-admin protection, 204 replay.
- `POST /api/trips/{trip_id}/leave`: MEMBER-only leave and 204 replay.
- `POST /api/trips/{trip_id}/invitation`: ADMIN issue/reissue, exact local URI, 10-minute expiry, 5/hour issue limit, hash-only identity, non-secret replay recipe.
- `DELETE /api/trips/{trip_id}/invitation`: ADMIN stop with `REVOKED|UNCHANGED` and no membership mutation.
- `POST /api/trip-invitations/join`: authenticated join/`ALREADY_MEMBER`, exact validation precedence, 10/principal and 50/token rolling limits, shared token-hash advisory serialization, trip-scoped label.
- `DELETE /api/trips/{trip_id}`: ADMIN soft delete, create-result invalidation, active invitation revoke, pre-delete callback recipients, 204 replay.

All command implementations use the R2 `(principal,trip,command,request_id)` ledger, SHA-256 length-prefixed canonical inputs, exact 24-hour DB-clock retention, post-lock permission/version checks, no failed ledger rows, and post-commit-only callbacks. Raw invitation tokens/URIs are not stored or logged by production code.

## Schema and R1 preservation

- Added only `V4__trip_collaboration_lifecycle.sql`; V1-V3 hashes remained:
  - V1 `b304a0b05cab4294b402b71474f9131da1b66748bd86fed2791d7308f5fa433a`
  - V2 `1b0162a56c8c163a3348f7ed63a1ed60c2567177ee6f0589f23f79af4401c234`
  - V3 `b6079d26454442b1373b65650e46a62218427c7c15f595edd7d784e60a2ab206`
  - V4 `c3dd97b743c1b34a9e567e0cdda91eeb48a90923332c984a70562a9952328cad`
- V4 adds soft-delete/create invalidation fields, `member_label`, invitations, R2 execution ledger, rate attempts, and active indexes.
- Existing My Trips/detail/basket/current-item/basket-write paths now exclude soft-deleted trips. My Trips still continues from the signed raw cursor tuple without anchor lookup or generation invalidation.
- Existing create replay returns `REQUEST_RESULT_NO_LONGER_AVAILABLE` while an invalidated unexpired result exists.
- Existing user/auth rows, DPAPI/settings, and real/shared rows were not changed. All DB writes were in generated isolated schemas.

## Tests and fixtures

### Test classes

- New: `TripCollaborationPostgresIntegrationTest` (11 deterministic tests).
- Extended: `TripProductControllerTest` (initial HTTP RED and GREEN).
- Extended: `AuthPostgresIntegrationTest` (two distinct normal synthetic-provider identities -> opaque Bearer -> create/invite/join/MEMBER edit through the security filter).
- Updated fixture safety: `BasketSavePostgresIntegrationTest.DatabaseSafetyGate` recognizes additive V4 tables.
- Related regression classes run: `TripProductPostgresIntegrationTest`, `BasketSavePostgresIntegrationTest`, `TripProductControllerTest`, `BasketSaveControllerTest`, `TripCursorCodecTest`, `AuthenticationBoundaryTest`.

### Focused evidence

- RED: `logs/red-http-metadata-edit.log` - compiling authenticated PUT behavior failed before production route implementation.
- R2a PostgreSQL GREEN: `logs/focused-postgres-green.log` - 11/11.
- Related R1/auth boundary GREEN: `logs/focused-related-green.log` - 62/62 across seven classes.
- Full auth PostgreSQL GREEN: `logs/auth-postgres-green.log` - 14/14, including normal synthetic-provider R2a Bearer flow.
- Summary: `red-green.md`.
- Latest isolation receipt: `test-runner-result.json` - generated non-superuser/private schema, V1-V4 migration, cleanup success, remaining role/schema `0|0`, pre-existing metadata unchanged, no password output.
- LSP diagnostics: zero diagnostics on every changed Java source/test file after final edits.

### Schema setup guidance

Use the existing owned runner; do not point tests at a shared or real schema:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File backend/src/test/scripts/Run-PostgresIntegration.ps1 `
  -Tests com.stog.backend.TripCollaborationPostgresIntegrationTest `
  -SkipBootJar `
  -ArtifactSubdirectory mvp-trip-002/r2a/backend
```

The runner creates a generated non-superuser role and same-name private schema in `stog_backend_test`, sets datasource/Flyway properties, applies V1-V4, then verifies ownership before dropping only that generated schema/role.

## Decisions and assumptions

- Used a transaction-scoped PostgreSQL advisory lock derived from the first 64 bits of the canonical token SHA-256 for cross-principal unknown-token quota serialization. This avoids storing raw tokens and closes the token-hash rate race; an unlocked count/index-only alternative was rejected.
- Kept member labels only on `trip_member`; no account profile/email was invented.
- Invitation issue replay reconstructs the URI only from the caller-resubmitted raw token plus stored hash/expiry recipe; no recoverable server token storage was added.
- Delete events carry the pre-delete member UUID set. Other event constructors remain source-compatible and use an empty explicit recipient list.

## Remaining / verifier-owned

- Full backend suite and `bootJar` were intentionally `NOT_RUN` here; the verifier owns the one final gate.
- Actual Android/two-user UI flow, device QA, and final R2a E2E artifact remain verifier/Android-lane work.
- R2b private cover/media V5 remains the next sequential increment and was not dropped or started.
- `.omo/artifacts/mvp-trip-002/contract-verification.md` in the repository still contains an older `BLOCKED` report, while the parent task explicitly states contract verification is PASS and `docs/tasks/MVP-TRIP-002.md`/PLAN mark the contract approved. No canonical or verifier-owned artifact was rewritten by this producer.
