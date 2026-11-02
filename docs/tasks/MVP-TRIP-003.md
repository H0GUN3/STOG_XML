# MVP-TRIP-003: R3a common Place catalog foundation

## 1. Identity and status

| Item | Value |
| --- | --- |
| Product increment | R3a, partial R3 foundation |
| Product basis | v1.4 sections 4.7, 5.4, 5.8, 7, 8 |
| Contract | APPROVED / DECISION-COMPLETE |
| Implementation | BACKEND + ANDROID IMPLEMENTED |
| Verification | R3a LOCAL VERIFIED |
| Prerequisite | MVP-TRIP-002 R2 LOCAL VERIFIED; existing authenticated basket save |
| Next single slot | Full-R3 ingest/search/spatial/map contract only; R3a is closed |

This TASK is the canonical contract for the first R3 increment. R3a is intentionally implementable without a production catalog or map provider, but it is **not full R3**. Full R3 remains incomplete until an approved real dataset is ingested/normalized and the shared map/Place detail obligations are implemented and verified.

## 2. Evidence and current gap

- `place` currently has only `id UUID PRIMARY KEY`; V1-V5 contain no catalog display fields, source identity, coordinates, search index, ingest path or search/detail endpoint.
- Existing Place rows in tests are generated ID-only fixtures. They are not production catalog data and remain valid references for existing TripItems.
- Existing `POST /api/trips/{trip_id}/trip-items` already owns current membership, ongoing/upcoming state, expected plan version, stable request ID, atomic TripItem/version/result and replay semantics. R3a reuses it unchanged.
- Android already owns selected trip ID, current detail/version, authenticated refresh-once transport, Fragment back stack and basket reload. It has no Place catalog client or UI.
- v1.4 §4.7 requires one common Place across search result, shared map marker/detail and basket; result cards include representative-image fallback, name, category and address/region, Back preserves query/results/scroll, and search alone triggers neither AI nor CELL.
- v1.4 §5.4 makes search, STOBEE, discovered posts and external-link confirmation converge on one Place/TripItem save; no trip context requires trip selection, while trip-basket entry preserves that trip and server rechecks membership/state.
- v1.4 §7 adopts server-collected/normalized public data in own PostgreSQL. It names the Small Enterprise and Market Service commercial-area CSV as the base, Tourism Organization data as a supplement, source identity plus name/category/description/address/coordinates/image as normalization fields, pg_trgm for partial/similarity name search, and PostGIS for spatial/map queries. It requires deduplication and measured query plans.
- v1.4 Appendix C preserves E25-E27 as technical references and says prior price/terms are not thereby reverified; it does not downgrade the §7 product obligations. Latest user direction selects the **free public-data category**, but no exact licensed snapshot, production ingest, account or provider connection is approved yet.
- Current domain rules supersede the historical roadmap where implementation state differs: common Place/TripItem and existing basket command are implemented; external provider/map/extension work still requires its own approved evidence and cannot be inferred from a proposal.

## 3. Included, excluded and full-R3 debt

### Included in R3a

- Additive V6 display/search fields on the existing common `place` table while preserving legacy ID-only rows.
- Authenticated bounded engineering search seam using normalized name prefix plus optional exact region/category filters. This is an R3a limitation, not the full v1.4 partial/similarity search requirement.
- Authenticated Place text detail.
- Portrait Java/XML `TripDetail -> PlaceSearch -> PlaceDetail` flow with query/result/scroll/recreation recovery.
- Explicit PlaceDetail basket action that invokes the existing basket-save endpoint with the selected trip context.
- Generated isolated V1-V6 fixtures and local Android -> Spring -> PostgreSQL acceptance.

### Excluded from R3a

