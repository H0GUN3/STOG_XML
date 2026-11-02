# DB-CONNECTIVITY-SETUP-001: local PostgreSQL R3c prerequisite setup

## 1. Identity and status

| Item | Value |
| --- | --- |
| Authorization | User-requested local DB setup and connectivity audit |
| Scope | Retained dev backup, V3-V6 migration, contract-compatible pg_trgm, bounded shared-test capability proof |
| Status | COMPLETE |
| Verification | LOCAL VERIFIED |
| Product slot | Unchanged: `MVP-TRIP-005` |
| Excluded | V7/product search implementation, retained catalog rows, broad grants, public network, destructive cleanup |

## 2. Decisions and changes

The frozen R3c contract qualifies `public.similarity` and
`public.gin_trgm_ops`. The dev `public` namespace was inspected before setup and
contained zero unrelated relations, functions, types, operators, or operator
classes. Therefore the compatible least-privilege setup was:

```sql
CREATE EXTENSION pg_trgm WITH SCHEMA public;
GRANT USAGE ON SCHEMA public TO stog_backend_app;
```

No PUBLIC/all-role grant was made. `stog_backend_app` still lacks public
CREATE, database CREATE/TEMPORARY, superuser, CREATEDB, CREATEROLE, and
replication privileges. The unrelated `stog_app` role still lacks public
USAGE.

Before the database write, `stog_backend_dev` was saved as a postgres-owned,
mode-600 custom-format archive in a postgres-owned, mode-700 private directory.
`pg_restore --list` verified archive readability. Restore was not tested.

The established `Start-LocalBackend.ps1` launcher applied only existing V3-V6.
`STOG_MEDIA_ROOT` was supplied process-locally with an owned private temporary
directory; no launcher or product configuration was changed. The backend was
stopped and that media directory removed after loopback verification.

`Verify-R3cDatabaseCapability.ps1` is a setup-only verifier. It creates one
regex-validated generated role/schema, grants temporary public USAGE, proves
qualified similarity and GIN operator-class access, revokes the grant, and
drops only its own schema/role.

Final review correction added the original runner's password-bearing
provisioning protections:

```sql
SET log_statement='none';
SET log_min_error_statement='panic';
```

It also adopted the original runner's exact-generated-state recovery pattern.
Cleanup ownership is now established from the committed role/schema/flag state,
not only from process acknowledgement. A test-only switch simulates
post-COMMIT acknowledgement loss after successful provisioning.

## 3. Preservation and security

- Existing dev identity/session aggregates and every pre-change retained table
  count were unchanged after V3-V6.
- No retained Place, Trip, TripItem, invitation, cover, or command row was
  added.
- Backend and PostgreSQL listeners remained loopback-only.
- Credential and internal user identity values were not printed or written to
  evidence.
- Four preexisting generated test role/schema pairs were preserved. They have
  no active session, membership, or owned relation.
- The first test grant/revoke converted a null `public` ACL representation to
  explicit owner-only `{stog_backend_test=UC/stog_backend_test}`. Effective
  app/generated access remains denied after cleanup. Later verifier runs
  preserve this ACL exactly.

## 4. Acceptance evidence

| Acceptance | Result |
| --- | --- |
| Private backup before mutation | PASS: mode 600, owner postgres, one archive |
| Archive readability | PASS: `pg_restore --list` exit 0, 52 entries |
| pg_trgm contract schema/version | PASS: `1.6`, `public` |
| Exact app privilege | PASS: USAGE=true, CREATE=false, no elevated/database-create flags |
| Flyway | PASS: V1-V6 success |
| Retained data | PASS: counts and identity/session aggregates unchanged |
| App-role trigram | PASS: qualified similarity and two operator classes |
| Backend surface | PASS: `127.0.0.1:8080`, protected 401 |
| Runtime cleanup | PASS: port 8080 zero, owned media root absent |
| Shared-test generated proof | PASS: non-superuser, similarity, GIN index |
| Generated cleanup | PASS: owned role/schema `0|0`, residue fingerprint unchanged |
| Normal verifier review run | PASS: exit 0, acknowledgement recovery=false |
| Simulated post-COMMIT acknowledgement loss | PASS: exit 0, recovery=true, cleanup `0|0` |
| Credential-statement local log scan | 0 file matches, 0 journal matches; external/historical collectors not proven absent |
| Broad/public grant | NONE |

Sanitized command results and exact backup metadata are in
`.omo/artifacts/db-connectivity-20261102/setup-verification.md`.

## 5. Remaining boundary

Database prerequisites are locally ready, but R3c product implementation is
not. V7, ranked search/cursor source, focused/full GREEN, representative
`EXPLAIN ANALYZE`, and runner orchestration remain `NOT_RUN` or absent.
Existing test residue cleanup and backup restore testing require separate
authorization. The single current product slot remains `MVP-TRIP-005`.
