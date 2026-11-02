# R3 first official public Place source

Retrieval/inspection date: 2026-10-06. Scope: one official source, one normal anonymous download, bounded ZIP listing plus README/header/first-row inspection only. No DB import, account change, API key, map/image/provider connection or production code.

## RESULT

**Usable first source:** 소상공인시장진흥공단 `상가(상권)정보`, data.go.kr dataset `15083033`, snapshot `20260630`.

Official landing URL: https://www.data.go.kr/data/15083033/fileData.do

The portal's normal download sequence returned `needCaptcha:false`; the official file downloaded anonymously with HTTP 200. There is no observed login, CAPTCHA, account or user-only blocker. The 352,699,739-byte ZIP is retained only at ignored local QA path `.omo/local/r3-source-qa/SEMAS_store_20260630.zip`; SHA-256 is `57aa361544108ff4fc73334f87d2ced61e41fe8638dd70bcd842930f865d2386`.

The archive contains one UTF-8 instruction file and 16 regional UTF-8 CSVs. All 16 inspected headers are identical, 39 columns, and bounded first-row samples decode strictly as UTF-8. This source can now be used as the selected catalog input for source-specific contract/import work; this investigation does not itself approve or perform ingestion.

## Existing authoritative baseline

Normative `STOG Android App Project Specification v1.4`:

- §4.7: search STOG's internal common Place catalog; show name/category/address and image fallback; preserve state. Search does not create CELL or invoke AI.
- §5.4: search/import/recommendation converges on one Place and existing TripItem basket save. Unknown coordinates and unverified candidates are not promoted.
- §7, §9.1: backend collects and normalizes public data into server-owned PostgreSQL; Android reads through STOG.
- §7 table and §7.1: SEMAS `상가(상권)정보` CSV is the base commercial catalog, using establishment ID, business name, category, address and coordinates; compare quarterly snapshots and deduplicate sources into one Place.
- §7.2: minimum internal identity/display/location fields are `place_id`, `source_type`, `source_key`, name/category/description, address/road address, latitude/longitude, with dedup evidence from normalized name + coordinate distance + address.
- §7.3 proposes PostGIS/pg_trgm for full spatial/fuzzy behavior.

Current canonical boundary:

- `docs/tasks/MVP-TRIP-003.md` §§3, 7, 11-12 approves R3a common-Place fields/search/detail and generated fixtures but excludes production ingestion, map/image fetch and extensions.
- Latest user choice confirms free official public data and this source category. Source selection/download inspection does not approve DB writes, importer runtime, map integration or images.
- v1.4's PostGIS/pg_trgm direction remains full-R3 guidance; current R3a's no-extension rule remains authoritative until measured need and separate approval.

## Official availability, rights and cost

Official sources retrieved:

- Landing metadata: https://www.data.go.kr/data/15083033/fileData.do
- Schema.org metadata: https://www.data.go.kr/catalog/15083033/fileData.json
- DCAT metadata: https://www.data.go.kr/biz/dcat/metadata/15083033.do
- Same-operator API metadata/data dictionary: https://www.data.go.kr/data/15012005/openapi.do
- Portal policy: https://www.data.go.kr/ugs/selectPortalPolicyView.do
- Operator: https://www.semas.or.kr/

Verified facts:

- Publisher: 소상공인시장진흥공단, AI빅데이터실.
- Current snapshot: `20260630`; published/modified 2026-08-05; quarterly; next date shown 2026-10-31.
- Coverage: operating establishments nationwide.
- Distribution: ZIP containing 16 regional CSV files plus UTF-8 instructions.
- Encoding: included README states UTF-8/code page 65001 and comma delimiter; all bounded samples decoded strictly as UTF-8.
- Cost: `무료`.
- Dataset-specific rights: `이용허락범위 제한 없음`. Portal policy describes unrestricted/type-0 use as commercial and noncommercial use plus modification allowed and no attribution condition. The landing page does not render a numbered Kogl badge, so no numbered badge is claimed.
- Storage/transformation/redistribution of the factual fields is compatible with the published unrestricted scope. STOG should still retain publisher, dataset ID, snapshot and import timestamp as provenance. Known third-party-right restrictions still apply; this dataset provides no image rights.
- Normal path: official metadata POST returned the current attachment; `/cmm/cmm/check-limit.json` returned `{"needCaptcha":false}`; the standard `/cmm/cmm/fileDownload.do` response returned the named ZIP and exact advertised content length. No bypass or invented endpoint was used.