- Production catalog rows, network ingestion, source-specific parser/refresh, provider account/key and external calls.
- Map SDK, marker/map rendering, routing, current GPS, radius/viewport search.
- Representative image fetch/cache/storage. R3a renders a local missing-image placeholder only.
- Required full-R3 pg_trgm partial/similarity behavior, plus initial-consonant, semantic search, autocomplete and recent queries.
- Required full-R3 PostGIS shared spatial/map behavior, plus Elasticsearch, Redis or any new dependency/framework.
- R4 basket delete/schedule/reorder/SSE, Instagram, AI/STOBEE, CELL/EXP/Record/Post and public deployment.

### Full R3 remains incomplete until

1. one approved real source is acquired under verified terms and zero-cost conditions;
2. source-specific ingest, normalization, source uniqueness, deduplication and refresh are implemented;
3. normative pg_trgm partial/similarity name search with region/category combination is implemented and measured on real rows; and
4. normative PostGIS spatial queries and the shared map/marker/Place detail path are implemented with an approved zero-cost map path.

A fixture-only R3a PASS cannot mark R3 complete.

## 4. Canonical text and search normalization

Display text (`name`, category, region, addresses, description) is NFC normalized, trims Unicode whitespace only at both edges, rejects ISO control characters, and preserves internal user/source text. Required catalog `name` is 1-200 Unicode code points after normalization.

Search keys and request filters use a separate deterministic transform:

1. apply the display normalization;
2. lowercase with `Locale.ROOT`;
3. replace each internal run of Unicode whitespace with one ASCII space.

`query` must be 2-100 code points. Optional `region` and `category` are 1-100. The server escapes literal `\`, `%` and `_` before SQL and uses a typed bind value with exact `LIKE ? ESCAPE '\'`; client text is never a SQL fragment. **R3a engineering behavior is normalized-name prefix only**: `normalized_name LIKE escaped_query || '%'`. Region/category compare exact normalized keys. This bounded behavior proves the wire/state/common-identity seam but does not satisfy or supersede v1.4 §7.3 pg_trgm partial/similarity search. Short-query, ranking and initial-consonant policy remain full-R3 work.

## 5. Exact HTTP contract

All routes require the existing `Authorization: Bearer <opaque STOG access token>`. Catalog reads are available to any authenticated principal and do not take `user_id`, role or trip ID. Responses use `Cache-Control: private, no-store` and `X-Content-Type-Options: nosniff`.

### 5.1 Search

`GET /api/places?query=<text>&region=<text?>&category=<text?>&limit=20&cursor=<opaque?>`

- Allowed keys are exactly `query`, `region`, `category`, `limit`, `cursor`; duplicate or unknown query keys are `400 INVALID_REQUEST`.
- `query` is required. `region`, `category`, `limit`, `cursor` are optional. Empty optional values are invalid.
- `limit` defaults to 20 and is 1-50. A continuation request must omit `limit` or repeat the cursor-bound limit exactly.
- Sort is `normalized_name ASC, place_id ASC`.
- Only rows with non-null catalog `name` and `normalized_name` participate. Legacy ID-only Place rows are not search results.

Success `200`:

```json
{
  "items": [
    {
      "place_id": "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db",
      "name": "성산일출봉",
      "category": "관광지",
      "region_label": "제주",
      "address": "제주특별자치도 서귀포시 ...",
      "latitude": 33.458056,
      "longitude": 126.942500
    }
  ],
  "next_cursor": null
}
```

All keys are required. `place_id` and `name` are non-null. `category`, `region_label`, `address`, `latitude`, `longitude` are nullable. Coordinates are both null or both JSON decimal numbers. Empty match is exact `items:[]`, `next_cursor:null`; it is not 404.

### 5.2 Search cursor

Cursor is base64url without padding, maximum 4096 ASCII characters, and expires 15 minutes after issue. The bound is a narrow engineering correction: the existing compact binary AES-GCM pattern encodes the valid worst-case Unicode query/region/category/last-name tuple above 1024 but below 4096; implementation tests must prove the actual maximum. It reuses the existing process-start generated AES-256-GCM pattern and binds:

