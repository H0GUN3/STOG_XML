# MVP-TRIP-003 R3 scope recovery

Status: **SUPERSEDED by [canonical MVP-TRIP-003](../../../docs/tasks/MVP-TRIP-003.md).** Latest user choice is `무료 공공데이터` category; the old pending source-choice wording below is historical and is not a request for renewed generic approval. The canonical TASK owns the exact R3a/full-R3 boundary.

## Result

**Implementation-ready local foundation only: R3a common Place schema/read/search/detail plus entry into the existing basket-save command. This is not full R3 acceptance.** Source, schema, runtime and canonical documents were not changed.

R3a deliberately starts from an existing selected trip (`TripDetail -> 장소 추가`) so it can reuse `selectedTripId`, current `plan_version`, membership checks and the implemented basket command. It can be built and verified with generated isolated catalog fixtures, but must remain labeled `R3a FOUNDATION / production catalog and shared map NOT_PROVISIONED`.

**Full R3 still requires all roadmap obligations:** an approved real catalog source; source-specific ingest, normalization, deduplication and refresh; production name/region/category search over that catalog; shared Place map/detail using an approved zero-cost map path; and explicit basket entry preserving the same Place identity. Fixture-only endpoints, nullable coordinates, or a text-only detail screen cannot mark R3 complete.

## Evidence that fixes the boundary

- v1.4 §4.7 requires STOG-server common Place search, result name/category/address/image fallback, search state restoration, selection/detail, and basket save; search/selection must not create CELL or trigger AI.
- v1.4 §5.4 says every ingestion path converges on the same Place and TripItem save, and the selected trip is rechecked server-side. §7 says server-owned PostgreSQL catalog, not Android-direct provider access; §7.2 names source identity, name/category/description, address/coordinates/image; §7.3 separates name search from spatial search and forbids Elasticsearch/Redis initially.
- Current `V1__basket_save_foundation.sql` has only `place(id UUID PRIMARY KEY)`. No display, source, address, category, coordinate, search index, catalog repository or ingest source exists in V1-V5. Existing Place rows are generated test IDs, not a production catalog.
- `BasketSaveController/Service` already provides authenticated `POST /api/trips/{trip_id}/trip-items` with `place_id`, `expected_plan_version`, stable `request_id`, membership/state/version checks and common-Place FK. It must be reused, not replaced.
- Android currently parses ID-only basket/current-item responses and honestly renders the existing fallback. It has no Place search/detail/save client or UI.
- PLAN's next and only slot is a separate R3 contract; R4 schedule/delete/reorder/SSE and R5 providers remain later.

## Exact R3a wire

All routes require the existing STOG Bearer principal. Catalog reads require authentication but not trip membership; basket save keeps its existing trip membership boundary.

### Search

`GET /api/places?query=<text>&region=<text?>&category=<text?>&limit=20&cursor=<opaque?>`

- Allowed query keys only: `query`, `region`, `category`, `limit`, `cursor`; duplicates/unknowns are `400 INVALID_REQUEST`.
- `query` is required, NFC/edge-whitespace normalized, 2-100 Unicode code points, no controls. `region` and `category` are optional with the same normalization and 1-100 bound.
- R3a matching is deterministic normalized **name prefix** plus optional exact normalized region/category filters. No fuzzy ranking, 초성, autocomplete or semantic search is claimed.
- `limit` default 20, range 1-50. Sort is `normalized_name ASC, place_id ASC`.
- Cursor is opaque, maximum 1024 ASCII characters, 15-minute/process-key lifetime, and binds principal, endpoint, normalized query/filters, limit and last `(normalized_name, place_id)`. Invalid/tampered/expired/mismatched cursor is `400 INVALID_CURSOR`.
- Success `200`:

```json
{
  "items": [{
    "place_id": "uuid",
    "name": "string",
    "category": "string-or-null",
    "region_label": "string-or-null",
    "address": "string-or-null",
    "latitude": 37.123456,
    "longitude": 127.123456,
    "representative_image_url": null
  }],
  "next_cursor": null
}
```

All keys are required; nullable display/coordinate/image fields stay null when absent. Latitude/longitude are both null or both present. Search is read-only and returns empty `items` for no match.

### Detail

`GET /api/places/{place_id}`

- Canonical lowercase UUID; authenticated read.
- `200` returns the same fields plus required nullable `description` and `road_address`.
- Missing ID is `404 PLACE_NOT_FOUND`. No provider/source key is exposed.
- Read does not create/modify Place, TripItem, plan version, ledger, CELL, EXP, Record or Post.

### Basket entry

The detail action reuses existing `POST /api/trips/{trip_id}/trip-items` unchanged. Android sends the selected common `place_id`, detail trip's current `plan_version`, and one stable request ID; ambiguous retry reuses that tuple. `CREATED|ALREADY_EXISTS`, conflict reload and membership/state errors retain existing semantics. On success Android reloads the trip basket.

## Minimal V6 data contract

A migration is necessary: the current ID-only table cannot truthfully serve search/detail. Additive `V6__common_place_catalog.sql` extends the same `place` table; V1-V5 are untouched.

Required new columns:

- `source_type`, `source_key`: nullable for legacy generated rows, but both required for newly ingested catalog rows; unique together when non-null.
- `name`, `normalized_name`: nullable for legacy rows, nonblank 1-200 code points for catalog rows.
- nullable `category`, `region_label`, `address`, `road_address`, `description`, `representative_image_url` with explicit bounded lengths.
- nullable decimal `latitude`/`longitude`, pair-nullability check and world bounds.

