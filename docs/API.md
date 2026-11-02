# API 책임 경계

상태: `M1-SHARED-FOUNDATION-002` basket operation과 `M1-SHARED-FOUNDATION-003` current TripItem GET의 구현·검증된 계약을 인증·외부 operation과 분리한다. endpoint와 JSON은 backend source와 [M1-003 검증 artifact](../.omo/artifacts/m1-foundation-003/final-verification.json)를 기준으로 한다. 인증 principal·transaction은 [BACKEND.md](BACKEND.md), 저장 schema는 [DATABASE.md](DATABASE.md)가 권위 원본이다.

## 확정 의미

- server가 인증한 principal만 호출하며 body/path/query의 `user_id`를 받지 않는다.
- principal은 대상 여행의 현재 관리자 또는 일반 구성원이어야 한다. 저장은 진행중·예정 여행만 허용하고 상태를 재검사한다. ended trip 저장은 `409 TRIP_NOT_ELIGIBLE`이며, 유효한 성공 replay와 현재 member의 GET은 ended trip에서도 허용된다.
- 기존 공통 Place 하나만 같은 여행의 새 `TripItem` 바구니 상태로 저장한다. Place 자동 생성, 일정/CELL/Record/EXP/Post 변경은 없다.
- 모든 신규 mutation은 `request_id`와 `expected_plan_version`을 사용한다. 성공 mutation은 item·version·성공 결과를 원자적으로 commit하고 commit 뒤에만 SSE를 시도한다.
- retry와 새로운 사용자 의도는 분리한다. 동일 `request_id`/동일 입력은 replay이고, 새로운 `request_id`/동일 basket Place는 구현된 `ALREADY_EXISTS` 결과다.

## 구현된 HTTP 계약

### 요청

`POST /api/trips/{trip_id}/trip-items`

```json
{
  "place_id": "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db",
  "expected_plan_version": 12,
  "request_id": "cb96754c-37f3-42fb-91b6-a5d9ad6cb80e"
}
```

- `Content-Type: application/json`.
- `trip_id`, `place_id`, `request_id`는 lowercase canonical UUID 문자열, `expected_plan_version`은 0 이상의 JSON 정수다. backend는 Java `long`과 PostgreSQL BIGINT 경계를 사용한다.
- 세 필드는 필수이고 `null`, 중복 JSON key, 알 수 없는 필드는 `400 INVALID_REQUEST`다.
- 인증 credential은 `Authorization: Bearer <opaque STOG access token>`으로 전달한다. server-side hash/session 검증으로 만든 principal만 사용하며 요청에 `user_id`, `state`, 날짜, 순서, Place 내용은 없다.

### 성공 결과

새 basket row가 없으면 basket item을 만들어 `201 Created`를 반환하고, 이미 존재하면 새 item을 만들지 않고 기존 basket item을 `200 OK`로 반환한다. 실제 POST 응답은 flat body다.

```json
{
  "result": "CREATED",
  "trip_item_id": "bc3a32fb-230c-4f43-83ca-ce1903eb1545",
  "place_id": "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db",
  "plan_version": 13
}
```

새 생성의 `plan_version`은 요청의 `expected_plan_version + 1`이다. 기존 basket 결과는 `ALREADY_EXISTS`이며 item metadata, version, event를 바꾸지 않는다. 전체 current-state read가 아니라는 점에 유의한다.

### 현재 TripItem 조회

`GET /api/trips/{trip_id}/trip-items/{trip_item_id}`

