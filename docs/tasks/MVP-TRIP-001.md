# MVP-TRIP-001: authenticated trip create, My Trips, detail, empty basket

## 1. 식별과 상태

| 항목 | 값 |
| --- | --- |
| 단계 / 기준 절 | R1 / v1.4 §4.3, §5.2, §5.7 |
| 작업 상태 | DONE / APPROVED / R1 VERIFIED |
| 승인 상태 | CONFIRMED - 2026-10-01 사용자가 product MVP 우선과 순차 구현을 명시적으로 승인 |
| 구현 상태 | BACKEND IMPLEMENTED; ANDROID IMPLEMENTED; INTEGRATED HARNESS IMPLEMENTED |
| 검증 상태 | BACKEND EXACT POSTGRESQL GATE VERIFIED; ANDROID EXACT GATE VERIFIED; REAL LOCAL E2E VERIFIED |
| 선행조건 | 기존 Java/XML Android, authenticated principal, V1/V2, PostgreSQL runner |
| 후속 | R2 collaboration/lifecycle/cover; R3 common Place/search; R4 plan editing/SSE recovery |

이 TASK가 R1 trip-product wire, 저장, Android 동작과 검증의 정본이다. [API](../API.md), [DATABASE](../DATABASE.md), [BACKEND](../BACKEND.md), [ANDROID](../ANDROID.md)는 소유 영역의 요약만 둔다.

## 2. 목적과 구현 승인

인증 사용자가 실제 Java/XML Android 화면과 local Spring backend/PostgreSQL을 통해 여행을 만들고, membership-scoped My Trips에서 열어 상세와 빈 basket을 확인한다. 이는 첫 수직 증분이며 usable MVP나 전체 v1.4 완료가 아니다.

사용자의 2026-10-01 최신 지시는 stale external-access-first보다 우선한다. 이 TASK는 R1 제품 구현, 아래 additive V3, backend/API/test와 Android Java/XML 화면/테스트를 승인한다. routine DTO, 정렬, cursor, lifecycle 선택은 이 계약으로 확정하며 재승인을 요구하지 않는다. public 설치/TLS, 외부 provider와 실제 계정·secret·real-user mutation은 승인하지 않는다.

## 3. 범위와 소유권

### 포함

- additive `V3`: legacy row를 보존하는 nullable trip metadata, membership list index, 전용 create idempotency ledger.
- authenticated `POST /api/trips`, `GET /api/trips`, `GET /api/trips/{trip_id}`, `GET /api/trips/{trip_id}/trip-items?state=BASKET`.
- creator `ADMIN`, membership-scoped My Trips, detail, coherent paginated basket read.
- Android `TripList -> TripCreate -> TripDetail`와 empty/ID-only basket fallback, recreation/Back/scroll/auth 종료.
- generated isolated fixture의 behavioral RED, backend/Android gate, bounded real local Android -> backend -> PostgreSQL acceptance와 소유 fixture cleanup.

### 제외

cover/media, Place display 확장·검색·추가, non-empty product-quality cards, basket delete, schedule 이동·정렬, trip edit/delete, invite/member mutation, SSE transport, Instagram, map, camera/record/CELL, Discover/MY, STOBEE, public listener/TLS/deployment와 provider 작업은 R1에 없다. 요구를 삭제하지 않으며 [roadmap](../../.omo/artifacts/mvp-first-20261001/roadmap.md)의 R2-R9에 유지한다.

### lane

- Backend lane만 `backend/` production/test/migration을 변경한다.
- Android lane은 이 wire가 고정된 뒤 `app/`과 필요한 app Gradle dependency만 변경한다. Java/XML, Fragment, View Binding을 유지하고 Compose/Kotlin/Navigation/DI/Room/Retrofit을 추가하지 않는다. 목록에 필요하면 RecyclerView 직접 dependency 하나만 허용한다.
- 두 lane은 상대 영역과 본 TASK의 계약을 수정하지 않는다. 최종 verifier만 전체 gate와 real surface artifact를 소유한다.
- 비용은 0원이다. 새 유료 service/dependency/provider가 없다.

## 4. 공통 타입, 입력 정규화와 여행 상태

