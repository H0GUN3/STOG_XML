# MVP-first implementation map

Date: 2026-10-01  
Status: read-only discovery, not a canonical contract or runtime evidence. No build, test, database, provider, service, public-network, or TLS action was run in this discovery.

## Recommendation

The safest first product increment is: **an authenticated user creates a trip, sees it in My Trips, opens its detail, and sees its empty basket, entirely through the local backend**. It reuses verified auth and PostgreSQL boundaries and can be tested with generated isolated fixtures without TLS, a new provider, or mutations to real-user rows.

Do not put place search or a product-quality non-empty basket card in this increment. `place` currently stores only a UUID (`backend/src/main/resources/db/migration/V1__basket_save_foundation.sql:22-24`), while Android asks the user to type trip/item UUIDs (`app/src/main/res/layout/fragment_main.xml:128-164`). Existing basket POST capability is not usable place discovery.

## Existing implementation to reuse

| Area | Source facts | Reuse boundary |
| --- | --- | --- |
| Authenticated principal | Protected trip-item operations accept only Spring `Authentication` containing `AuthenticatedUser` (`backend/src/main/java/com/stog/backend/BasketSaveController.java:31-47`). Android already keeps access in memory and rotates refresh credentials (`app/src/main/java/com/stog/app/auth/AuthApiClient.java:47-126,146-180`). | All trip endpoints derive identity from the same filter; never accept `user_id` in body/query. |
| Membership non-disclosure | Current-item SQL joins `trip_member` on principal (`BasketSaveService.java:34-49`). | Detail/basket preserve the same indistinguishable missing-trip/nonmember 404. |
| Trip aggregate | V1 has `trip(id,start_date,end_date,plan_version)` and `trip_member(trip_id,user_id,role)` (`V1__basket_save_foundation.sql:5-20`). | Atomically create trip plus creator `ADMIN`; do not replace these tables. |
| One TripItem state model | `schedule_date` and `sort_order` encode BASKET versus SCHEDULED on one row (`V1__basket_save_foundation.sql:26-40`). | Basket list reads BASKET rows; no separate Basket entity/table. |
| Versioned basket mutation | `plan_version` is nonnegative (`V1...sql:9-11`). Save locks Trip, checks expected version, and increments once (`BasketSaveService.java:70-127`). | New trip starts at version 0; later plan mutations retain the existing invariant. |
| Existing basket POST | `POST /api/trips/{tripId}/trip-items` takes place/version/request ID (`BasketSaveController.java:43-55,90-102`). Service verifies Place, commits item/version/result atomically, then publishes after commit (`BasketSaveService.java:91-128`). | Reuse in a later place-selection slice. Do not expose arbitrary UUID entry as product UI. |
| Existing item GET | `GET /api/trips/{tripId}/trip-items/{tripItemId}` returns the six current-state fields (`BasketSaveController.java:31-41,104-110`). | Reuse parsing/error/cancellation patterns; do not build a list through N item GETs. |
| Android lifecycle/network | View-lifecycle observation and binding cleanup exist (`MainFragment.java:50-51,151-156`); read state/cancellation exists (`AuthViewModel.java:21,51-76,98-103`); the client has one executor, 10s timeout, 32KiB cap, no redirects, and one refresh/retry (`AuthApiClient.java:27-40,146-180,240-294`). | Share auth/token transport. Add one trip feature state owner, not clients per screen. |
| Test neighbors | PostgreSQL tests cover authorization/read-only, replay/version, concurrency, rollback, and after-commit behavior (`BasketSavePostgresIntegrationTest.java:106-582`). Real-local Android test subscribes before action and covers recreation/refresh/logout (`RealBackendTripItemUiTest.java:48-148`). | Extend these deterministic fixture/signal patterns; no fixed sleeps. |

## Gaps and ownership

### Schema/data

1. **Trip product fields are absent.** v1.4 section 4.3 requires create inputs for name, cover image, dates, and region, and a detail header with name/dates/member count. The physical `trip` has only ID, dates, and version (`V1...sql:5-11`).
   - First slice needs `name` and a minimum region display/identity contract. Cover media is deferred because no media identity/authorization model exists.
   - Use additive V3; never edit applied V1/V2. Preserve existing rows: do not invent names/regions as a backfill. Safest first migration adds nullable `name` and `region_label`, while the new create API requires nonblank values. Consider DB `NOT NULL` only after legacy remediation.
   - Explicitly unresolved: field limits, region label versus canonical region ID, duplicate names, Unicode normalization, legacy-row presentation, cover identity/ownership.