현재 member가 읽으며 ended trip도 허용한다. trip 부재 또는 nonmember는 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`, member가 접근할 수 있는 trip에서 item이 없거나 삭제됐거나 다른 trip에 속하면 `404 TRIP_ITEM_NOT_FOUND`다.

```json
{
  "trip_item_id": "bc3a32fb-230c-4f43-83ca-ce1903eb1545",
  "place_id": "4d0f73ea-43ab-43d7-9ca9-bc61b69f20db",
  "state": "BASKET",
  "schedule_date": null,
  "sort_order": null,
  "plan_version": 13
}
```

item과 현재 `plan_version`은 한 coherent SQL snapshot에서 읽는다. 이 GET은 mutation, version, request ledger, event를 만들지 않는다. 구현·검증 evidence는 [M1-003 final verification](../.omo/artifacts/m1-foundation-003/final-verification.json)이다.

### 오류 envelope와 status

모든 지원 오류는 다음 최소 envelope를 사용한다. `message`는 고정 식별자가 아니며 내부 ID, 다른 사용자, stack/SQL/token을 노출하지 않는다.

```json
{
  "error": {
    "code": "PLAN_VERSION_CONFLICT",
    "message": "여행 계획이 변경되었습니다.",
    "current_plan_version": 13
  }
}
```

| HTTP | `error.code` | 조건 / 노출 규칙 | domain mutation / SSE 효과 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | JSON/type/필수값/UUID/version validation 실패 | TripItem/version 변화와 SSE 없음 |
| 401 | `AUTHENTICATION_REQUIRED` | 인증 principal 없음/무효 | 여행 조회, TripItem/version 변화, SSE 없음 |
| 404 | `TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE` | trip 부재, 비구성원, membership 철회 뒤 replay를 동일하게 처리 | TripItem/version 변화와 SSE 없음; 저장된 과거 성공 결과도 미노출 |
| 404 | `PLACE_NOT_FOUND` | 권한 있는 trip 확인 뒤 기존 Place가 없음 | TripItem/version 변화와 SSE 없음; Place 생성 안 함 |
| 409 | `REQUEST_ID_REUSED` | 같은 scope/request_id에 다른 입력 | TripItem/version 변화와 SSE 없음; 기존 성공 결과 미변경 |
| 409 | `TRIP_NOT_ELIGIBLE` | ended trip에 저장 시도 | TripItem/version 변화와 SSE 없음 |
| 409 | `PLAN_VERSION_CONFLICT` | 신규 명령의 expected version이 현재와 다름. 이 오류만 `current_plan_version` 정수 포함 | TripItem/version 변화와 SSE 없음 |
| 404 | `TRIP_ITEM_NOT_FOUND` | GET 대상 item이 없음/삭제됨/다른 trip 소속 | TripItem/version 변화와 SSE 없음 |
| 503 | `DATABASE_BUSY` | database lock/timeout busy | TripItem/version 변화와 SSE 없음 |
| 503 | `DATABASE_OPERATION_FAILED` | database operation failed | TripItem/version 변화와 SSE 없음 |

`current_plan_version` 외 오류에는 추가 field가 없다. 구현된 오류는 domain mutation, version, event를 만들지 않으며 실패 결과를 저장하지 않는다. 성공 결과만 24시간 보관된다. 종료 여행은 `TRIP_NOT_ELIGIBLE`로 거부한다. lock timeout과 DB busy의 경계 결과는 source와 검증 artifact를 따른다.

## request_id replay 입력

**IMPLEMENTED scope:** `(authenticated principal_user_id, command type, request_id)`. command type은 `CREATE_TRIP_ITEM_BASKET_V1`이며 request row의 trip, place, expected version으로 입력을 비교한다.

**IMPLEMENTED replay input:** request scope는 authenticated principal, command type, request_id이며 trip, place, expected version 입력을 저장해 동일성을 비교한다. source와 migration에는 별도 SHA-256 fingerprint column이 없다.

입력 비교 대상은 `trip_id`, `place_id`, `expected_plan_version`이며 UUID는 canonical lowercase, version은 0 이상 int64다. UNEXPIRED stored success record가 있는 같은 request_id로 입력을 바꾸면 `REQUEST_ID_REUSED`다. 실패 결과와 만료되어 조건부 삭제된 record는 영구 기억되지 않는다.

처리 의미:

- 동일 입력 retry: 권한을 다시 확인한 뒤 저장한 결과를 그대로 반환한다. 현재 plan_version이 더 진행됐어도 재실행·version 증가·event는 없다.
- 새로운 request_id의 같은 basket Place: `200 OK`, `ALREADY_EXISTS`와 기존 item/version을 반환한다. 새 item, version 증가, event는 없다.
- UNEXPIRED stored success record가 있는 같은 key의 다른 trip/place/version: 현재 요청 대상의 비노출 권한 검사를 먼저 거친 뒤 `409 REQUEST_ID_REUSED`다. 만료 key가 transaction 안에서 조건부 삭제되면 새 validation으로 처리되며 실행·성공이 보장되지 않고 client retry나 version replacement도 자동으로 하지 않는다.
- 동시에 온 동일 요청: 하나만 commit하고 나머지는 그 결과를 replay한다. unique loser가 생기면 기존 transaction을 전부 rollback하고, 새 transaction에서 target authorization/membership을 재검사한 뒤 winner 결과를 읽는다. client가 timeout을 이유로 새 request_id를 자동 생성하면 안 된다.
- 성공 응답이 확인되지 않은 동일 사용자 의도는 같은 request_id로만 retry한다.

서로 다른 trip에 같은 global key가 동시에 들어오면 저장된 입력 trip이 다르므로 서로 다른 입력이다. 두 transaction이 각각 domain write를 stage했더라도 key unique loser는 전부 rollback하고, aborted transaction 밖의 새 authorized transaction에서 winner를 읽어 `409 REQUEST_ID_REUSED`를 반환한다. loser trip의 item/version/SSE는 없다.

실패 결과는 저장하지 않는다. 오류는 domain mutation, version, event를 만들지 않으며 기존 성공 snapshot을 덮어쓰지 않는다. 성공 결과만 24시간 보관되고 만료 key는 transaction 안에서 조건부 삭제된 뒤 새 validation으로 처리된다. 실행·성공은 보장되지 않으며 client retry나 version replacement를 자동으로 하지 않는다.

## Version·transaction·SSE

valid success replay는 version check보다 먼저 반환한다. 그 외 모든 NEW request는 ALREADY_EXISTS branch를 포함해 basket lookup 전에 expected/current `plan_version`을 비교한다. CREATED만 version을 정확히 1 증가시키고, ALREADY_EXISTS는 `200`으로 version과 event를 바꾸지 않는다. version conflict branch에서는 TripItem/plan_version/domain mutation 또는 SSE가 없으며, 실패 결과는 저장하지 않는다. 권한/Place/중복정책/version 검사 및 atomic write 순서는 [BACKEND.md](BACKEND.md#명령-처리-순서와-동시성)를 따른다.

최초 commit 뒤 callback에 전달되는 event payload 경계는 다음과 같다.

```json
{"trip_id":"940e857d-b025-4f72-b420-2ee354a514ad","plan_version":13,"changed_scope":"TRIP_ITEMS"}
```

SSE event name, 구독 route, real SSE transport와 full-trip/basket list synchronization route는 정의하지 않는다. 다만 구현된 one-item GET(`/api/trips/{trip_id}/trip-items/{trip_item_id}`)으로 해당 item을 다시 읽을 수 있다. 전달은 best effort이고 유실될 수 있다. client는 화면 진입·재연결·알림·version 불연속 때 권한 있는 REST 조회로 회복한다. replay, rollback, conflict에는 event가 없다.

## 구현된 인증 wire

- `POST /api/auth/signup`: canonical username, NFC password와 required `display_name`을 받아 native User/credential/current session을 atomic하게 만들고 기존 5-field Bearer token response를 `201`로 반환한다. Signup은 Kakao identity, Trip, membership 또는 dummy product data를 만들지 않는다.
- `POST /api/auth/login`: canonical username/password를 검증하고 기존 User에 새 opaque session family를 발급한다. Unknown username, wrong password, throttle block와 restricted-owner mismatch는 모두 동일 `401 AUTHENTICATION_REQUIRED`다.
- `POST /api/auth/kakao/exchange`: `kakao_access_token` 하나를 받아 provider 검증/소비 뒤 Bearer access/refresh를 반환한다.
- `POST /api/auth/refresh`: `refresh_token` 하나를 받아 access/refresh를 모두 회전한다. used token replay는 session family를 revoke한다.
- `POST /api/auth/logout`: refresh history로 현재 session을 찾아 idempotent `204`로 revoke한다.
- `GET /api/me`: 인증된 본인의 `{ "display_name": string | null }`만 no-store `200`으로 반환한다. 미인증은 `401`이고 Kakao 등 이름이 없는 계정은 `null`이다. 요청으로 다른 사용자를 지정할 수 없다.
- Native signup은 `username`, `password`, `display_name` 외 field를 받지 않고 성공 `201`, duplicate collision `409 ACCOUNT_NOT_CREATED`, rejected password `400 PASSWORD_NOT_ACCEPTABLE`, restricted-owner enabled `503 AUTH_REGISTRATION_UNAVAILABLE`를 사용한다. Native login은 `username`, `password`만 받고 성공 `200`이다.
- Username은 trim/lowercase 뒤 4-32 ASCII canonical key다. Password는 trim하지 않는 NFC 8-128 code point/1024 UTF-8 byte 경계와 offline blocklist를 사용한다.
- 모든 `POST /api/auth/*` JSON body는 controller/Jackson 전에 8192 bytes로 제한한다. Declared length와 chunked/unknown-length body 모두 같은 `400 INVALID_REQUEST` no-store envelope로 중단되며 service/DB에 진입하지 않는다.
- Password null/NUL은 malformed field `400 INVALID_REQUEST`, 8 code point 미만·128 초과·1024 UTF-8 byte 초과는 `400 PASSWORD_NOT_ACCEPTABLE`이다. 8개 이상의 space-only password와 NFC-equivalent Unicode는 허용된 password input이며 login/signup에서 같은 normalized secret로 취급한다.
- exchange/refresh 응답은 `token_type`, `access_token`, `access_expires_in`, `refresh_token`, `refresh_expires_in`이며 `Cache-Control: no-store`, `Pragma: no-cache`다. malformed client JSON과 whitespace/control-character 또는 4097-character 이상 credential은 provider 호출 전 400이고 4096-character 이하는 허용된다. invalid/mismatch/replay는 generic 401, provider 장애/malformed 또는 timestamp-representability 밖 `expires_in`은 provisioning 전 503이다.
- access/refresh token은 256-bit base64url opaque 값이고 URL/cookie/query에 넣지 않는다. 상세 contract와 DEVELOPMENT 수명은 [KAKAO-AUTH-CONTRACT-001](tasks/KAKAO-AUTH-CONTRACT-001.md)이 소유한다.
- restricted-owner 시험 모드에서도 wire는 바뀌지 않는다. 검증된 identity가 configured internal owner에 이미 매핑되지 않은 exchange와 nonowner session의 access/refresh는 기존 generic `401 AUTHENTICATION_REQUIRED`이고, logout은 idempotent `204`를 유지한다. disabled mode의 자동 provisioning wire도 그대로다.

Native auth backend wire는 [NATIVE-AUTH-UI-001](tasks/NATIVE-AUTH-UI-001.md)
범위에서 `IMPLEMENTED / LOCAL VERIFIED`다. Full backend gate 135/135와
bootJar, retained loopback signup `201`, empty trips `200`, authenticated trip
create `201`, logout `204`, login `200`, same persisted trip read와 final logout
`204`를 확인했다. Runtime credential은 same-user DPAPI operator file에만
있고 API/evidence/log에는 없다.

## 승인된 R1 trip-product wire

`MVP-TRIP-001`은 다음 구현 슬롯의 정본 wire를 확정한다: `POST /api/trips`, membership-scoped `GET /api/trips`, member-only `GET /api/trips/{trip_id}`, coherent `GET /api/trips/{trip_id}/trip-items?state=BASKET`. exact request/response/nullability/status/error는 [TASK](tasks/MVP-TRIP-001.md#5-정확한-http-계약)를 따른다.

- Create는 required normalized `name`, `region_label`, valid dates와 canonical `request_id`를 받고 Trip version 0 + creator `ADMIN` + 24시간 success snapshot을 atomically 만든다. 최초 201, 동일 replay 200, changed normalized input은 `409 REQUEST_ID_REUSED`다.
- My Trips는 `ONGOING -> UPCOMING -> PAST`와 TASK의 exact keyset sort를 사용하고 첫 page의 UTC `as_of_date`를 모든 page에 고정한다. detail/basket absent와 nonmember는 동일 404다.
- list limit은 default 20, 1-50이다. 최대 1024자의 15분 AES-GCM opaque cursor는 principal/endpoint/limit과 sort tuple을 묶으며 malformed/tampered/expired/mismatch는 `400 INVALID_CURSOR`다. R2 visibility 변경 뒤에도 My Trips cursor는 anchor row 존재를 재검사하지 않고 signed raw sort tuple 뒤의 current-visible rows를 반환한다. soft delete/membership 철회만으로 cursor를 invalidate하는 data generation은 없으며, mutation을 직접 관찰한 Android만 first page를 새로 읽는다.
- Basket response는 top-level `plan_version`, ID-only Place item과 cursor를 반환한다. page 사이 version이 바뀌면 `409 PLAN_VERSION_CONFLICT`; search/display field를 발명하지 않는다.
- 모든 field의 required/nullable/data type, status의 UTC date 경계, 오류 envelope와 Android retry 의미는 TASK가 소유한다. OpenAPI는 R1 범위에 새로 만들지 않고 controller DTO/test가 machine-consumed contract가 된다.

현재 backend wire는 `IMPLEMENTED / EXACT POSTGRESQL GATE VERIFIED`다. Android consumer correction 뒤 실제 `com.stog.app.auth.TripFlowUiTest` mapping의 최종 gate는 JVM·instrumentation 24/24·Lint·assemble을 통과했다. real-local Android -> backend -> isolated V1-V3 PostgreSQL 실행도 create response-loss/replay, empty·ID-only basket, recreation/Back/refresh, member/nonmember 동일 404, logout/no-private-content와 crash scan을 통과해 R1 local flow는 `VERIFIED`다. [MVP-TRIP-001 evidence](../.omo/artifacts/mvp-trip-001/final-verification.json).

## 승인된 R2a/R2b wire

[MVP-TRIP-002](tasks/MVP-TRIP-002.md)이 R2 정본이다. 현재 실행 증분 R2a는 member 공통 `PUT /api/trips/{trip_id}`, member page, ADMIN-only role/remove/invitation issue·stop와 trip soft delete, MEMBER-only leave, authenticated invitation join을 고정한다. metadata/member/delete mutation은 `expected_plan_version`과 `request_id`, join은 외부 participant라 version 없이 `request_id`를 사용한다. 실제 변경은 version을 1 증가시키고 no-op/replay/invitation lifecycle은 증가시키지 않는다.

Soft delete 뒤 unexpired R1 create replay는 stale snapshot을 공개하거나 새 trip을 만들지 않고 `409 REQUEST_RESULT_NO_LONGER_AVAILABLE`다. Invitation lookup은 Android가 만든 32-byte/43-char base64url token의 SHA-256만 저장하고 10분 유효하다. Issue replay ledger에는 raw token/URI/ciphertext 대신 non-secret result/expiry recipe만 저장하며, caller가 같은 raw token을 다시 보내 hash가 일치할 때 URI를 재구성한다. exact local deep link는 `stog://trip-invitations/{token}`이며 ACTION_VIEW, raw token/full URI paste와 ACTION_SEND text share를 지원한다. 재발급은 이전 invitation을 revoke하지만 membership을 바꾸지 않는다. member control/share/stop/restart는 ADMIN-only다. Join의 malformed JSON/missing/duplicate/wrong type은 400이고, token string의 noncanonical Base64URL/length와 canonical unknown/expired/revoked/deleted-trip은 동일 `404 INVITATION_NOT_FOUND_OR_NOT_USABLE`다. canonical parse 전 hash/rate/DB lookup은 없다. public HTTPS App Link는 승인 전 `NOT_RUN`이다.

R2a wire는 `IMPLEMENTED / LOCAL VERIFIED`다. isolated PostgreSQL 전체 91개와 bootJar, Android exact gate 및 generated normal-auth user 두 명의 실제 local HTTP 흐름에서 exact role/status, strict invitation 400/404, stop/reissue/new issue, access revoke와 soft-delete replay/non-disclosure를 확인했다. Raw invitation capability와 provider credential은 evidence에 기록하지 않았다.

R2b cover upload는 2026-10-06 contract reconciliation에 따른 `multipart/form-data`이며 exact file part `image` 하나와 JSON part `command` 하나(`expected_plan_version`, `request_id`)를 사용한다. 이는 manager DAG에서 양쪽 구현에 적용된 engineering choice이지 direct-user `CONFIRMED` wire 결정이 아니다. parser file 8 MiB/request 9 MiB와 application stream 8 MiB+1, 24 MP/8192px decode bound 뒤 metadata 없는 bounded JPEG display/thumbnail만 보존한다. trip DTO의 required nullable `cover`, member-authenticated `/covers/{cover_id}/file`과 `/covers/{cover_id}/thumbnail`, member 공통 remove를 추가한다. Corrected real servlet에서 initial `201`, replacement committed-response loss 뒤 same-request `200`, removed/deleted generic 404와 exact UNCHANGED replay를 actual local E2E로 확인해 R2b wire는 `IMPLEMENTED / LOCAL VERIFIED`다. Cover upload/remove idempotency는 `(principal,trip,command,request_id)`이고 같은 UUID를 두 command에서 써도 충돌하지 않는다. no-cover remove는 exact `200 UNCHANGED` snapshot을 replay하며 별도 blob request-ID uniqueness는 없다. file/thumbnail도 absent/deleted/nonmember trip을 동일 404로 비노출하고 public URL/redirect/filesystem path를 제공하지 않는다. Exact DTO, status, errors, rate와 idempotency scope는 TASK §4-§6이 소유한다.

## 승인된 R3a common Place wire

[MVP-TRIP-003](tasks/MVP-TRIP-003.md)은 partial R3 foundation으로 authenticated `GET /api/places` engineering prefix-search seam과 `GET /api/places/{place_id}` text detail을 승인한다. Prefix behavior는 v1.4 §7.3의 required pg_trgm partial/similarity search를 대체하거나 완료하지 않는다. Search는 required normalized `query`, optional exact normalized `region|category`, limit 1-50와 principal/filter/limit/last `(normalized_name,place_id)`를 묶는 15분, 최대 4096 ASCII opaque cursor를 사용한다. Unknown/duplicate query, literal LIKE wildcard escaping, exact DTO/nullability와 `INVALID_REQUEST|INVALID_CURSOR|PLACE_NOT_FOUND`는 TASK §4-5가 정본이다. Catalog reads는 trip/member/Place/version/ledger/event를 쓰지 않는다.

PlaceDetail의 명시적 저장은 새 wire를 만들지 않고 기존 `POST /api/trips/{trip_id}/trip-items`를 current selected trip/version/stable request ID로 호출한다. Search/detail 선택만으로 TripItem, CELL, AI 또는 계획 event를 만들지 않는다. Wire/backend는 generated V1-V6 PostgreSQL 116/116+bootJar에서 `LOCAL VERIFIED`이고 Android parser/fixture POST shape도 검증됐다. Native continuation behavioral RED를 bounded RecyclerView viewport로 수정한 actual Android composition은 exactly two search requests, common-ID detail, dropped committed 201의 same request/version/place retry, exactly-one item/ledger/version, basket reload와 logout까지 통과했다. Production ingest/normalization, pg_trgm/PostGIS, representative image와 shared map/detail이 없어 full R3 완료가 아니며 external provider/image/map wire는 승인하지 않는다.

## 미결정 gate와 OpenAPI 생략

다음은 사용자/제품 승인 없이는 wire를 완결할 수 없다.

1. **인증 외부 표면:** backend Bearer wire, 실제 Kakao local loopback login/logout/relogin, 그리고 synthetic-provider fixture로 Android current TripItem request -> real local backend 경계는 각각 확인됐다. real Kakao와 protected request를 한 production flow로 결합한 E2E, public HTTPS와 repository OpenAPI 문서는 남아 있다.
2. **후속 operation:** R1 backend create/list/detail/basket-read, Android consumer와 integrated real-local acceptance는 통과했다. trip edit/delete/invite, basket add product UI/delete, schedule, real SSE transport와 Place search는 R2-R4에 남는다.
3. **여행 상태 확장:** 현재 구현은 종료 여행을 거부한다. 다른 상태와 그 wire를 추가하려면 별도 승인과 TASK가 필요하다.
4. **추가 operation:** 현재 구현된 route, UUID 표현, response/error code는 source와 테스트가 기준이다. real SSE 구독 route와 OpenAPI 문서는 아직 없다.

따라서 기존 basket save/current-item/auth operation과 R1 backend/Android/integrated local 제품 flow는 검증됐다. repository OpenAPI와 real SSE transport는 추가하지 않았고, 후속 operation은 별도 TASK에서 기록한다.