- 모든 JSON object는 알 수 없는 field, 중복 key, 누락 required field와 `null` required field를 `400 INVALID_REQUEST`로 거부한다.
- 모든 UUID는 기존 관례와 같은 lowercase canonical `8-4-4-4-12` 문자열이다. path/body/cursor payload의 비정규 UUID는 거부한다.
- `name`과 `region_label`은 JSON string required/non-null이다. server는 Unicode NFC로 변환한 뒤 양 끝 Unicode whitespace를 제거한다. 결과는 nonblank 1-100 Unicode code points이며 CR/LF와 ISO control code point를 포함할 수 없다. 내부 whitespace는 보존한다. 저장, replay 비교와 응답에는 이 normalized 값을 쓴다. 같은 normalized 이름은 허용한다.
- `start_date`, `end_date`는 required canonical ISO `YYYY-MM-DD` JSON string이며 실제 Gregorian date `0001-01-01..9999-12-31`여야 하고 `start_date <= end_date`다. 기간 상한은 R1에서 만들지 않는다.
- server의 UTC clock에서 얻은 calendar date를 `as_of_date`로 사용한다. `start_date <= as_of_date <= end_date`면 `ONGOING`, `start_date > as_of_date`면 `UPCOMING`, `end_date < as_of_date`면 `PAST`다. 경계일 00:00 UTC부터 상태가 바뀐다. client timezone으로 재분류하지 않는다.
- `role`은 required enum string `ADMIN|MEMBER`, `member_count`는 required JSON int64 `>=1`, `plan_version`은 required JSON int64 `>=0`이다.

## 5. 정확한 HTTP 계약

모든 route는 `Authorization: Bearer <opaque STOG access token>`에서 생성된 `AuthenticatedUser`만 사용한다. body/query의 `user_id`, role, member count, status와 version을 받지 않는다. 인증 전 실패는 `401 AUTHENTICATION_REQUIRED`다.

### 5.1 Create

`POST /api/trips`, `Content-Type: application/json`

```json
{
  "name": "제주 여행",
  "region_label": "제주",
  "start_date": "2026-10-10",
  "end_date": "2026-10-12",
  "request_id": "cb96754c-37f3-42fb-91b6-a5d9ad6cb80e"
}
```

성공 body의 모든 field는 required/non-null이다.

```json
{
  "trip_id": "940e857d-b025-4f72-b420-2ee354a514ad",
  "name": "제주 여행",
  "region_label": "제주",
  "start_date": "2026-10-10",
  "end_date": "2026-10-12",
  "status": "UPCOMING",
  "as_of_date": "2026-10-01",
  "role": "ADMIN",
  "member_count": 1,
  "plan_version": 0
}
```

최초 commit은 `201 Created`, 같은 principal/key/normalized input의 unexpired replay는 최초 응답의 `status`와 `as_of_date`까지 포함한 동일 snapshot과 `200 OK`다. 생성에는 `expected_plan_version`이 없고 version 0으로 시작하며 SSE/event를 만들지 않는다.

Idempotency scope는 `(principal_user_id, CREATE_TRIP_V1, request_id)`이고 성공 결과를 DB clock 기준 정확히 24시간 보존한다. 다른 principal의 같은 UUID는 별도 scope다. 같은 scope/key의 raw 표현이 달라도 normalized name/region과 parsed dates가 같으면 replay이며, normalized input 하나라도 다르면 `409 REQUEST_ID_REUSED`; 기존 결과나 trip identity를 body에 노출하지 않는다. 실패 결과는 저장하지 않는다.

Trip, creator `ADMIN` membership과 성공 execution snapshot은 한 transaction에서 모두 commit하거나 rollback한다. 동시에 같은 key가 들어오면 ledger unique constraint가 승자를 정한다. loser transaction의 임시 Trip/member/result를 전부 rollback하고 aborted transaction 밖 새 transaction에서 같은 principal의 winner를 읽어 replay 또는 input conflict를 반환한다. 범용 자동 retry는 하지 않는다.

### 5.2 My Trips

`GET /api/trips?limit=20&cursor=<opaque>`