2. **Place is ID-only.** There is no name, address, coordinate, category, thumbnail, or provider reference (`V1...sql:22-24`), and current TripItem response exposes only `place_id` (`BasketSaveControllerTest.java:39-55`).
   - Close the first slice on a newly created trip's empty basket.
   - Before non-empty cards, define a shared Place display projection and missing-display behavior through a separate additive migration/API contract.
   - Do not claim usable search: repository search found no search endpoint/index/provider adapter/Android search UI.
3. **No suitable trip-create idempotency ledger.** Existing `request_execution` has basket-specific NOT NULL columns and a check allowing only `CREATE_TRIP_ITEM_BASKET_V1` (`V1...sql:45-61`).
   - Do not weaken or overload that record. Prefer additive `trip_create_execution`, unique on principal/request ID, containing normalized create input, result trip snapshot, and expiry.
   - Trip, creator membership, and successful execution record commit or roll back together.

### Backend/API

Current controllers expose only three auth POSTs and trip-item GET/POST (`backend/src/main/java/com/stog/backend/AuthController.java:17-38`; `BasketSaveController.java:23-55`). Missing contracts:

- `POST /api/trips`: principal is creator; stable `request_id`; same key/input replays the same trip, changed input returns `409 REQUEST_ID_REUSED`; never generate a new key after ambiguous response loss. Atomically create version 0 trip and creator `ADMIN`. Creation has no `expected_plan_version` because the aggregate does not yet exist.
- `GET /api/trips?limit=&cursor=`: membership-scoped only; minimum item is `trip_id,name,start_date,end_date,status,role,plan_version`; include `next_cursor`.
- `GET /api/trips/{tripId}`: current member only, same 404 for absent/nonmember; return create fields, caller role, member count, and plan version.
- `GET /api/trips/{tripId}/trip-items?state=BASKET&limit=&cursor=`: current member only; return BASKET rows and one coherent top-level plan version; never fan out through item GET.

All mutations retain Bearer principal, stable request ID, one transaction, no failed execution record, and non-secret errors. Trip creation is not a plan mutation, so it needs no SSE. Reads change no version/event. Future basket add reuses current expected-version/request-ID/after-commit behavior.

**Pagination is an explicit product-contract gap.** v1.4 specifies ongoing -> upcoming -> past grouping but not page wire/size. Prefer opaque keyset cursors over offsets. A trip cursor should bind a server `as_of_date`, status bucket, bucket sort date, and trip ID; basket cursor can use `created_at DESC,id DESC`. Canonical TASK must set limits/order. Android treats cursors as opaque.

### Android/UI/navigation/recreation

- One XML `FragmentContainerView` hard-wires `MainFragment` (`app/src/main/res/layout/activity_main.xml:1-6`); `MainActivity` only applies insets (`MainActivity.java:13-27`). No trip list/create/detail fragments, adapter, or navigation graph exists.
- Current signed-in UI is UUID entry plus text output (`fragment_main.xml:95-185`; `MainFragment.java:47-51,116-149`), a diagnostic lookup rather than a product journey.
- Direct dependencies are AppCompat, Fragment, Material, Kakao, and tests (`app/build.gradle:75-83`); no Navigation/RecyclerView dependency is declared. The smallest route is the existing `FragmentManager` back stack for `TripList -> TripCreate/TripDetail`. If a paged list uses RecyclerView, declare that one direct dependency; do not add a navigation framework.
- One `TripViewModel` owns pages, create draft, selected trip ID, detail/basket state, and in-flight request identity. Never save token, View, or full response in saved state.
- Preserve create draft and selected ID across recreation. Re-read detail by ID; preserve list stable IDs and scroll anchor. Back from create/detail returns to the existing list/page/scroll.
- Distinguish loading/content/empty/retryable/auth-ended. Create retries reuse the same request ID. New request, logout, and clear invalidate stale callbacks.
- New-screen QA must cover light/dark, portrait/landscape recreation, font 1.3, empty/content/error, keyboard/insets, Back, and accessibility labels/focus. Old screenshots are not evidence for new screens.

## First vertical slice boundary

### Included

1. Additive V3 plus Trip create service/controller, atomic creator `ADMIN`, and create idempotency.
2. Current principal's trip list, status grouping, and deterministic cursor pagination.
3. Current-member trip detail, non-disclosure, caller role/member count/version.
4. BASKET read, deterministic pagination, coherent plan version. The primary journey is the new trip's empty result.
5. Android signed-in -> My Trips list -> create form -> created detail -> empty basket, including retry, auth-ended, recreation, and Back.
6. Generated isolated users/trips only, with cleanup proof. Do not change durable local DB data or real Kakao identity/session/user rows.