R3a uses a standard PostgreSQL prefix index on `(normalized_name, id)` and filter indexes only after the exact query is exercised. It does **not** create PostGIS/pg_trgm extensions. Their roadmap mention is a historical proposed dependency, not approval to install cluster infrastructure. The current stack has neither extension migration nor demonstrated query-plan need. Full R3 may propose them only after the chosen source supplies real coordinate/text volume and measured queries show the need; map/radius or fuzzy behavior must not be claimed before that approval and evidence.

Legacy `place(id)` rows remain readable by existing TripItem endpoints but are excluded from catalog search while `name/normalized_name` are null. No backfill or invented display data.

## Exact implementation paths

Backend:

- `backend/src/main/resources/db/migration/V6__common_place_catalog.sql`
- new `PlaceCatalogController.java`, `PlaceCatalogService.java`, `PlaceCatalogException.java`, and narrowly scoped cursor codec (or an additive endpoint kind in the existing authenticated cursor mechanism)
- `ApiErrorHandler.java` only for the new typed error
- new controller and PostgreSQL integration tests; existing basket/product/auth suites remain regression gates

Android Java/XML:

- extend `AuthApiClient.java` with authenticated search/detail and existing basket-save wire
- extend `TripViewModel.java` with bounded search/detail/query/result/scroll/pending-save state; no second auth/navigation stack
- new `PlaceSearchFragment.java`, `PlaceDetailFragment.java`, `fragment_place_search.xml`, `fragment_place_detail.xml`, `item_place.xml`
- one `장소 추가` entry from `TripDetailFragment` preserving selected trip context
- focused parser/state/UI tests using generated catalog fixtures

No dependency is required for R3a. Remote image loading is not implemented until a catalog source and allowed image-host/storage policy are approved; UI uses the required missing-image fallback even if `representative_image_url` is null.

## Acceptance

1. Isolated V1-V6 fixture inserts two catalog Places and one legacy ID-only Place. Authenticated search returns deterministic prefix/filter pages; cursor mismatch/tamper/expiry fails before SQL disclosure. Anonymous access is 401.
2. Detail returns exact nullable fields; unknown Place is `PLACE_NOT_FOUND`; all reads leave Place/Trip/ledger/version/events unchanged.
3. Search result, detail and basket response retain the exact same `place_id`. Explicit save through the existing command creates one basket TripItem; response-loss replay does not duplicate it; nonmember and ended-trip behavior is unchanged.
4. Android distinguishes loading/content/empty/retryable/auth-ended and missing coordinate/image. Back restores normalized query, result page and scroll. Newer query/logout invalidates stale callbacks.
5. Generated local Android -> Spring -> isolated PostgreSQL evidence covers search -> detail -> explicit basket save and cleanup. No external provider, real catalog row, map, CELL or AI call is represented as verified.

## Full R3 dependency gates and user actions

There is currently **no available production catalog input or approved shared map path in the repository**. v1.4 recommends the Small Enterprise and Market Service commercial-area CSV as the base and Tourism Organization APIs as supplements, but no source artifact, current terms, account/key, image rights, or zero-cost receipt exists here. No map provider/account/free-limit approval exists for this R3 slot.

The user must perform only these concrete actions before the corresponding full-R3 work can be approved:

1. **Choose and provide the first production catalog source.** Recommended smallest option: provide one dated official commercial-area CSV snapshot (or its exact official download URL and version/date). If choosing a Tourism Organization API instead, identify the exact API product and confirm that an account/key is available; do not send the key in chat or commit it.
2. **Confirm data rights.** Provide the official license/terms reference and confirm STOG may persist and display the selected fields: source identity, normalized name, category, address/road address and coordinates. If images are included, separately confirm hotlink/cache/redisplay rights; otherwise R3 uses the missing-image state and does not ingest image URLs.
3. **Confirm zero-cost operation.** State that the selected dataset/API account has no required paid billing for the intended local ingestion, identify any free quota/download limit, and choose fail-closed behavior when unavailable or exhausted. API credentials, if any, go only into ignored operator configuration after approval.
4. **Choose the shared map path for full R3 detail.** Identify the intended map provider/SDK (or explicitly defer map completion), confirm its account/token availability, display/storage attribution terms, free quota and hard no-charge control. Until this is supplied, map/detail acceptance remains blocked and R3 cannot be marked complete.

The user does **not** need to decide DTO class names, cursor encoding, SQL lock syntax, normalization helper placement, test fixture IDs, or Android state-owner mechanics.

## Immediate versus blocked execution

- **Immediately implementable after contract approval:** additive common-Place fields preserving legacy IDs; authenticated prefix/filter search and detail; Android loading/content/empty/error states; selected-trip basket entry through the existing command; generated V1-V6 tests and local fixture E2E. This is R3a foundation only.
- **Blocked on source input:** production importer, source-specific field mapping, deduplication thresholds, refresh cadence, real catalog rows and production-search quality claims.
- **Blocked on map input:** shared map/marker/detail rendering, coordinate viewport/radius behavior, attribution and directions. No coordinates may be guessed or generated to bypass this gate.
- **Not automatically approved:** PostGIS, pg_trgm, map SDKs, provider accounts, external network calls or new dependencies.

Until the user actions are satisfied, implementation may use only generated isolated fixture rows and must never claim usable production catalog search or full R3 completion.

Excluded from R3a: production row seeding, external API calls, map SDK/UI, directions, PostGIS radius queries, pg_trgm fuzzy search, Instagram, AI/STOBEE, R4 schedule editing/SSE, CELL/EXP/Record/Post, public network and provider/account operations.