- 허용 query는 `limit`, `cursor`뿐이고 duplicate/unknown query는 `400 INVALID_REQUEST`다. `limit`은 optional decimal integer, default 20, 범위 1-50이다.
- 첫 page에는 cursor를 생략한다. 후속 page cursor가 page size를 고정하므로 `limit`을 생략하거나 같은 값만 보낼 수 있다.
- current membership만 반환한다. 다른 사용자 trip은 존재 여부와 개수를 포함해 노출하지 않는다. `role`은 해당 principal의 current `trip_member.role`, `member_count`는 응답 snapshot에서 그 trip에 존재하는 current `trip_member` row 수다.

```json
{
  "as_of_date": "2026-10-01",
  "items": [
    {
      "trip_id": "940e857d-b025-4f72-b420-2ee354a514ad",
      "name": "제주 여행",
      "region_label": "제주",
      "start_date": "2026-10-10",
      "end_date": "2026-10-12",
      "status": "UPCOMING",
      "role": "ADMIN",
      "member_count": 1,
      "plan_version": 0
    }
  ],
  "next_cursor": null
}
```

`items`, `as_of_date`, `next_cursor`는 required이며 `items`는 empty 허용 array, `next_cursor`만 nullable string이다. page limit 뒤 current row가 더 있을 때만 `next_cursor`가 non-null이다. 각 item의 field도 required이나 legacy `name`과 `region_label`만 nullable이다.

한 전체 순서는 bucket `ONGOING -> UPCOMING -> PAST`다. `ONGOING`은 `end_date ASC, trip_id ASC`, `UPCOMING`은 `start_date ASC, trip_id ASC`, `PAST`는 `end_date DESC, trip_id ASC`다. status와 정렬은 첫 page에서 sampled UTC `as_of_date`로 모든 page에 고정한다. DB 변경 전체 snapshot을 고정하지는 않지만 offset을 쓰지 않으며 cursor tuple 뒤의 current membership row만 keyset 조회한다.

### 5.3 Detail

`GET /api/trips/{trip_id}`는 current member에게만 아래 flat body를 `200`으로 반환한다. shape와 nullability는 create body와 같지만 legacy `name`, `region_label`만 nullable일 수 있다. `as_of_date`는 이 요청에서 sampled UTC date다. absent/nonmember는 동일한 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`다. read는 version/event/ledger를 바꾸지 않는다.

### 5.4 Basket list

`GET /api/trips/{trip_id}/trip-items?state=BASKET&limit=20&cursor=<opaque>`

- `state`는 required exact enum `BASKET`; 누락·다른 값은 `400 INVALID_REQUEST`다.
- 허용 query는 `state`, `limit`, `cursor`뿐이고 duplicate/unknown query는 `400 INVALID_REQUEST`다. `limit`/cursor 규칙은 My Trips와 같다.
- current member만 읽으며 ended trip도 읽을 수 있다. absent/nonmember는 동일한 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`다.

```json
{
  "trip_id": "940e857d-b025-4f72-b420-2ee354a514ad",
  "plan_version": 0,
  "items": [],
  "next_cursor": null
}
```

Top-level `trip_id`, `plan_version`, `items`, `next_cursor`는 모두 required/non-null이며 `items`는 empty 허용 array, `next_cursor`만 nullable string이다. page limit 뒤 같은 frozen version의 row가 더 있을 때만 cursor가 non-null이다. non-empty legacy-compatible item shape는 다음과 같다. 모든 key가 required이고 `schedule_date`, `sort_order`는 BASKET에서 반드시 null이다. `created_at`은 required UTC RFC 3339 string이며 fractional seconds를 허용하고 `Z`로 직렬화한다.

```json
{
  "trip_item_id": "bc3a32fb-230c-4f43-83ca-ce1903eb1545",
  "place_id": "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db",
  "state": "BASKET",
  "schedule_date": null,
  "sort_order": null,
  "created_at": "2026-10-01T03:04:05Z"
}
```

첫 page는 trip `plan_version`과 items를 한 coherent SQL snapshot에서 읽고 `created_at DESC, trip_item_id DESC`로 정렬한다. cursor는 이 version도 묶는다. 후속 page에서 current version이 다르면 `409 PLAN_VERSION_CONFLICT`와 `current_plan_version`; Android는 첫 page부터 다시 읽는다. 따라서 한 성공 pagination sequence의 top-level version은 동일하다. R1은 Place가 ID-only임을 숨기지 않고 name/address/image field를 발명하지 않는다.