- version and issue/expiry instants;
- authenticated principal UUID;
- endpoint kind `PLACE_SEARCH`;
- normalized query, region and category;
- limit;
- last `normalized_name` and last Place UUID.

Malformed/tampered/expired/restart-stale, principal/filter/limit mismatch and unknown fields are `400 INVALID_CURSOR` before catalog SQL. Cursor values are typed parameters only.

### 5.3 Detail

`GET /api/places/{place_id}`

Path ID is lowercase canonical UUID. Success `200`:

```json
{
  "place_id": "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db",
  "name": "성산일출봉",
  "category": "관광지",
  "region_label": "제주",
  "address": "제주특별자치도 서귀포시 ...",
  "road_address": null,
  "description": null,
  "latitude": 33.458056,
  "longitude": 126.942500
}
```

All keys are required with the same summary nullability; `road_address` and `description` are nullable. A missing row or legacy ID-only row without catalog name is `404 PLACE_NOT_FOUND`. Detail is read-only.

### 5.4 Errors

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | query/path/type/normalization/limit invalid |
| 400 | `INVALID_CURSOR` | cursor invalid, expired or mismatched |
| 401 | `AUTHENTICATION_REQUIRED` | no verified principal |
| 404 | `PLACE_NOT_FOUND` | detail has no displayable catalog Place |
| 503 | `DATABASE_OPERATION_FAILED` | bounded DB failure |

Search/detail do not expose source key or distinguish legacy/nonexistent detail rows.

## 6. Existing basket command integration

PlaceDetail's explicit `바구니에 저장` action calls existing `POST /api/trips/{trip_id}/trip-items` unchanged.

- Target trip is the selected current trip from which search opened. Both ADMIN and MEMBER may save; server membership remains final authority.
- Input is exact `(trip_id, place_id, observed expected_plan_version, request_id)`.
- One user intent owns one canonical request ID. Ambiguous transport failure retries the same tuple/request ID. Changing Place, trip, or observed version starts a new intent/request ID.
- `201 CREATED` and `200 ALREADY_EXISTS` are both success; Android reloads detail/basket and shows the server result without inventing a second item.
- `409 PLAN_VERSION_CONFLICT` reloads current trip detail and basket, preserves the selected Place, and requires a new explicit save action with a new request ID/current version. It does not auto-submit.
- `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`, `409 TRIP_NOT_ELIGIBLE` and auth end terminate the pending save and clear private trip state as already contracted.
- Search/detail selection alone never invokes the basket command.

## 7. Additive V6 data contract

Create only `V6__common_place_catalog.sql`; never modify V1-V5 or backfill legacy rows.

Add nullable columns to `place`:

- `source_type VARCHAR(32)`, `source_key VARCHAR(200)`;
- `name VARCHAR(200)`, `normalized_name VARCHAR(200)`;
- `category VARCHAR(100)`, `normalized_category VARCHAR(100)`;
- `region_label VARCHAR(100)`, `normalized_region_label VARCHAR(100)`;
- `address VARCHAR(300)`, `road_address VARCHAR(300)`;
- `description VARCHAR(4000)`;
- `latitude NUMERIC(9,6)`, `longitude NUMERIC(10,6)`.

Checks:

- source type/key are both null or both non-null;
- catalog name/normalized name are both null or both non-null and nonblank;
- coordinates are both null or both non-null; latitude `-90..90`, longitude `-180..180`;
- non-null bounded display/search fields have length at least 1 and at most their declared bound.

Indexes:

- partial unique `(source_type,source_key)` where both are non-null;
- partial prefix keyset `(normalized_name varchar_pattern_ops,id)` where normalized name is non-null;
- simple partial indexes for `normalized_region_label` and `normalized_category` only if the implementation query uses them and PostgreSQL `EXPLAIN` confirms the plan.

