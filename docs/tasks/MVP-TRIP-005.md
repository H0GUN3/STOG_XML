# MVP-TRIP-005: R3c pg_trgm partial and similarity Place search

## 1. Identity and status

| Item | Value |
| --- | --- |
| Product increment | R3c, normative name search behavior |
| Product basis | v1.4 §7.3, accepted R3a/R3b common Place |
| Contract | APPROVED / DECISION-COMPLETE |
| Implementation | DEFERRED BY `NATIVE-AUTH-UI-001`; IMPLEMENTATION ABSENT |
| Verification | BEHAVIORAL RED VERIFIED; GREEN NOT_RUN |
| Dependency | Existing PostgreSQL `pg_trgm 1.6` installed in `public` |
| Excluded | Android DTO/UI changes, Elasticsearch, PostGIS creation, maps/providers, retained catalog mutation |

R3c replaces only R3a's documented prefix limitation. Search/detail routes, response DTO/nullability, authentication, optional exact normalized region/category filters, limit 1-50 and 4096-character cursor bound remain wire-compatible.

## 2. Query and matching rules

Canonical request normalization remains R3a: NFC display validation, Unicode-edge trim, ISO-control rejection, `Locale.ROOT` lowercase and internal Unicode-whitespace collapse. `query` remains 2-100 code points; filters remain optional 1-100.

Matching is literal and parameterized:

1. all queries match a literal normalized-name substring using escaped `LIKE '%query%' ESCAPE '\'`;
2. queries of 2 code points are substring-only; typo similarity is disabled because fewer than three code points do not form a meaningful trigram signal;
3. queries of 3-100 code points additionally match pg_trgm similarity at threshold `0.30`;
4. threshold is set statement-locally through a materialized configuration CTE before the `%` operator executes; no shared/session-global setting is changed;
5. optional region/category remain exact normalized-key filters and are applied before pagination.

Literal `%`, `_` and `\` are escaped for substring/prefix classification and never become patterns or SQL fragments.

## 3. Deterministic ranking and pagination

Every eligible row has:

- class `0`: normalized name exactly equals query;
- class `1`: normalized name starts with query;
- class `2`: normalized name contains query elsewhere;
- class `3`: similarity-only match;
- score: `public.similarity(normalized_name, query)` as PostgreSQL `real`.

Sort is exact:

`match_class ASC, match_score DESC, normalized_name ASC, place_id ASC`.

The continuation key is the complete last tuple `(match_class int32, match_score float32 raw bits, normalized_name, place_id)`. Keyset comparison follows the same directions and typed binds; no offset pagination is used. Requested limit+1 remains the one bounded page query.

## 4. Cursor semantic version

- Place cursor semantic/payload/AAD version becomes `2` and endpoint kind is ranked `PLACE_SEARCH_R3C`.
- It binds principal UUID, issued/expires instants, normalized query/region/category, limit, ranking version, and complete last rank tuple.
- Any R3a V1 prefix cursor, malformed/tampered/restart-stale token, principal/filter/limit mismatch, nonfinite score, invalid class or trailing field is `400 INVALID_CURSOR` before catalog SQL.
- Maximum remains 4096 ASCII base64url characters and 15 minutes. Android treats invalid cursor with its existing fresh-search recovery; response DTO is unchanged.

## 5. Future additive V8 index

Native auth now owns additive V7 under the latest explicit user decision. When
R3c resumes, create only `V8__place_name_trigram_search.sql`; V1-V7 remain
untouched.

- V8 does **not** create/install an extension.
- It requires the already installed `public.pg_trgm` objects and creates one partial GIN index:
  `place(normalized_name public.gin_trgm_ops) WHERE normalized_name IS NOT NULL`.
- If pg_trgm is unavailable or the generated non-superuser cannot use the existing operator class, migration fails and is reported as a concrete dependency/privilege blocker.
- The existing V6 prefix B-tree index remains for exact/prefix compatibility and is not removed.

## 6. Security, bounds and side effects

- Search remains authenticated and read-only, with private/no-store and nosniff headers.
- One ranked SQL query per page returns only the unchanged summary DTO. Detail is unchanged.
- Legacy ID-only/missing-name rows remain excluded. Nullable category/region/address/coordinates remain valid response values.
- Search writes no Place, TripItem, trip/version, ledger, event, extension or setting outside the current statement transaction.
- No generic search abstraction or new dependency is introduced.

## 7. Behavioral RED and acceptance

Executable RED must run against the current R3a prefix implementation and show authenticated HTTP failures for both:

- an infix query that should return an existing Place; and
- a typo query with similarity >=0.30 that should return the intended Place.

Acceptance:

1. exact, prefix, infix and typo-only rows rank in that order, with score/name/UUID ties deterministic;
2. two-character queries are literal substring-only and do not admit typo-only matches;
3. region/category combinations apply to infix and similarity candidates;
4. literal `%`, `_`, `\`, Unicode and SQL-looking text remain parameter-safe;
5. cursor V2 round-trips maximum Unicode tuples, rejects V1/tamper/principal/filter/limit/rank mismatch, and stable keyset pages have no duplicates/gaps;
6. legacy/missing/null fields, anonymous access, detail identity and read-only/no-side-effect invariants remain green;
7. a modest representative real SEMAS subset imported by the existing R3b importer in generated isolated V1-V8 receives actual HTTP infix/typo queries and `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` evidence without planner forcing;
8. focused and one full backend gate pass; cleanup is role/schema `0|0`, media root removed and preexisting metadata unchanged.

## 8. Completion boundary

R3c would complete bounded normative partial/similarity name search over the available common Place rows. It does not prove national production ingestion, cross-source deduplication, quarterly refresh/closure policy, PostGIS spatial behavior, map/image/detail sharing or production search capacity. Those remain full-R3 work.

## 9. Observed blocker and recovery

- Executable RED passed its purpose: current authenticated prefix search failed an infix expectation for an existing Place; exit 1. The typo assertion was not reached after the first failure. Evidence: `.omo/artifacts/mvp-trip-005/logs/behavioral-red.log`.
- Read-only metadata confirmed pg_trgm `1.6` is installed in `stog_backend_test.public`, but pg_trgm is absent from `stog_backend_dev`.
- The actual development launcher role is `stog_backend_app` and its schema is separately named `stog_app`; the similarly named role `stog_app` is not the launcher role. `stog_backend_app` has no public-schema USAGE.
- A candidate V7 containing only the required partial GIN `public.gin_trgm_ops` index, with no `CREATE EXTENSION`, failed under the canonical generated non-superuser migration role: `ERROR: permission denied for schema public`.
- This is a shared database owner/privilege-layout blocker, not a search-code ambiguity. Granting broad public-schema usage or moving/reinstalling the extension would mutate shared database security/extension state and is outside the approved R3c code slice.
- Candidate V7/ranked service/cursor/tests were removed after the probe. The verified R3b prefix baseline compiles with zero diagnostics. No full R3c gate or real-sample plan was claimed.
- Cleanup passed: generated invocation role/schema `0|0`, metadata unchanged, private media root removed, port 8080 absent. Evidence: `.omo/artifacts/mvp-trip-005/blocker.json`.

The historical candidate-V7 failure remains evidence and is not rewritten.
Current status is `DEFERRED / GREEN NOT_RUN`; the DB prerequisite is now locally
ready, but native auth is the current slot. When R3c resumes, every V7 reference
in executable migration/test orchestration must use V8. Exact historical
read-only proposal: `.omo/artifacts/mvp-trip-005/privilege-proposal.md`.