## 6. Cursor 계약

- cursor는 client가 해석하지 않는 base64url(no padding) token이며 최대 1024 ASCII chars, 수명은 발급 후 15분이다.
- backend process는 startup마다 JCA `SecureRandom` 256-bit in-memory key를 만들고 AES-256-GCM으로 versioned payload를 authenticated-encrypt한다. cursor secret/config/DB table은 추가하지 않는다. restart 뒤 기존 cursor는 invalid가 되고 첫 page reload가 복구 경로다.
- payload allowlist는 version, issued/expiry instant, authenticated principal UUID, endpoint kind(`TRIP_LIST|BASKET_LIST`), page limit, 그리고 다음 값뿐이다. trip list는 frozen `as_of_date`, bucket, sort date, last trip UUID; basket은 trip UUID, exact `BASKET`, frozen plan version, last `created_at`, last item UUID다.
- malformed base64, 길이 초과, GCM 인증 실패, unknown version/field/enum, expiry, principal/endpoint/trip/state/limit mismatch와 범위 밖 값은 모두 `400 INVALID_CURSOR`다. 다른 principal cursor로 data를 읽거나 존재 여부를 추론하지 못한다.
- cursor 값은 SQL fragment/column/order로 사용하지 않는다. endpoint와 sort는 server enum으로 선택하고 모든 tuple 값은 typed bind parameter로만 사용한다. raw cursor/payload/principal을 log 또는 오류에 넣지 않는다.

## 7. 오류 계약

기존 `{ "error": { "code": "...", "message": "..." } }` envelope를 쓴다. `message`는 식별자가 아니다. 아래 외 오류는 추가 field가 없고 모든 실패는 trip/member/version/ledger/event를 바꾸지 않는다.

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | JSON/query/type/required/null/UUID/text/date/state/limit validation |
| 400 | `INVALID_CURSOR` | cursor malformed/tampered/expired/mismatched |
| 401 | `AUTHENTICATION_REQUIRED` | verified internal principal 없음 |
| 404 | `TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE` | detail/basket trip absent 또는 nonmember |
| 409 | `REQUEST_ID_REUSED` | create key의 normalized input mismatch |
| 409 | `PLAN_VERSION_CONFLICT` | basket continuation의 version 변화; 이 오류만 `current_plan_version` 포함 |
| 503 | `DATABASE_BUSY` | bounded DB lock/timeout busy |
| 503 | `DATABASE_OPERATION_FAILED` | 기타 DB operation failure |

목록의 empty는 `200 items:[]`이지 404가 아니다. create validation/auth 실패에는 ledger가 없다.

## 8. Additive V3 데이터 계약

적용된 V1/V2를 수정하거나 다시 실행하지 않는다. V3는 기존 row를 update/backfill/rename하지 않는다.

1. `trip.name VARCHAR(100) NULL`, `trip.region_label VARCHAR(100) NULL`을 추가한다. nullable은 legacy 보존용이다. nullable이 아니면 PostgreSQL `character_length`가 1-100인 check를 둔다. 새 create service는 NFC/control 규칙까지 강제하고 non-null만 insert한다.
2. `trip_member_user_trip_idx ON trip_member(user_id, trip_id)`, `trip_status_start_idx ON trip(start_date, id)`, `trip_status_end_idx ON trip(end_date, id)`를 추가한다. 기존 PK/FK/roles와 common Place/TripItem은 유지한다.
3. `trip_create_execution`을 추가한다. exact columns는 `principal_user_id UUID NOT NULL`(User FK), `command_type VARCHAR(64) NOT NULL`, `request_id UUID NOT NULL`, `input_name VARCHAR(100) NOT NULL`, `input_region_label VARCHAR(100) NOT NULL`, `input_start_date DATE NOT NULL`, `input_end_date DATE NOT NULL`, `result_trip_id UUID NOT NULL`, `result_name VARCHAR(100) NOT NULL`, `result_region_label VARCHAR(100) NOT NULL`, `result_start_date DATE NOT NULL`, `result_end_date DATE NOT NULL`, `result_status VARCHAR(16) NOT NULL`, `result_as_of_date DATE NOT NULL`, `result_role VARCHAR(16) NOT NULL`, `result_member_count BIGINT NOT NULL`, `result_plan_version BIGINT NOT NULL`, `completed_at TIMESTAMPTZ NOT NULL`, `expires_at TIMESTAMPTZ NOT NULL`이다. result trip에는 snapshot 보존을 위해 FK를 두지 않는다. PK는 `(principal_user_id, command_type, request_id)`, command check는 `CREATE_TRIP_V1`, expiry check는 `completed_at + interval '24 hours'`, status/role/count/version/text/date checks는 위 계약과 일치한다. raw input/hash나 실패 row는 저장하지 않는다.
4. ledger snapshot은 mutable trip을 재조회해 replay body를 재구성하지 않는다. R1에 delete가 없지만 향후 삭제 후 replay 정책은 R2에서 정하며 V3를 소급 변경하지 않는다.