## Actual archive schema

All 16 CSV files have this exact header:

`상가업소번호, 상호명, 지점명, 상권업종대분류코드, 상권업종대분류명, 상권업종중분류코드, 상권업종중분류명, 상권업종소분류코드, 상권업종소분류명, 표준산업분류코드, 표준산업분류명, 시도코드, 시도명, 시군구코드, 시군구명, 행정동코드, 행정동명, 법정동코드, 법정동명, 지번코드, 대지구분코드, 대지구분명, 지번본번지, 지번부번지, 지번주소, 도로명코드, 도로명, 건물본번지, 건물부번지, 건물관리번호, 건물명, 도로명주소, 구우편번호, 신우편번호, 동정보, 층정보, 호정보, 경도, 위도`.

Bounded inspection findings:

- `상가업소번호` is present and nonblank; 16 first-row samples were distinct alphanumeric identifiers. Same-operator API metadata defines `bizesId` as the serial number assigned to the establishment. It is the correct `source_key`; cross-quarter disappearance/reassignment behavior should still be tested when implementing refresh.
- Same-operator API metadata explicitly describes longitude and latitude as **WGS84** coordinates. CSV columns are separately named `경도`, `위도`, fixing axis meaning. Bounded values are decimal degrees; sampled ranges were longitude 126.569908451853-129.263536613542 and latitude 33.5116371028632-37.8782755585983.
- Source precision exceeds R3a's six-decimal database columns. Import must specify deterministic rounding to six decimal places; it must not swap axes or regenerate coordinates.
- Optional fields are represented by empty CSV values in inspected rows (for example building/branch fields). Full null/sentinel frequency remains an importer profiling task, not a blocker to selecting the source.
- No representative image URL, image license, description or opening-hours field is present.

## Minimal import mapping

This is the concrete source mapping baseline, not an executed import:

- `source_type` = fixed `SEMAS_COMMERCIAL_AREA` (exact internal enum/name may follow repository convention).
- `source_key` <- `상가업소번호`.
- `name` <- `상호명`. `지점명` is a distinct source field; do not silently concatenate or discard it without an explicit display/dedup policy.
- `category` / normalized category <- `상권업종소분류명`; preserve `상권업종소분류코드` in source/provenance handling when the importer contract is added.
- `region_label` <- full `시도명`; do not invent shortened names. `시군구명` remains available for address/filter refinement.
- `address` <- `지번주소`; `road_address` <- `도로명주소`.
- `longitude` <- `경도`; `latitude` <- `위도`; WGS84, explicit axes, deterministic six-decimal rounding for current V6.
- `description`, `representative_image_url` <- null.
- Preserve dataset ID `15083033`, snapshot `20260630`, archive SHA-256 and import timestamp. Deduplication must use v1.4 normalized name + WGS84 distance + address evidence, never name alone.

## Baseline-to-source fit and remaining contract facts

- The source directly supplies R3a identity, name, category, region, both addresses and coordinate pair.
- It does not supply image/description, matching the current missing-image/nullable-description behavior.
- R3a's `source_key VARCHAR(200)` accommodates actual IDs; current coordinate NUMERIC scales require explicit rounding.
- `지점명`, category code and richer administrative codes do not currently have dedicated V6 columns. A source-specific importer must decide whether they are staging/provenance-only or require a separately approved schema addition; R3a search API must not invent fields.
- No user action is currently required. The next actionable step is engineering-side: incorporate this exact mapping/rounding/provenance contract into the source-specific R3 ingest increment, then build a bounded parser/profile test before any DB import.

Detailed bounded receipt: `.omo/artifacts/mvp-trip-003/download-check/zip-inspection.json`; summary: `bounded-sample-summary.json`; official request receipt: `official-source-retrieval-receipt.json`.
