# MVP-TRIP-003 R3a backend handoff

RESULT: R3a backend is implemented and locally verified. V6 extends the same common Place with nullable legacy-safe catalog/source/location fields and source uniqueness. Authenticated real HTTP search/detail, compact authenticated cursor, exact errors/private headers, and existing basket command integration are implemented. No importer, CSV processing, production rows, map, image, PostGIS/pg_trgm, provider, account, dependency or R4 work was added.

WIRE: `GET /api/places` uses normalized-name prefix plus optional exact normalized region/category, limit 1-50, keyset order `(normalized_name,id)`, and a 15-minute principal/filter-bound cursor. Cursor maximum is corrected to 4096 ASCII after actual maximum-Unicode codec proof. `GET /api/places/{place_id}` returns exact text/coordinate detail; legacy ID-only and missing rows share `PLACE_NOT_FOUND`. Existing basket POST is unchanged.

CHANGED PRODUCTION: `V6__common_place_catalog.sql`; `PlaceCatalogController`, `PlaceCatalogService`, `PlaceCatalogException`, `PlaceCursorCodec`; narrow `ApiErrorHandler` JSON mapping. Tests: `PlaceCatalogHttpIntegrationTest`, `PlaceCursorCodecTest`. Narrow canonical correction: TASK/API cursor cap 1024 -> 4096 and WGS84 six-decimal fixture/source facts.

EVIDENCE: behavioral RED exit 1; focused GREEN 29/29; all changed Java files LSP 0 diagnostics; canonical isolated V1-V6 PostgreSQL+bootJar exit 0, 116/116, failures/errors/skips 0/0/0. Cleanup role/schema `0|0`, generated media root removed, preexisting metadata/prefix counts unchanged. See `red-green.md`, `verification-summary.json`, `cleanup-receipt.json` and `full-gate/test-runner-result.json`.

ANDROID NOTE: `.omo/artifacts/mvp-trip-003/backend/android-integration-note.md` records stable DTO/cursor/schema facts for worker `st_01a10fdb`. Current app ViewModel is parent-fragment scoped; Android must preserve that actual ownership rather than move it for stale TASK wording.

REMAINING: Android implementation and generated local end-to-end acceptance are not owned here. Full R3 still owes production SEMAS mapping/ingest/dedup/refresh, representative image, normative pg_trgm/PostGIS behavior and shared map/detail. The ignored official ZIP remains untouched for the later mapping lane.