Create는 target Trip lock이 없으므로 ledger unique 충돌 rollback/readback을 사용한다. reads는 read-only다. 기존 basket `request_execution`, 24시간 semantics, common Place, TripItem와 plan version 규칙을 변경하지 않는다.

## 9. Legacy presentation과 Android 계약

- list/detail에서 legacy nullable `name`은 Android resource `제목 없음`, nullable `region_label`은 `지역 미정`으로만 표시한다. wire null과 DB null은 그대로이며 create/edit로 가장하거나 저장하지 않는다.
- basket이 비어 있으면 명시적 empty state를 표시한다. 기존 ID-only row가 있으면 `장소 정보 없음` fallback과 stable item identity만 사용하고 name/address/image/search 완료를 가장하지 않는다. 임의 Place display data나 좌표를 만들지 않는다.
- 기존 signed-in shell에서 Activity의 FragmentManager back stack으로 `TripList -> TripCreate -> TripDetail`을 연다. 새 navigation/DI stack을 만들지 않는다. 하나의 trip feature state owner와 기존 shared authenticated HTTP/session 경계를 재사용한다.
- list는 stable `trip_id`를 사용한다. loaded pages와 `anchor_trip_id + pixel offset`, selected trip ID, create draft, pending normalized create tuple와 request ID를 configuration/process recreation 가능한 작은 state로 보존한다. token, full response, View/binding은 저장하지 않는다. detail/basket payload는 ID로 다시 읽는다. Detail content는 exact name/date range/region/status/caller role/member count/plan version을 표시하고 이어서 basket state를 표시한다.
- create submit이 local validation을 통과할 때 UUID를 한 번 만든다. response loss, transport failure, 5xx 또는 malformed success처럼 commit 여부가 불명확한 retry는 같은 normalized tuple와 같은 request ID를 유지한다. 입력 edit로 normalized tuple가 달라지면 old pending intent를 폐기하고 다음 submit에 새 ID를 만든다. definitive success, validation 400 또는 explicit discard 뒤에도 다음 intent는 새 ID다. `REQUEST_ID_REUSED`는 새 ID로 몰래 retry하지 않고 conflict/reload action을 표시한다.
- loading/content/empty/retryable/auth-ended를 구분한다. create 중복 submit을 막고, read retry는 같은 page/cursor 또는 fresh-page recovery를 명시한다. basket version conflict/invalid cursor/expired cursor는 첫 page reload로 복구한다.
- newer request, logout, auth-ended, owner clear는 generation/cancellation로 stale callback을 무효화한다. 기존 규칙대로 첫 protected 401에 refresh 1회와 request retry 1회만 허용한다. 최종 auth-ended/logout은 pending create ID/draft, pages, selected/detail/basket과 private UI를 모두 지우고 signed-out surface로 간다.
- Back: create/detail에서 list로 돌아가 기존 loaded pages와 anchor를 복원한다. 성공 create 뒤 detail의 Back은 새 trip이 한 번 반영된 list와 그 item anchor로 돌아간다. create Back은 draft를 유지하고 explicit discard만 지운다. list root의 Back은 system Activity Back 동작에 맡긴다.

## 10. Behavioral RED와 검증

### Backend RED identifiers

구현 전에 다음 실제 assertion을 추가하고 실패를 보존한다.

