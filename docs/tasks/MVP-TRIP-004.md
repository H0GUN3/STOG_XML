# MVP-TRIP-004: R3b SEMAS common-Place CSV ingestion

## 1. Identity and status

| Item | Value |
| --- | --- |
| Product increment | R3b, bounded source-specific ingestion |
| Product basis | v1.4 §7 and accepted R3a common Place |
| Contract | APPROVED / DECISION-COMPLETE |
| Implementation | IMPLEMENTED |
| Verification | LOCAL VERIFIED |
| Input | Official SEMAS dataset `15083033`, snapshot `20260630` |
| Excluded | Android, maps, images, external calls, extensions, production/dev DB population |

R3b adds one source-specific CSV-to-existing-V6 ingestion path. It does not rewrite V1-V6, add a generic ETL framework, or claim that the national production catalog is populated.

## 2. Fixed source and provenance

- Input is the already preserved ignored ZIP `.omo/local/r3-source-qa/SEMAS_store_20260630.zip`, exact size `352699739` and SHA-256 `57aa361544108ff4fc73334f87d2ced61e41fe8638dd70bcd842930f865d2386`.
- The command verifies both values before opening entries. It downloads nothing and makes no external request.
- Expected archive shape is 16 UTF-8 regional CSV entries with the inspected identical 39-column header. A bounded run may select one exact entry and maximum accepted row count; an unbounded invocation may stream all matching CSV entries.
- `source_type` is fixed `SEMAS_STORE`; `source_key` is exact trimmed `상가업소번호`. This pair is stable across snapshot reruns. Snapshot, dataset ID, archive digest, entry name, import time and aggregate counts belong to the sanitized execution report; V6 has no provenance table and R3b does not invent one.

## 3. Exact mapping

| V6 field | SEMAS field / rule |
| --- | --- |
| `id` | New random UUID only when `(SEMAS_STORE, source_key)` is absent; an update preserves the existing UUID |
| `source_type` | exact `SEMAS_STORE` |
| `source_key` | `상가업소번호`, required 1-200 code points |
| `name` | `상호명`, required; branch is not concatenated |
| `normalized_name` | canonical search normalization of mapped name |
| `category` | `상권업종소분류명`, required |
| `normalized_category` | canonical search normalization of category |
| `region_label` | full `시도명`, required; no abbreviation |
| `normalized_region_label` | canonical search normalization of region |
| `address` | empty-to-null `지번주소` |
| `road_address` | empty-to-null `도로명주소` |
| `description` | null; source has no approved description |
| `longitude` | `경도`, WGS84, required with latitude, HALF_UP to six decimals |
| `latitude` | `위도`, WGS84, required with longitude, HALF_UP to six decimals |

`지점명` remains a separately inspected optional source field. It is neither discarded semantically nor silently appended to `name`: R3b reports aggregate nonblank-branch count, while a future display/provenance schema may map it explicitly. `상권업종소분류코드` is required to be nonblank and its aggregate distinct/count facts are report-only because V6 has no code column. Larger/middle category and administrative codes are likewise not invented as V6 fields.

## 4. Text, coordinates and duplicates

- Display text uses NFC, trims only Unicode whitespace at both edges, preserves internal text, rejects ISO control characters, and enforces the V6 code-point/column bounds.
- Search keys lowercase with `Locale.ROOT` and collapse each internal Unicode whitespace run to one ASCII space.
- Coordinates parse as decimal text without locale, preserve axes (`경도` -> longitude, `위도` -> latitude), require both values, reject nonnumeric/out-of-range values, then round HALF_UP to scale 6. Values are never guessed, swapped or partially stored.
- Empty optional address fields become null. Required source ID/name/category/category code/region/coordinate values are invalid rows.
- Distinct source keys are always distinct common Places even when normalized name/address/coordinates match. No name-only merge occurs. Such possible cross-row duplicates are counted as review candidates, not merged.
- A repeated source key inside one CSV is an invalid ambiguous source row and rolls back that file. Cross-source dedup remains future work.

## 5. Parser, bounds and transactions

- Use a source-specific Java streaming parser over UTF-8 `Reader`; no new dependency. It handles comma delimiters, CRLF/LF, quoted commas, doubled quotes and quoted embedded newlines. Stray quotes, unterminated records, wrong headers or non-39-column rows are rejected.
- Parser bounds: 39 columns exactly, maximum 8,192 characters per field, maximum 32,768 characters per logical record, and maximum 500 mapped rows held for one JDBC batch.
- Each CSV entry is one database transaction. Batches use 500 or fewer rows. Any parse, validation, duplicate-key ambiguity or JDBC error rolls back the whole entry and stops the invocation.
- Previously committed entries from the same archive are not deleted on a later-entry failure. Recovery is rerun: upsert identity makes already committed entries updates/no duplicates.
- The importer never deletes Places absent from the snapshot and does not infer closures. It does not modify TripItem, trip version, request ledgers, events or media.

