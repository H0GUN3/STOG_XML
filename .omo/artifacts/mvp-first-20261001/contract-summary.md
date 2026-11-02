# R1 trip-product contract summary

Date: 2026-10-01  
Status: `CONTRACT READY / IMPLEMENTATION NOT_STARTED / RUNTIME NOT_RUN`

## Canonical decision

User authorization now prioritizes product MVP implementation and defers external tooling/public installation. The one current slot is [MVP-TRIP-001](../../../docs/tasks/MVP-TRIP-001.md): authenticated trip create, membership-scoped My Trips, detail and empty/legacy-ID-only basket on real Java/XML Android + local backend/PostgreSQL. R1 is a first increment, not the full MVP.

## Frozen implementation contract

- Additive V3 only; V1/V2 and existing rows are untouched. Nullable legacy `trip.name`/`region_label`, list indexes and a dedicated create ledger are added.
- Create input: NFC + edge-whitespace normalized nonblank `name`/`region_label`, 1-100 code points, valid canonical dates, canonical `request_id`. Trip version 0 + creator `ADMIN` + 24-hour success snapshot commit atomically.
- Same principal/key/normalized input replays the same result (`201` first, `200` replay); changed input is `409 REQUEST_ID_REUSED`. Unique losers roll back Trip/member/ledger before winner readback.
- Exact routes: `POST /api/trips`, `GET /api/trips`, `GET /api/trips/{trip_id}`, `GET /api/trips/{trip_id}/trip-items?state=BASKET`.
- Status uses server UTC date. My Trips order is ongoing then upcoming then past; ongoing ends soonest, upcoming starts soonest, past ended most recently, with UUID tie-breakers. `as_of_date` is frozen through pages.
- Limits are default 20, range 1-50. A max-1024-character, 15-minute, startup-key AES-GCM cursor binds principal, endpoint, limit and keyset tuple; invalid/tampered/expired/mismatched cursor is `400 INVALID_CURSOR`. Typed values only reach SQL.
- Detail/basket absent and nonmember are the same `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`. List never exposes another user's rows.
- Basket pages are `created_at DESC, trip_item_id DESC`; top-level `plan_version` is coherent and frozen across pages or continuation returns `409 PLAN_VERSION_CONFLICT`.
- Legacy nullable metadata is only presented as `제목 없음`/`지역 미정`. ID-only Place rows return no invented display fields; Android uses `장소 정보 없음`. Search and product-quality nonempty cards remain incomplete.
- Android reuses FragmentManager and shared auth transport. It preserves draft, unresolved request ID, selected trip and stable scroll anchor; ambiguous retry keeps request ID, normalized input change creates a new ID. Back, recreation, stale callback and auth-ended rules are explicit in the TASK.
- cover/media, Place search/add, edit/delete/invite, basket mutation, schedule, SSE, Instagram, map, record/CELL, Discover/MY, STOBEE and public/TLS/provider work are excluded and remain in R2-R9.

## Ownership and verification

Backend owns `backend/` code/tests/V3. Android owns `app/` and only a necessary RecyclerView dependency after the wire is fixed. Final verification owns the combined gate and generated fixture cleanup.

The TASK names behavioral RED tests so an absent protected endpoint is exercised with a normal authenticated fixture and fails on the expected 201/200 contract, while anonymous 401 is a separate assertion. Exact existing commands are preserved for the PostgreSQL+bootJar runner and Android JVM/instrumentation/Lint/assemble gate. Final acceptance requires a new bounded `RealBackendTripProductUiTest` Android -> local backend -> PostgreSQL run, native recreation/Back/font/visual/accessibility observations, and proof that owned QA rows/processes/settings were cleaned while pre-existing aggregates remained unchanged.

## Evidence and remaining blockers

Read sources: root README/AGENTS, PLAN/domain docs, V1/V2 migrations, controller/service/security/error/test/runner conventions, Android host/dependencies and the MVP-first roadmap, implementation-gaps, verification and product-scope artifacts. Applied doc-to-spec and ponytail guidance: one canonical TASK, links instead of duplicated reports, reuse existing boundaries and no speculative infrastructure.

No code, build, test, runtime, DB, device, provider, secret or public-network action was performed in this contract phase. Existing failures, historical evidence and `NOT_RUN` claims remain unchanged. There is no user-only blocker for R1 implementation; later roadmap policy/provider decisions do not block this increment.