- `TripProductControllerTest.authenticatedPrincipalCreatesAndReplaysExactWire`: `AuthenticatedUser` test authentication으로 POST의 최초 201/replay 200 shape를 기대한다. route가 없어서 404/handler mismatch로 RED여야 한다. anonymous request는 별도 `unauthenticatedTripRoutesReturnAuthenticationEnvelope`에서 401을 기대한다. 정상 RED를 anonymous 401로 대신하지 않는다.
- `TripProductControllerTest.validatesExactCreateListDetailAndBasketContracts`: unknown/null/bounds/date/state/limit/cursor와 required/nullable field를 고정한다.
- `TripProductPostgresIntegrationTest.createTripMembershipAndLedgerCommitAtomicallyAndReplay24Hours`.
- `TripProductPostgresIntegrationTest.concurrentCreateKeyHasOneTripAndLoserRollsBack`.
- `TripProductPostgresIntegrationTest.changedInputConflictsWithoutDisclosingWinner`.
- `TripProductPostgresIntegrationTest.myTripsAreMembershipScopedGroupedAndKeysetPagedWithFrozenAsOf`.
- `TripProductPostgresIntegrationTest.cursorTamperExpiryAndPrincipalEndpointMismatchAreInvalidWithoutDisclosure`.
- `TripProductPostgresIntegrationTest.detailAndBasketHideNonmemberAndReadsDoNotMutate`.
- `TripProductPostgresIntegrationTest.basketPagesKeepOnePlanVersionOrConflictAndOrderDeterministically`.
- `TripProductPostgresIntegrationTest.legacyNullMetadataAndIdOnlyPlaceRemainHonest`.
- `TripProductPostgresIntegrationTest.lateFailureRollsBackTripMemberAndExecution`.

Fixture는 generated normal authenticated principal/user를 만들고 실제 security filter/controller/service/PostgreSQL을 지난다. restricted-owner나 principal bypass를 제품 성공 증거로 쓰지 않는다.

### Android RED identifiers

- JVM `TripFlowStateTest`: normalized draft/pending request ID reuse와 input-change new ID, stale callback/auth-ended clearing, cursor recovery와 saved small state.
- instrumentation `TripApiClientTest`: exact create/list/detail/basket parsing, nullable legacy, bounded body/redirect denial, one refresh/retry, error mapping.
- instrumentation `TripFlowUiTest`: list empty/content, create validation/loading/retry, detail/empty basket, ID-only fallback, recreation/Back/anchor와 1.3 font semantics.
- real surface `RealBackendTripProductUiTest`: generated principal로 UI create -> one list row -> detail -> empty basket -> ambiguous replay still one trip; nonmember API/UI fixture는 같은 404.

비동기 test는 exact state/event를 action 전에 subscribe하고 bounded latch/idling signal을 기다린다. fixed sleep, polling과 prose pinning은 금지한다.

### 정확한 runner

Backend 전체 PostgreSQL + bootJar:

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File backend/src/test/scripts/Run-PostgresIntegration.ps1
```

Android non-external gate는 기존 explicit class 여섯 개에 `com.stog.app.trip.TripApiClientTest,com.stog.app.auth.TripFlowUiTest`를 추가하고 JVM test는 전체 실행한다. `TripFlowUiTest`의 실제 package는 `com.stog.app.auth`다.

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
./gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug '-Pandroid.testInstrumentationRunnerArguments.class=com.stog.app.MissingConfigurationUiTest,com.stog.app.auth.AuthApiClientSecurityTest,com.stog.app.auth.AuthApiClientTest,com.stog.app.auth.AuthApiClientTripItemTest,com.stog.app.auth.ConfiguredAuthUiTest,com.stog.app.auth.SecureRefreshStoreTest,com.stog.app.trip.TripApiClientTest,com.stog.app.auth.TripFlowUiTest'
```

최종 verifier는 새 `.omo/artifacts/mvp-trip-001/Run-Real-Local-E2E.ps1`로 exact `RealBackendTripProductUiTest`만 실행한다. runner는 generated IDs/credentials/schema rows만 소유하고 action 전에 readiness/UI state signal을 구독하며 bounded timeout을 사용한다. 종료 시 owned trip/member/ledger rows와 auth fixture, backend/provider process, `adb reverse`, emulator, font/rotation을 제거·복원하고 pre-existing aggregate fingerprint 불변을 기록한다. real Kakao/user row, DPAPI 설정, durable DB와 다른 fixture는 건드리지 않는다.