Source columns establish the v1.4 concrete common identity/uniqueness seam but do not authorize or implement an importer. No image URL/storage column is added in R3a; representative-image/fallback data remains a full-R3 obligation. No extension is installed in R3a. This is a slice limitation: full R3 still owes the explicitly adopted pg_trgm and PostGIS behavior after current-stack compatibility, privilege, real-data need and query plans are verified; R3a does not replace those requirements with prefix/B-tree behavior.

## 8. Backend implementation ownership

Expected paths:

- V6 migration;
- `PlaceCatalogController`, `PlaceCatalogService`, `PlaceCatalogException`;
- narrow Place cursor codec reusing the existing authenticated AES-GCM/canonical JSON pattern;
- `ApiErrorHandler` mapping;
- controller and real PostgreSQL integration tests.

Reads use one bounded SQL query per page/detail, typed parameters, requested limit + 1, and no N+1 lookup. They write no Place, TripItem, trip version, ledger or event. Existing basket service remains the only R3a mutation.

## 9. Android Java/XML contract

Use the existing `AuthApiClient`, Activity-scoped `TripViewModel`, FragmentManager and portrait `MainActivity`; no navigation/DI/image/map framework.

Screens:

1. `TripDetail` adds `장소 추가`, visible to ADMIN and MEMBER when detail content exists.
2. `PlaceSearchFragment`: back, text input, clear action, submit action, result RecyclerView and bounded next-page loading.
3. `PlaceDetailFragment`: text fields, missing-coordinate state, fixed local image placeholder, and explicit basket-save action.

Observable states:

- Search: `IDLE`, `INVALID_QUERY`, `LOADING`, `CONTENT`, `EMPTY`, `RETRYABLE_ERROR`, `AUTH_ENDED`.
- Detail: `LOADING`, `CONTENT`, `NOT_FOUND`, `RETRYABLE_ERROR`, `AUTH_ENDED`.
- Save: `IDLE`, `SAVING`, `CREATED`, `ALREADY_EXISTS`, `VERSION_CONFLICT`, `TRIP_ENDED`, `TRIP_INACCESSIBLE`, `RETRYABLE_ERROR`, `AUTH_ENDED`.

State/lifecycle:

- Preserve raw draft query, last submitted normalized filters, loaded page items, next cursor, selected Place ID and RecyclerView stable-ID/scroll anchor in the existing ViewModel across view recreation and Back.
- SavedState keeps only query/filter text, selected Place/trip IDs and scroll/page-depth anchors. It does not store tokens, full responses, coordinates or source identity. Process restore reissues search/detail by IDs and bounded page depth.
- Subscribe to exact state before action in tests; no fixed sleep/polling.
- New query, Back beyond search, trip change, logout/auth end and ViewModel clear cancel or generation-invalidate stale callbacks.
- Missing category/address/coordinates render explicit fallback text. R3a always renders a local image placeholder and makes no remote image request.
- Search/detail/basket save run off main thread through the shared authenticated refresh-once transport. Redirects remain denied.

## 10. Behavioral RED and acceptance

### Backend RED

