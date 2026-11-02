# MVP-TRIP-004 R3b handoff

## RESULT

`IMPLEMENTED / LOCAL VERIFIED` for the bounded SEMAS CSV ingestion slice.

- Added source-specific streaming CSV parsing and V6 upsert with no new dependency or migration.
- Fixed source identity: `SEMAS_STORE + 상가업소번호`; rerun/update preserves `place.id`.
- Mapped name, smallest category name, full province, both addresses and WGS84 coordinates rounded HALF_UP to six decimals. Branch and smallest-category code remain aggregate report-only because V6 has no dedicated columns.
- Entry-atomic temp staging uses at most 500 rows per JDBC batch. It rejects malformed CSV, invalid required fields/coordinates and duplicate source IDs, and rolls back the whole entry.
- It never deletes absent rows, infers closures, name-merges Places, modifies Android, installs extensions, calls an external service or writes a retained database.

Changed implementation paths:

- `backend/src/main/java/com/stog/backend/SemasPlaceImporter.java`
- `backend/src/main/java/com/stog/backend/SemasPlaceImportCommand.java`
- `backend/scripts/Import-SemasPlaces.ps1`
- `backend/build.gradle`
- focused tests and `backend/src/test/scripts/Run-SemasImportVerification.ps1`
- canonical TASK/PLAN/BACKEND/DATABASE evidence only

## EVIDENCE

- Implementation-absence/scaffold check: `./gradlew.bat -p backend test --tests com.stog.backend.SemasPlaceImporterTest`, exit 1, compiled reflective test observed absent class through `ClassNotFoundException`. This is not CSV create/update behavioral RED; preimplementation behavioral import RED is `NOT_VERIFIED`. Historical log preserved at `.omo/artifacts/mvp-trip-004/logs/behavioral-red.log`.
- Focused generated PostgreSQL: `Run-PostgresIntegration.ps1 -Tests 'com.stog.backend.SemasPlaceImporter*' -SkipBootJar`, exit 0, 6 tests. `.omo/artifacts/mvp-trip-004/focused-green-2/`.
- Official bounded run: `Run-SemasImportVerification.ps1`, exit 0. Exact official ZIP size/hash verified; 제주 entry first 25 rows produced `25|25|25`, repeat remained `25|25|25`, stable identity true, authenticated search/detail exact-ID true, external calls 0. First report: inserted 25, branches 5, distinct category codes 14, review duplicate groups 0; repeat: inserted 0/updated 25. `.omo/artifacts/mvp-trip-004/real-subset/`.
- Full backend gate: `Run-PostgresIntegration.ps1 -ArtifactSubdirectory mvp-trip-004/full-gate`, exit 0; 122 tests, 0 failure/error/skipped, bootJar PASS. `.omo/artifacts/mvp-trip-004/full-gate/`.
- Diagnostics: zero on all changed Java files.
- Cleanup: generated role/schema `0|0`, private media root removed, preexisting metadata and task-prefix counts unchanged, port 8080 absent. No retained catalog was populated.
- Read-only extension observation: pg_trgm default/installed `1.6/1.6`; PostGIS default `3.6.2`, installed empty. No CREATE EXTENSION or installation occurred.

## REMAINING PROBLEMS

- The bounded 25-row evidence is not national production population.
- One snapshot cannot prove source-key disappearance/reassignment, closure policy or quarterly refresh recovery.
- Cross-source deduplication remains unimplemented; distinct source keys are intentionally separate/reviewable.
- Tourism supplement, production scheduling/operations, pg_trgm behavior, PostGIS spatial behavior, representative imagery and shared map/detail remain full-R3 work.