Native UI acceptance는 Pixel_8 API 36 emulator real surface에서 portrait/landscape recreation, light/dark, 1.3 font, keyboard/insets, accessibility label/focus, list empty/content, create validation/loading/retry, detail/empty basket/ID-only fallback, Back/scroll anchor와 crash scan을 관찰한다. API 23/37 runtime, physical device, TalkBack, real provider와 public HTTPS는 실행하지 않으면 `NOT_RUN`이다.

## 11. 완료 gate와 현재 evidence

R1 `VERIFIED`는 작은 edit마다가 아니라 두 lane merge 뒤 한 번의 V3 gate로 판정한다.

1. behavioral RED artifact가 실제 endpoint/state 부재 때문에 실패하고 anonymous 401과 구분된다.
2. backend changed-file diagnostics, PostgreSQL runner 전체와 bootJar가 한 번에 통과한다.
3. Android changed-file diagnostics, JVM/instrumentation/Lint/assemble gate가 한 번에 통과한다.
4. bounded generated real-local runner와 native UI matrix/cleanup receipt가 통과한다.
5. final verifier가 wire, migration checksum/history, fixture cleanup, secret/public/provider 부재와 기존 failure/history를 확인한다.

2026-10-01 final verifier는 process JDK 17로 canonical backend runner를 실행해 전체 backend test, V1-V3 isolated PostgreSQL과 `bootJar`를 exit 0으로 확인했다. generated role/schema cleanup은 `0|0`, pre-existing metadata는 불변이었다. 변경 Java LSP diagnostics도 0이었다.

R1 local 증분은 `VERIFIED`다. 최초 verifier의 잘못된 `com.stog.app.trip.TripFlowUiTest` mapping, 당시 source 결함과 이후 proxy/observer/provider-reattach/cleanup 실패 이력은 보존한다. Android producer correction으로 legacy nullable parsing, bounded page-depth/process-anchor 복원, cursor recovery, `ConfiguredAuthUiTest` deadlock, nested system Back와 logout teardown이 수정됐다. 실제 package `com.stog.app.auth.TripFlowUiTest`를 사용한 최종 exact gate는 owned Pixel_8 API 36.1에서 JVM, 지정 instrumentation 24/24, Lint와 assemble을 모두 통과했다. API/UI 변경 전체의 historical preimplementation RED 부재는 소급 복구하지 않는다.

Verifier-owned `RealBackendTripProductUiTest`, response-loss proxy와 bounded real-local runner는 generated isolated non-superuser schema/role에 V1-V3를 적용했다. 최종 실행은 synthetic provider의 정상 exchange/security filter를 거쳐 member/nonmember identity 각 1개를 만들고, create 요청 2회/첫 response loss 1회의 같은 request replay로 trip/member/create execution 각 1개를 확인했다. detail/empty basket, generated ID-only basket fallback, light/dark, landscape 1.3 font recreation, focus와 exact IME-inset signal, system Back의 exactly-one 목록 복귀, access-expiry refresh, member logout, nonmember empty list와 동일 detail 404, 최종 signed-out/no-private-content 및 빈 AndroidRuntime crash scan을 통과했다. 현재 PNG 10개를 육안 검사했고 token, credential, private user ID는 없으며 ID-only 화면 UUID는 generated fixture ID로 표시한다. headless emulator PNG에는 software keyboard surface가 보이지 않지만 capture 전 IME inset visibility event는 assertion으로 확인했다.

최종 cleanup은 generated role/schema `0|0`, pre-existing metadata 불변, backend/provider/8080/emulator/qemu/device/shutdown-helper 부재, reverse와 font/rotation 복원을 확인했다. durable DB, 실제 user, DPAPI, real provider와 public/TLS는 건드리지 않았다. [최종 검증](../../.omo/artifacts/mvp-trip-001/final-verification.json), [cleanup receipt](../../.omo/artifacts/mvp-trip-001/cleanup-receipt.json), [screenshots](../../.omo/artifacts/mvp-trip-001/screenshots/README.md). API 23/37, physical device, TalkBack, real provider와 public HTTPS/TLS는 `NOT_RUN`이다. 원래 API/UI preimplementation behavioral RED 부재는 남고 R2-R9는 계속 backlog다.