- routes absent for authenticated principal; anonymous remains 401;
- normalization and literal `%`, `_`, `\` escaping; duplicate/unknown query rejection;
- deterministic prefix/region/category pages, keyset continuation and cursor tamper/principal/filter/limit/restart rejection;
- legacy ID-only rows excluded, exact detail nullability and indistinguishable missing/legacy 404;
- search/detail read-only row/version/ledger/event proof;
- same Place ID search -> detail -> existing basket command, replay and nonmember/ended/conflict regressions.

### Android RED

- exact DTO/null parser and malformed success rejection;
- loading/content/empty/error/auth-ended rendering;
- query/filter/result/scroll Back and recreation recovery;
- stale query/detail/save invalidation;
- ADMIN/MEMBER entry visibility and existing basket command request-ID/version recovery.

### Gates

1. changed-file diagnostics;
2. full isolated V1-V6 PostgreSQL suite and bootJar with role/schema cleanup and preexisting metadata unchanged;
3. Android JVM/instrumentation/Lint/assemble;
4. generated normal-auth Android -> Spring -> isolated PostgreSQL flow: selected trip -> search -> detail -> explicit basket save/replay -> basket reload -> logout, with no external calls and owned cleanup.

Backend V1-V6 passed 116/116 plus bootJar, and Android passed JVM 16, local instrumentation 48/48, Lint and assemble with fixture light/dark/font-1.30 rendering. The preserved Android 17-symbol compile failure is **compile/scaffold RED only**; preimplementation behavioral RED is `NOT_VERIFIED` and was not reconstructed.

The first actual generated normal-auth Android -> Spring -> isolated PostgreSQL attempt is preserved as valid behavioral RED: 20 first-page rows rendered, but the `wrap_content` result RecyclerView nested in `auth_scroll` never observed native page-end swipes. The correction reused the existing R2 bounded native RecyclerView viewport pattern and center-bounded gestures. The same scenario then passed with exactly two search requests, 21 rendered rows, common-ID detail, one deliberately dropped committed `201`, same request/version/place replay, exactly one TripItem/request ledger/version increment, basket reload, Back/recreation/query/scroll recovery, logout/no stale private UI, seven inspected screenshots and clean crash scan.

Final changed-code gate: JVM16, local instrumentation49/49, Lint 0 errors and assemble PASS. Backend 116/116+bootJar was unchanged and reused. Cleanup proved role/schema `0|0`, preexisting metadata restored, private media root removed, reverse/settings restored and all owned runtime/emulator absent. Evidence: [Android receipt](../../.omo/artifacts/mvp-trip-003/android/final-verification.json), [behavioral RED](../../.omo/artifacts/mvp-trip-003/integration/blocker.json), [real-local PASS](../../.omo/artifacts/mvp-trip-003/integration/real-local-e2e-result.json), [backend summary](../../.omo/artifacts/mvp-trip-003/backend/verification-summary.json).

Therefore R3a is `IMPLEMENTED / LOCAL VERIFIED`. Full R3 remains incomplete.

## 11. Source decision and remaining user-only inputs

Latest user decision selects **무료 공공데이터 (free public data)** as the source category and directs use of existing normative materials. This is enough to proceed with R3a code and isolated fixtures without another generic approval question. It is not selection of a particular licensed snapshot and does not approve production ingest, an API account, credentials or a connected service.

v1.4's existing baseline is commercial-area CSV for the base commercial catalog and Tourism Organization data as a supplement. Engineering source QA selected and anonymously acquired the official SEMAS 20260630 snapshot once; the ignored ZIP and sanitized source receipts under `.omo/artifacts/mvp-trip-003/` establish 16 UTF-8 CSV files with one 39-column shape, free/unrestricted metadata, and same-source API metadata identifying WGS84. This clears acquisition for later mapping but does not approve production ingest/refresh/map or enlarge R3a.

V6/generated fixtures use only bounded WGS84 latitude/longitude decimal pairs rounded to six decimal places; both values are present or both null, never guessed. Exact branch/category-code mapping and raw source precision remain later source-mapping decisions and do not add R3a columns or importer abstractions.

Concrete user action is required only if engineering cannot use the preserved official snapshot without owner input: the user then supplies the intended file, or confirms availability of an account/key for a specifically verified free public API. Secret values stay in ignored operator configuration and are never placed in source/docs/chat. The user is not asked to research public terms, costs or licensing.

Full shared map/detail remains blocked on a separately settled map path. Engineering must first verify official terms/attribution, compatibility, free quota and hard no-charge control; the user later only chooses among verified viable options and confirms account/token availability if required. Coordinates are never guessed to bypass the gate.

## 12. Completion boundary

R3a completion proves a local common-Place foundation and basket entry only. It does not prove a usable production catalog, production search quality, ingest freshness/deduplication, representative imagery, map marker/detail, routing or full R3.