## 6. Safe rerun and update

- Upsert target is the V6 partial unique source identity `(source_type, source_key)`.
- Existing rows update only mapped catalog fields and keep `id` unchanged. Repeating identical input is a no-duplicate no-op at domain identity level.
- Changed source values update that same Place UUID. This preserves TripItem/common-Place references.
- The report contains only source metadata, row/file counts, inserted/updated counts, invalid reason category and bounded category/branch aggregates. It never stores complete raw rows.

## 7. Runtime boundary

- `backend/scripts/Import-SemasPlaces.ps1` is an explicit operator command. It requires isolated/approved JDBC settings and exact ZIP path; tests supply a generated non-superuser V1-V6 schema.
- It must not default to or mutate retained `stog_backend_dev`, production, or public services. No account/key is used.
- Read-only inspection of `pg_available_extensions` may record availability of `pg_trgm`/`postgis`; R3b does not install or create either extension.

## 8. Behavioral RED and acceptance

Preimplementation behavioral import RED is `NOT_VERIFIED`. The preserved reflective `ClassNotFoundException` only proves implementation absence/class presence; it is scaffold evidence, not observed CSV create/update behavior. It is not rerun or relabeled. Current parser/database/HTTP tests and the official bounded subset are valid postimplementation behavioral evidence.

Acceptance:

1. synthetic parser cases prove quoted comma/newline/doubled quote, Unicode normalization, branch/code handling, exact 39-column header and bounded malformed-record rejection;
2. invalid coordinates/required fields/duplicate source key reject and roll back the entire entry;
3. generated isolated V1-V6 PostgreSQL imports a valid batch, repeats it without duplicates, updates fields while preserving UUID, and leaves unrelated/legacy rows untouched;
4. injected mid-batch JDBC failure rolls back and a clean retry succeeds;
5. an actual bounded subset streamed from the documented official ZIP imports into an isolated V1-V6 schema, then authenticated real HTTP search/detail returns mapped fields and exact common identity;
6. full related/backend tests and bootJar pass once stable; generated role/schema cleanup is `0|0`, private media root is removed and preexisting metadata is unchanged.

## 9. Completion boundary

R3b completion proves one bounded SEMAS snapshot ingestion implementation and isolated evidence. It does not prove national production population, quarterly disappearance/closure semantics, cross-source deduplication, Tourism Organization supplementation, refresh scheduling, pg_trgm/PostGIS behavior, representative imagery or shared map/detail. Full R3 remains open for those items.

## 10. Implementation and verification evidence

- Implementation-absence/scaffold check: `SemasPlaceImporterTest.validSemasCsvHasAnExecutableImporter` compiled and failed because the class was absent (`ClassNotFoundException`), exit 1. It is **not behavioral RED**. Preimplementation behavioral import RED is `NOT_VERIFIED`; the historical log is preserved unchanged at `.omo/artifacts/mvp-trip-004/logs/behavioral-red.log`.
- Implementation: source-specific `SemasPlaceImporter`, explicit `SemasPlaceImportCommand`, Gradle `importSemasPlaces` task and `backend/scripts/Import-SemasPlaces.ps1`. No dependency or migration was added.
- Synthetic/isolated acceptance: six focused tests passed for RFC-style quoting/newlines, bounds, Unicode/search normalization, six-decimal rounding, branch/category reporting, repeat/update stable UUID, distinct-source duplicate review, invalid-coordinate/source-key rollback, injected DB failure and retry, and authenticated HTTP search/detail.
- Official bounded subset: the exact 352,699,739-byte/verified-SHA ZIP, 제주 entry, first 25 rows imported as `25|25|25`; repeat remained `25|25|25`, inserted 0/updated 25, identity fingerprint unchanged. First run reported 5 nonblank branches, 14 distinct small-category codes and zero exact review groups. Authenticated HTTP search and detail returned the exact imported Place ID. No external call occurred.
- Full backend gate: generated non-superuser V1-V6 PostgreSQL 122/122, failure/error/skip 0, bootJar PASS. Cleanup role/schema `0|0`, private media root removed and preexisting metadata/counts unchanged.
- Read-only extension inspection: `pg_trgm` availability/default 1.6 and preexisting installed 1.6 were observed; PostGIS availability/default 3.6.2 and no installed version were observed. R3b did not install or create either extension.
- Evidence: `.omo/artifacts/mvp-trip-004/{real-subset,full-gate}/`, `.omo/artifacts/mvp-trip-004/handoff.md`.

Final status is `IMPLEMENTED / LOCAL VERIFIED`. No retained development/production catalog was populated.
