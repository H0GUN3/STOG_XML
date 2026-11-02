# MVP-TRIP-005 pg_trgm owner-approval packet (READ-ONLY)

No statement in this packet was executed. No grant, revoke, extension install/move, schema change, table DDL or table DML occurred.

## Result

A single `GRANT USAGE ON SCHEMA public` is **not sufficient for both databases**:

- `stog_backend_test`: pg_trgm `1.6` is installed in schema `public`; a temporary invocation-role USAGE grant would technically expose its functions/operators/operator classes and allow the candidate V7 migration.
- `stog_backend_dev`: pg_trgm is **not installed at all** (`pg_extension` contains only `plpgsql`). Granting USAGE on `public` cannot provide missing pg_trgm objects.

The local launcher confirms the actual development application login is role `stog_backend_app`, and its application schema is separately named `stog_app`. The schema is not a role. A cluster role also named `stog_app` exists, but the launcher does not use it and this packet proposes no grant to it.

## Exact observed metadata

### `stog_backend_test`

- Database owner: role `stog_backend_test`.
- `public` schema owner: role `stog_backend_test`; observed ACL is null, while direct privilege checks for `stog_backend_app` and `stog_app` both report USAGE=false.
- No schema named `stog_app` exists in this database.
- pg_trgm: installed version/default `1.6`, extension schema `public`, extension owner `stog_backend_test`.
- Extension members: 31 functions, 10 operators, 2 operator classes (`gin_trgm_ops`, `gist_trgm_ops`), 2 operator families and 2 types.
- All 31 pg_trgm functions have default PUBLIC EXECUTE; none is SECURITY DEFINER.
- `public` also contains 21 non-extension PUBLIC-executable functions: one itinerary version trigger, five mutation guards/rejectors, five photo/visit/receipt immutability functions, and ten catalog validation helpers. One is SECURITY DEFINER: `public.advance_itinerary_version_from_history()` owned by `stog_backend_test`, with fixed `search_path=pg_catalog` and dynamic updates against `TG_TABLE_SCHEMA` when invoked as a trigger. PostgreSQL rejects direct calls to a trigger-returning function, but schema USAGE allows the invocation role to resolve it when defining triggers on objects it owns.
- Other public-schema catalog objects: 45 tables, 26 sequences and 133 indexes. Schema USAGE alone grants no table/sequence DML privilege. The actual development role `stog_backend_app` currently has zero SELECT/INSERT/UPDATE/DELETE privileges on those public tables. The similarly named but unrelated `stog_app` role has historical public-table grants and must not be treated as the application role.
- Test migrations run as a newly generated role `stog_m1_002_<32 hex>` owning a same-named generated schema. That role does not exist before an invocation and is removed afterward.

### `stog_backend_dev`

- Database owner: `postgres`.
- Actual application login: `stog_backend_app` (LOGIN, non-superuser, no CREATEDB/CREATEROLE).
- Application schema: `stog_app`, owned by role `stog_backend_app`; that role has USAGE/CREATE on this schema.
- The distinct role `stog_app` is not the launcher role and has no USAGE on schema `stog_app`.
- `public` schema owner: `pg_database_owner`; ACL grants only `pg_database_owner=UC`. `stog_backend_app` has public USAGE=false.
- pg_trgm is absent. `public` contains zero functions and no reported relation objects in the inspected classes.

## Grant-only proposal for the existing shared test database

This is the exact smallest statement that would unblock a single canonical generated test invocation, but it broadens function lookup as described below. Replace the placeholder only with the runner-created, regex-validated invocation role.

```sql
-- Target database: stog_backend_test
-- Target role: the current invocation-owned stog_m1_002_<32 lowercase hex> role only
GRANT USAGE ON SCHEMA public TO "stog_m1_002_<invocation_hex>";
```

Do **not** grant this to `stog_backend_app` or `stog_app` in `stog_backend_test`; neither is the invocation migration role.

Changed permission scope:

- Adds only schema lookup/USAGE; it adds no CREATE on `public`, no database privilege, no table/sequence DML, and no function ACL.
- Because 52 public functions already grant EXECUTE to PUBLIC, schema lookup makes all 52 callable by the invocation role: 31 pg_trgm functions plus 21 unrelated functions, including the one SECURITY DEFINER trigger function above.
- It also makes pg_trgm operators/types/operator classes resolvable, including `public.gin_trgm_ops` required by candidate V7.

This collateral function reachability means the grant is technically sufficient for tests but not narrowly limited to pg_trgm objects. PostgreSQL has no object-level USAGE privilege for an operator class that bypasses its containing schema.

### Read-only validation after an approved grant

