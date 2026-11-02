# MVP-TRIP-005 R3c handoff

## RESULT

`CONTRACT APPROVED / BEHAVIORAL RED VERIFIED / BLOCKED / GREEN NOT_RUN`.

The exact R3c contract is in `docs/tasks/MVP-TRIP-005.md`: query length 2 remains supported; two-code-point queries are literal substring-only; 3-100 adds pg_trgm threshold 0.30; rank is exact -> prefix -> infix -> similarity, then score descending/name/UUID; cursor V2 binds the complete rank tuple and rejects V1.

No R3c production source or V7 migration remains in the tree. The accepted R3b prefix baseline was restored after the dependency probe.

## EVIDENCE

- Executable RED command:
  `Run-PostgresIntegration.ps1 -Tests com.stog.backend.PlaceCatalogHttpIntegrationTest.infixAndTypoQueriesFindTheExistingCommonPlace -SkipBootJar -ArtifactSubdirectory mvp-trip-005/behavioral-red`
  - exit 1;
  - current authenticated prefix search returned no first item for an existing infix Place;
  - evidence: `logs/behavioral-red.log`, `behavioral-red/test-runner-result.json`.
- Read-only dependency inspection:
  - `pg_trgm|1.6|1.6|public`;
  - `stog_app` public-schema USAGE: false;
  - `stog_backend_app` public-schema USAGE: false;
  - public schema ACL: null under the hardened database policy.
- Candidate migration probe:
  - V7 attempted only a partial GIN index using `public.gin_trgm_ops`; it did not create/install an extension;
  - canonical generated non-superuser Flyway startup failed `ERROR: permission denied for schema public`;
  - 9 selected tests discovered, 6 failed from the shared context startup; no GREEN claim;
  - evidence: `logs/focused-green.log`, `focused-green/test-runner-result.json`.
- Recovery verification:
  - candidate V7/ranked service/cursor/green tests removed;
  - restored baseline `compileJava compileTestJava` exit 0;
  - changed-file diagnostics 0;
  - generated invocation cleanup `0|0`, metadata unchanged, media root removed, port 8080 absent.
- Full R3c backend gate and representative real-sample `EXPLAIN ANALYZE` were not run because migration/runtime cannot legally resolve pg_trgm in the current privilege layout.
- Machine receipt: `.omo/artifacts/mvp-trip-005/blocker.json`.

## REMAINING PROBLEMS

- An approved owner-level extension-schema path is required. The safe recommendation is a dedicated extension schema with narrowly scoped USAGE for runtime and migration roles; broad public-schema USAGE was not granted and pg_trgm was not moved/reinstalled.
- After that prerequisite, R3c still requires V7, cursor V2, ranked HTTP GREEN, representative imported sample plans, one full backend gate and cleanup.
- Cross-source deduplication, quarterly refresh/closure, PostGIS spatial behavior, maps and imagery remain outside R3c/full-R3 backlog.