### Excluded

- Place search/create/import/provider and Instagram/Kakao/Naver map parsing.
- Productizing arbitrary Place UUID entry or describing ID-only Place as usable search.
- Final non-empty basket name/address/image cards, basket add/delete, schedule move/reorder, trip edit/delete/invite/member management.
- Cover upload/media schema, SSE transport, public HTTPS/TLS, new provider, and real-user mutation.
- Room, Retrofit, DI, Navigation component, or other infrastructure not required by this flow.

### Observable completion condition

An isolated fixture principal creates a trip through the local backend and replaying the same request returns the same trip. Android shows it once in a paginated My Trips list, opens it, and renders exact name/dates/region/role/version plus an empty basket. Rotation/font change preserves selection and state; Back restores prior list position. A nonmember learns nothing from list/detail/basket. Fixture cleanup leaves pre-existing DB aggregates unchanged.

## Dependency and ownership order

1. **Manager/canonical contract:** settle legacy nullable behavior, name/region limits, replay retention, page limits/cursor order, and response/error shapes in TASK/API/DATABASE.
2. **Backend/schema lane:** additive V3 and create/list/detail/basket APIs plus PostgreSQL tests. Do not modify V1/V2.
3. **Android lane:** after response shapes settle, implement Java/XML screens, authenticated client extension, state/navigation/recreation. The UUID lookup is not product navigation.
4. **Verification lane:** one backend runner, one Android full gate, generated local E2E, and native visual QA. No provider/public/TLS call.

The backend contract is Android's only prerequisite. Place display/search is not a blocker for this empty-basket slice; it is the blocker for the next non-empty basket/card and add-place slice.

## Exact available verification commands

These commands match the repository wrapper/runner and current operational docs. They were **NOT_RUN** during discovery. Use process-local JDK `C:/Program Files/Java/jdk-17`.

### Backend: isolated PostgreSQL, all backend tests, bootJar

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File backend/src/test/scripts/Run-PostgresIntegration.ps1
```

The runner invokes `gradlew.bat -p backend --no-daemon test bootJar --rerun-tasks` (`backend/src/test/scripts/Run-PostgresIntegration.ps1:179-182`). Add V3 contract tests to this runner and retain generated-role/schema cleanup and pre-existing metadata preservation evidence.

### Android current non-external gate

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
./gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.MissingConfigurationUiTest,com.stog.app.auth.AuthApiClientSecurityTest,com.stog.app.auth.AuthApiClientTest,com.stog.app.auth.AuthApiClientTripItemTest,com.stog.app.auth.ConfiguredAuthUiTest,com.stog.app.auth.SecureRefreshStoreTest'
```

The explicit class filter excludes `RealBackendTripItemUiTest`, which requires generated runtime arguments. Add the first slice's unit/instrumentation classes to this explicit non-external filter when implemented.

### Existing bounded real-local Android/backend/PostgreSQL runner

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .omo/artifacts/android-tripitem-get-001/Run-Real-Local-E2E.ps1
```

This existing runner owns generated IDs/credentials, invokes the real local backend and one exact instrumentation class, then restores rows/processes/reverse/font/rotation (`Run-Real-Local-E2E.ps1:1-32,196-211`). It verifies the existing item journey, not the future trip-create journey. The first slice must add an equivalently bounded runner for its new class; never put secret or `local.properties` values in commands/artifacts. Subscribe to an exact state signal before action and await it with a timeout; do not use fixed sleep/polling. The existing test pattern is `RealBackendTripItemUiTest.java:113-148`.

## Explicit uncertainties and repository state

- `C:/Users/GUN/AndroidStudioProjects/STOG_2` has no `.git`; `git status` failed. Adjacent `../STOG/.git` is a different repository and was not used as worktree evidence. Recheck the actual edit root/worktree before implementation.
- v1.4 defines create UI fields and status/permissions but not name/region limits, pagination wire/page size, legacy-row display, or cover-media identity. The first canonical TASK must decide them.
- There is no repository OpenAPI file. Controller DTOs and tests remain the machine-consumed contract unless OpenAPI is separately approved.
- Existing auth/local DB/restricted-owner/current-GET verification was reused as historical evidence and not rerun.