```sql
-- Target database: stog_backend_test
SELECT current_database();
SELECT has_schema_privilege('stog_m1_002_<invocation_hex>', 'public', 'USAGE');
SELECT has_function_privilege(
  'stog_m1_002_<invocation_hex>',
  'public.similarity(text,text)',
  'EXECUTE'
);
SELECT opc.opcname, am.amname, n.nspname
FROM pg_opclass opc
JOIN pg_am am ON am.oid = opc.opcmethod
JOIN pg_namespace n ON n.oid = opc.opcnamespace
WHERE n.nspname='public' AND opc.opcname IN ('gin_trgm_ops','gist_trgm_ops');
```

Expected: database `stog_backend_test`, both privilege booleans true, and both pg_trgm operator classes present. Then the normal generated V1-V7 migration/test run is the behavioral validation; no retained rows are used.

### Invocation-owned rollback/cleanup

Rollback is limited to the newly granted privilege, then the runner's existing owned cleanup:

```sql
-- Target database: stog_backend_test
REVOKE USAGE ON SCHEMA public FROM "stog_m1_002_<invocation_hex>";
-- Existing runner then drops the same-named owned schema and generated role.
```

Post-checks:

```sql
SELECT has_schema_privilege('stog_m1_002_<invocation_hex>', 'public', 'USAGE'); -- false, before role drop
SELECT count(*) FROM pg_roles WHERE rolname='stog_m1_002_<invocation_hex>';     -- 0 after cleanup
SELECT count(*) FROM pg_namespace WHERE nspname='stog_m1_002_<invocation_hex>'; -- 0
```

The runner must continue proving preexisting metadata unchanged and private media-root cleanup.

## Why no grant-only development statement is proposed

There is no pg_trgm extension in `stog_backend_dev`. Therefore this would be ineffective and is **not proposed**:

```sql
-- NOT SUFFICIENT; DO NOT EXECUTE as an R3c fix
GRANT USAGE ON SCHEMA public TO stog_backend_app;
```

Under the current no-CREATE constraint, R3c cannot run in the actual local development database. The exact missing owner-level dependency is an initial pg_trgm installation in that database. Since pg_trgm is absent there, this would not be relocation/reinstallation, but it is still a persistent extension CREATE and requires a separate explicit approval:

```sql
-- SEPARATE FUTURE APPROVAL ONLY; NOT EXECUTED
-- Target database: stog_backend_dev
CREATE EXTENSION pg_trgm WITH SCHEMA stog_app;
```

Installing into existing application schema `stog_app` avoids granting `stog_backend_app` visibility over unrelated `public` objects. No extra schema grant is needed because `stog_backend_app` owns `stog_app`. Validation would be read-only metadata plus a similarity SELECT as that role. Rollback, before any dependent V7 index is retained, would be `DROP EXTENSION pg_trgm;`. This persistent action is intentionally outside the present grant-only packet.

## Viable no-shared-change isolated testing alternative

Instead of granting access to the shared `stog_backend_test.public`, an owner-approved runner can create a disposable database, create its invocation-owned schema, install pg_trgm into that schema, run V1-V7/tests/plans, then drop the entire database and role. This avoids exposing the 21 unrelated shared-public functions and does not alter either retained database. It still requires temporary owner CREATE DATABASE/CREATE EXTENSION operations and was not executed.

Command-level outline, with generated validated identifiers and a generated credential omitted from evidence:

```sql
-- Connected to postgres as owner; generated names only
CREATE ROLE "stog_r3c_<hex>" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION PASSWORD '<process-only>';
CREATE DATABASE "stog_r3c_<hex>" OWNER postgres ENCODING 'UTF8' TEMPLATE template0;
-- Reconnect to database stog_r3c_<hex>
REVOKE CONNECT, TEMPORARY ON DATABASE "stog_r3c_<hex>" FROM PUBLIC;
GRANT CONNECT ON DATABASE "stog_r3c_<hex>" TO "stog_r3c_<hex>";
CREATE SCHEMA "stog_r3c_<hex>" AUTHORIZATION "stog_r3c_<hex>";
CREATE EXTENSION pg_trgm WITH SCHEMA "stog_r3c_<hex>";
```

Owned cleanup after tests disconnect:

```sql
-- Connected to postgres as owner
DROP DATABASE "stog_r3c_<hex>";
DROP ROLE "stog_r3c_<hex>";
```

Required cleanup evidence: database/role/schema count zero, preexisting database/role metadata unchanged, no listener/process/media residue.

## Approval boundary

No persistent shared permission change should be requested as though it fully solves R3c. A user/owner decision is needed separately for:

1. whether to permit the temporary shared-test `GRANT USAGE` despite unrelated PUBLIC-executable function reachability, or instead authorize the disposable-database test runner; and
2. whether to permit the separate initial pg_trgm installation into `stog_backend_dev.stog_app` for the actual local runtime.

The removed candidate remains reviewable at `.omo/artifacts/mvp-trip-005/candidate/`; no failing R3c regression was weakened to claim baseline GREEN. Current compile evidence is baseline-only, while R3c GREEN remains blocked by this prerequisite.
