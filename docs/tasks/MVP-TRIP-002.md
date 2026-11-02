# MVP-TRIP-002: R2 공동 여행 수명주기와 대표 이미지

## 1. 식별, 상태와 실행 순서

| 항목 | 값 |
| --- | --- |
| 단계 / 기준 절 | R2 / v1.4 §4.3, §5.2, §5.3, §5.5-5.7, §12.1 |
| 계약 상태 | APPROVED / DECISION-COMPLETE |
| 구현 상태 | R2a IMPLEMENTED / R2b IMPLEMENTED |
| 검증 상태 | R2a LOCAL VERIFIED / R2b LOCAL VERIFIED |
| 선행조건 | `MVP-TRIP-001` R1 local increment VERIFIED, V1-V3 applied history 보존 |
| 현재 실행 증분 | **R2 COMPLETE** |
| 다음 순차 증분 | **별도 R3 계약, NOT_STARTED** |

이 TASK가 R2a와 R2b의 wire, 데이터, Android 동작과 검증의 정본이다. [API](../API.md), [DATABASE](../DATABASE.md), [BACKEND](../BACKEND.md), [ANDROID](../ANDROID.md)는 소유 영역만 요약한다. R2a와 R2b는 하나의 R2 요구를 빠뜨리지 않고 순차 구현하기 위한 분할이며 placeholder를 허용하지 않는다.

## 2. 근거와 결정

- v1.4는 ADMIN과 MEMBER 모두 여행 이름·대표 이미지·기간, 바구니와 일정을 편집할 수 있게 하고, ADMIN만 여행 삭제, MEMBER만 여행 나가기를 허용한다. 초대는 발급 뒤 10분 동안 신규 참여에 유효하며 만료가 기존 membership을 취소하지 않는다.
- 최신 사용자 지시는 product MVP를 우선하고 external tooling/public installation을 보류한다. 이 계약은 local Android/backend/PostgreSQL 제품 흐름만 승인하며 public host, TLS, provider, 계정, secret 또는 real-user mutation을 승인하지 않는다.
- 최신 timeout 위임 기본값은 **재발급이 이전 invitation을 즉시 무효화하고 기존 membership은 유지**하는 것이다. 이는 개별 항목 직접 선택이 아니라 delegated best-judgment default로 기록한다.
- v1.4의 기간 단축 시 범위 밖 일정 처리 정책은 미확정이다. R2a는 scheduled `TripItem.schedule_date`를 자동 이동·삭제하지 않고, 새 기간 밖 항목이 하나라도 있으면 전체 edit을 `409 TRIP_DATE_RANGE_CONFLICT`로 거부한다. 날짜 확대 또는 모든 scheduled item이 새 범위 안인 변경은 허용한다. 이 선택은 데이터 손실을 막고 R4의 일정 UX를 침범하지 않는다.
- v1.4 §5.2는 일반 구성원도 invitation 링크 사용/공유가 가능하다고 적지만, 최신 위임은 member management와 invitation stop/restart/share control을 ADMIN-only로 좁혔다. 최신 결정이 우선한다. 링크를 받은 인증 사용자의 join은 역할과 무관하게 가능하다.
- R2 cover는 외부 URL/CDN/object-store/provider 대신 backend private local file root와 인증 file API를 사용한다. Android는 system document picker만 사용하고 camera, recorder, WorkManager와 영속 URI 권한을 추가하지 않는다.
- 공학 기본값의 근거: member page 20/50, cursor 15분, command success 24시간과 int64 version은 R1을 그대로 재사용한다. invitation 32-byte token은 기존 STOG opaque credential과 같은 256-bit entropy이며 10분은 v1.4 요구다. cover 8 MiB/24 MP/8192px input, 2048px display와 512px thumbnail은 한 요청의 heap/disk를 제한하면서 여행 header/list 용도를 충족하는 local MVP 상한이며 제품 원문의 고정 수치가 아니다. 측정 결과로 바꾸려면 후속 migration 없이 validation/config 계약을 새 TASK에서 명시한다.

## 3. 범위, 분할과 소유권

### R2a 포함

- 여행 `name`, `region_label`, `start_date`, `end_date` 전체 교체 edit과 날짜 기준 status 재분류.
- current member 목록, ADMIN의 `ADMIN|MEMBER` 역할 변경과 구성원 제거, MEMBER의 자기 나가기, ADMIN의 soft delete.
- 항상 ADMIN이 한 명 이상 남는 불변조건. ADMIN 자기 제거와 ADMIN leave는 금지하며, 다른 ADMIN이 있을 때 자기 역할을 MEMBER로 낮춘 뒤 leave할 수 있다.
- ADMIN-only invitation issue/reissue, share, stop와 10분 authenticated join. 링크는 여러 인증 사용자가 만료 전 사용할 수 있고 한 사용자는 한 membership만 가진다.
- custom URI deep link, paste/raw-token local test entry와 Android `ACTION_SEND` 공유. HTTPS App Link는 없다.
- R1 list/detail/basket, 기존 basket save/current item, auth principal, version/request ledger/commit callback 경계의 회귀 보존.

### R2b 포함

- current member가 system picker에서 JPEG/PNG 하나를 골라 private cover로 설정·교체·제거.
- bounded decode, canonical JPEG 재인코딩, metadata 제거, display file과 thumbnail 생성.
- R1 list/detail의 nullable cover metadata 확장, authenticated metadata/file/thumbnail read.
- file/DB 일관성, replacement와 rollback orphan cleanup, soft-deleted trip media 보존.

### 제외

Place display/search/add, basket delete, schedule move/reorder, 기간 밖 일정 자동 처리, real SSE transport, public HTTPS/App Link verification, email/SMS 발송, email signup/account, remote image URL, CDN/object store, camera/recorder, persistent background upload, Room, Instagram, map, Record/CELL, Discover/MY와 STOBEE는 R2에 없다.

### lane과 순서

1. **R2a backend lane**만 `backend/`의 additive V4, production/test를 변경한다.
2. wire가 고정된 뒤 **R2a Android lane**만 `app/`을 변경한다.
3. verifier가 R2a 전체 gate와 두 real authenticated generated user의 실제 flow를 소유한다.
4. R2a가 통과한 뒤 같은 순서로 **R2b backend V5**, **R2b Android**, **R2b verifier**를 실행한다.
5. producer lane은 TASK wire를 바꾸거나 상대 production 영역과 `.omo/artifacts/mvp-trip-002/`의 최종 E2E harness/evidence를 수정하지 않는다. verifier만 실제 E2E test/harness와 최종 artifact를 소유한다.

## 4. 공통 wire와 불변조건

- 모든 route는 `Authorization: Bearer <opaque STOG access token>`에서 만든 `AuthenticatedUser`만 사용한다. body/query에 `user_id`, caller role, status 또는 current version을 받지 않는다.
- JSON은 R1과 같이 unknown/duplicate field, missing/null required field를 `400 INVALID_REQUEST`로 거부한다. UUID, text/date normalization과 `TripResponse`의 기본 field는 R1 계약을 재사용한다.
- `expected_plan_version`과 `plan_version`은 nonnegative JSON int64다. version 증가가 필요한데 current version이 `Long.MAX_VALUE`이면 `409 PLAN_VERSION_LIMIT_REACHED`이고 아무것도 바꾸지 않는다.
- 모든 R2 command의 `request_id`는 canonical UUID다. idempotency scope는 `(principal_user_id,trip_id,command_type,request_id)`이고 성공 snapshot을 DB clock 기준 정확히 24시간 보존한다. 이는 R1 ledger를 소급 변경하지 않는 R2 전용 scope다. 같은 scope와 canonical input은 같은 result snapshot을 replay하고 입력 hash가 다르면 `409 REQUEST_ID_REUSED`다. 최초 resource creation/join/upload의 `201`은 replay에서 `200`이며 나머지는 최초 status를 유지한다. 실패 결과는 execution ledger에 저장하지 않는다.
- canonical input은 command별로 다음 exact tuple이다: update trip `(trip_id,normalized name,normalized region,parsed dates,expected version)`, role `(trip_id,target user_id,role,expected)`, remove `(trip_id,target user_id,expected)`, leave/delete `(trip_id,expected)`, issue `(trip_id,token SHA-256)`, revoke `(trip_id)`, join `(token SHA-256,normalized member_label)`, cover upload `(trip_id,expected,input SHA-256,decoded format,width,height)`, cover delete `(trip_id,expected)`. Length-prefixed binary encoding 뒤 SHA-256하며 JSON field order/raw whitespace는 fingerprint가 아니다. Cover의 exact `command_type`은 각각 `SET_TRIP_COVER_V1`, `REMOVE_TRIP_COVER_V1`이다.
- invitation identity lookup에는 SHA-256 token hash만 저장한다. Issue ledger의 `result_json`도 raw token/URI 없이 exact `result`와 `expires_at`만 저장하는 **response recipe**이며 완전한 bearer response snapshot이 아니다. Replay는 caller가 같은 request body로 다시 보낸 raw token을 canonical parse하고 그 SHA-256이 stored input hash와 constant-time으로 일치할 때만 stored recipe와 결합해 exact URI를 재구성한다. server는 raw token 또는 recoverable ciphertext를 DB/file/cache에 저장하지 않는다.
- 같은 scope/request ID의 concurrent insert는 `(principal_user_id,trip_id,command_type,request_id)` ledger PK가 승자 하나를 정한다. unique loser는 staged domain/version/file pointer를 전부 rollback하고 aborted transaction을 닫은 뒤 새 transaction에서 current authorization과 winner input/result를 readback한다. 같은 input이면 replay, 다르면 non-disclosing request conflict이며 범용 자동 retry를 추가하지 않는다.
- current member가 호출하는 mutation은 `Trip -> caller TripMember -> target TripMember` 순서로 잠근다. target이 여러 명이면 `user_id ASC`로 잠근다. authorization과 `expected_plan_version` 검사는 post-lock current row 기준이다.
- metadata, membership, delete와 cover의 실제 변경은 `plan_version`을 정확히 1 증가시킨다. normalized no-op은 성공 ledger만 만들고 version/callback을 바꾸지 않는다. join은 외부 사용자가 current version을 알 수 없으므로 `expected_plan_version`을 받지 않지만 Trip lock 뒤 정확히 1 증가시킨다. invitation issue/revoke는 plan content가 아니므로 version/callback을 바꾸지 않는다.
- version 증가 mutation은 transaction commit 뒤 기존 `TripPlanChangedEvent` 경계를 사용한다. `changed_scope` enum은 기존 `TRIP_ITEMS`에 `TRIP_METADATA|MEMBERS|COVER|TRIP_DELETED`를 additive로 더한다. rollback, replay, no-op, conflict와 invitation lifecycle에는 callback이 없다. callback 실패는 committed HTTP 성공을 rollback하지 않으며 real SSE transport는 R4다.
- absent, soft-deleted trip과 nonmember는 member route에서 동일한 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`다. 현재 member임을 확인한 뒤에만 ADMIN 권한 부족과 target member 오류를 구분한다.
- 성공 replay 전에도 current authorization을 재검사한다. 단, 자기 leave와 trip delete는 성공으로 membership/access가 사라지는 command이므로 같은 principal/command/trip/request의 unexpired success ledger만 먼저 확인해 `204` replay를 허용한다. 다른 command의 과거 성공은 제거·demotion·delete 뒤 공개하지 않는다.
- **R1 My Trips cursor supersession:** membership 철회나 soft delete로 last key가 invisible이어도 signed raw `(bucket,sort_date,last_trip_id)` 뒤의 current-visible rows를 `200`으로 계속 반환한다. Anchor 재조회와 visibility/data generation은 없다. R1의 15분 수명과 process-start in-memory AES-GCM key lifecycle은 그대로라 restart/expiry/tamper/mismatch만 `400 INVALID_CURSOR`다. Android가 자신이 관찰한 leave/remove/delete 뒤 first page를 읽는 것은 fresh-state 정책이지 token invalidation이 아니다.

## 5. R2a 정확한 HTTP 계약

### 5.1 여행 metadata edit

`PUT /api/trips/{trip_id}`, `Content-Type: application/json`

```json
{
  "name": "제주 가을 여행",
  "region_label": "제주",
  "start_date": "2026-10-10",
  "end_date": "2026-10-13",
  "expected_plan_version": 4,
  "request_id": "cb96754c-37f3-42fb-91b6-a5d9ad6cb80e"
}
```

ADMIN과 MEMBER 모두 호출한다. text/date normalization과 범위는 R1 create와 같다. 성공은 R1 detail과 같은 flat `TripResponse`를 `200`으로 반환한다. `as_of_date`와 `status`는 commit transaction의 DB UTC date로 새 dates에서 계산한다. normalized 네 field가 모두 current와 같으면 version은 그대로다.

새 기간 밖 `schedule_date`가 하나라도 있으면 `409 TRIP_DATE_RANGE_CONFLICT`이고 이 오류에만 `scheduled_outside_count` JSON int64 `>=1`을 포함한다. server는 item을 이동·삭제·수정하지 않는다.

### 5.2 member list

`GET /api/trips/{trip_id}/members?limit=20&cursor=<opaque>`

current ADMIN/MEMBER가 읽는다. `limit` default 20, 범위 1-50, cursor 규칙과 15분/startup-key/non-disclosure는 R1 cursor를 재사용하며 endpoint kind `MEMBER_LIST`, principal, trip, limit, frozen `plan_version`, last role bucket과 last `member_user_id`를 bind한다. 정렬은 `ADMIN -> MEMBER`, 각 bucket에서 `member_user_id ASC`다. page 사이 version 변화는 `409 PLAN_VERSION_CONFLICT`와 `current_plan_version`이다.

```json
{
  "trip_id": "940e857d-b025-4f72-b420-2ee354a514ad",
  "plan_version": 4,
  "member_count": 2,
  "items": [
    {
      "member_user_id": "f6f1128e-8c48-47c1-a084-6be4e854133f",
      "role": "ADMIN",
      "member_label": null,
      "is_self": true
    }
  ],
  "next_cursor": null
}
```

모든 key는 required이고 `member_label`, `next_cursor`만 nullable이다. current member UUID는 해당 여행 구성원끼리 역할 관리에만 노출하며 Android 화면은 UUID를 사용자 label로 렌더링하지 않는다. 현재 `V1__basket_save_foundation.sql`의 `stog_user`는 `id`만 있고 V2 auth도 email/profile을 저장하지 않는다. 따라서 R2a는 account/email 없이 invitation join 때 trip-scoped `member_label`을 받는다. legacy null은 `is_self`면 `나`, 아니면 role+stable ordinal fallback을 표시한다. UUID-only 제어 화면은 만들지 않는다.

### 5.3 role 변경

`PUT /api/trips/{trip_id}/members/{member_user_id}/role`

```json
{
  "role": "ADMIN",
  "expected_plan_version": 4,
  "request_id": "9f47fcdf-7c1c-4e5e-8137-d85139af35e0"
}
```

caller는 current ADMIN이어야 한다. target은 current member여야 한다. `role`은 exact `ADMIN|MEMBER`다. 같은 role은 no-op이다. ADMIN을 MEMBER로 낮춰도 commit 시 ADMIN count가 1 이상이어야 하며 아니면 `409 LAST_ADMIN_REQUIRED`다. 자기 demotion은 다른 ADMIN이 남을 때 허용한다.

성공 `200`:

```json
{
  "result": "UPDATED",
  "member_user_id": "f6f1128e-8c48-47c1-a084-6be4e854133f",
  "role": "ADMIN",
  "plan_version": 5
}
```

`result`는 `UPDATED|UNCHANGED`다.

### 5.4 구성원 제거와 나가기

ADMIN 제거: `DELETE /api/trips/{trip_id}/members/{member_user_id}`

```json
{
  "expected_plan_version": 5,
  "request_id": "3b3dfdf5-69ef-4f59-9f68-f391ffb90cac"
}
```

caller는 ADMIN이다. 자기 자신은 `409 ADMIN_SELF_REMOVAL_FORBIDDEN`이다. target ADMIN 제거 뒤에도 ADMIN이 1명 이상 남아야 한다. target membership만 삭제하고 User, 다른 trip membership, TripItem 작성자와 기존 shared data는 삭제하지 않는다. 성공/replay는 `204 No Content`다.

자기 나가기: `POST /api/trips/{trip_id}/leave`

```json
{
  "expected_plan_version": 5,
  "request_id": "2265a0d8-f906-40dd-a0bd-d8dbf0e8759b"
}
```

MEMBER만 호출한다. ADMIN은 다른 ADMIN 존재 여부와 무관하게 `403 ADMIN_CANNOT_LEAVE`다. 성공은 caller membership만 삭제하고 `204`; User와 shared data는 보존한다. 성공 뒤 일반 read는 동일 404이며 같은 leave request replay만 ledger로 204다.

### 5.5 invitation issue/reissue, stop와 exact link

ADMIN issue/reissue: `POST /api/trips/{trip_id}/invitation`

Android는 JCA `SecureRandom` 32 bytes를 base64url no-padding으로 인코딩한 exact 43 ASCII char `invitation_token`을 한 intent에 한 번 만들고, 불명확한 retry에서 같은 token/request ID를 memory에서 재사용한다.

```json
{
  "invitation_token": "n4mYxW5u4nS5JH1xQjQfn0zhvCq8kG9GqfdrkZ5ve08",
  "request_id": "6996c5d3-2db1-4b62-bbd9-aa0b4b7fa644"
}
```

Server는 exact 32-byte decode와 canonical spelling을 확인하고 identity row에는 SHA-256만 저장한다. global token-hash unique collision은 transaction 전체를 rollback하고 `409 INVITATION_TOKEN_UNAVAILABLE`로만 알리며 다른 trip 정보는 노출하지 않는다; Android는 definitive failure로 old pending intent를 폐기하고 다음 명시적 share에서 새 token/request ID를 만든다. commit DB clock부터 정확히 10분 뒤 만료한다. 새 issue는 같은 trip의 기존 ACTIVE invitation을 같은 transaction에서 REVOKED로 바꾼 뒤 새 row를 만든다. 이전 link의 신규 join은 즉시 실패하지만 membership은 바뀌지 않는다. 링크는 만료/stop/reissue 전 여러 서로 다른 인증 사용자가 사용할 수 있다.

최초 `201`, same request replay `200`:

```json
{
  "result": "ISSUED",
  "invite_uri": "stog://trip-invitations/n4mYxW5u4nS5JH1xQjQfn0zhvCq8kG9GqfdrkZ5ve08",
  "expires_at": "2026-10-01T03:14:05Z"
}
```

URI는 exact lowercase scheme `stog`, authority `trip-invitations`, path segment 하나만 허용하며 query/fragment/userinfo/port는 없다. Android share는 `Intent.ACTION_SEND`, MIME `text/plain`, `EXTRA_TEXT`에 URI 하나만 넣는다. receive는 `ACTION_VIEW` custom scheme과 Join 화면의 raw token/full URI paste 두 경로가 같은 strict parser를 사용한다. Custom scheme은 verified domain ownership을 증명하지 않으므로 앱은 parsing만으로 join하지 않고 로그인 뒤 명시적 `참여` 확인을 요구한다. token/URI를 log, analytics, saved state, screenshot evidence에 넣지 않는다.

Issue replay의 persisted recipe는 DB clock 24시간 뒤 ledger와 함께 만료·정리된다. 그 전에는 backend restart와 무관하게 caller가 같은 raw token을 다시 보낸 경우만 replay할 수 있다. raw token 암호문과 암호화 key를 두지 않으므로 decryption failure 경로도 없다. token 누락/타입 오류는 `400`, 같은 request ID에 다른 canonical token은 `409 REQUEST_ID_REUSED`, ledger expiry 뒤 요청은 새 intent로 처리한다. Android process death로 in-memory pending token을 잃으면 server recovery를 시도하지 않고 사용자가 다시 공유를 누를 때 새 token/request로 reissue한다. 모든 request/error/access log와 metric은 raw token과 URI를 record 전에 redact한다.

ADMIN stop: `DELETE /api/trips/{trip_id}/invitation`

```json
{
  "request_id": "ce5e3955-a736-407f-a444-0443bf82e43c"
}
```

성공 `200` body는 `{ "result": "REVOKED" }` 또는 active link가 없을 때 `{ "result": "UNCHANGED" }`다. **공유 중지**는 invitation만 revoke하고 trip/membership/data를 삭제하지 않는다. 이후 POST는 새 token의 **공유 재시작/reissue**다. invitation에는 별도 restart endpoint가 없다.

Issue rate는 committed new issue 기준 trip당 rolling 1시간 5회다. Join은 principal당 rolling 10분 10회, token hash당 rolling 10분 50회다. replay와 `ALREADY_MEMBER`는 issue/join 새 성공 count를 늘리지 않는다. 초과는 `429 INVITATION_RATE_LIMITED`와 해당 rolling window의 oldest counted attempt가 빠질 때까지 올림한 decimal `Retry-After`를 반환한다(issue 1-3600초, join 1-600초). Issue는 `Trip -> invitation` lock, valid-token join은 lookup 뒤 `Trip -> invitation -> principal stog_user` 순서로 잠근 뒤 재검사와 count+attempt insert를 serialize한다. invalid token은 principal row lock/rate만 적용한다. attempt row는 brute-force rate용 보안 기록이며 성공 execution ledger가 아니다. IP는 현재 trusted proxy 계약이 없으므로 key로 사용하지 않는다.

### 5.6 invitation join

`POST /api/trip-invitations/join`

```json
{
  "invitation_token": "n4mYxW5u4nS5JH1xQjQfn0zhvCq8kG9GqfdrkZ5ve08",
  "member_label": "민지",
  "request_id": "0302f303-e57f-4166-a338-d9a068f19a93"
}
```

인증은 필수이나 existing membership은 필요 없다. `member_label`은 R1 text와 같은 NFC/edge-whitespace/control 규칙의 nonblank 1-40 Unicode code points이며 trip membership에만 저장한다. 같은 label은 허용하고 authorization/account identity로 신뢰하지 않으며 UI는 중복 label에 stable ordinal을 덧붙인다.

Invitation JSON validation precedence는 exact하다. (1) 인증, (2) JSON object parsing과 field allowlist, duplicate/missing/null/type 검사, (3) canonical `request_id`와 `member_label` semantic validation, (4) token canonical parse, (5) token hash/idempotency/rate/DB 순서다. 2-3 실패는 `400 INVALID_REQUEST`다. Join의 token은 JSON string이기만 하면 단계 4에서 exact 43 ASCII `[A-Za-z0-9_-]`, no padding, base64url decode 32 bytes, re-encode equality를 검사하며 길이 42/44, `=`, `+`, `/`, non-ASCII, decode/re-encode 실패를 이유 구분 없이 `404 INVITATION_NOT_FOUND_OR_NOT_USABLE`로 반환한다. Issue endpoint의 같은 noncanonical token은 lookup이 아니라 생성 입력 오류이므로 `400 INVALID_REQUEST`다. canonical parse 전에는 hash, rate lock/count 또는 invitation lookup을 하지 않는다. server는 canonical token hash로 invitation을 찾고 Trip을 잠근 뒤 `revoked_at IS NULL`, `DB clock < expires_at`, trip not deleted를 확인한다. canonical unknown/expired/revoked/deleted도 같은 404 envelope이며 trip identity, token shape, expiry 또는 state를 노출하지 않는다.

새 사용자는 MEMBER로 insert되고 plan version이 1 증가하며 `201`:

```json
{
  "result": "JOINED",
  "trip_id": "940e857d-b025-4f72-b420-2ee354a514ad",
  "role": "MEMBER",
  "member_label": "민지",
  "plan_version": 6
}
```

Join response의 `result`, `trip_id`, `role`, `member_label`, `plan_version` key는 항상 required이고 legacy `member_label`만 nullable이다. 이미 current member가 valid link를 사용하면 `200`, `result: "ALREADY_MEMBER"`, current role/member label/version이고 request label로 기존 label을 바꾸지 않으며 mutation/callback이 없다. 만료·revoked link가 existing member의 membership을 제거하지 않지만 그 link 자체는 성공시키지 않는다. 성공 join replay는 current membership과 nondeleted trip을 다시 확인한 뒤 token이 이후 reissue되어도 같은 snapshot을 반환한다.

### 5.7 trip soft delete

`DELETE /api/trips/{trip_id}`

```json
{
  "expected_plan_version": 6,
  "request_id": "73c3869a-01ac-461c-8857-8b59065a2116"
}
```

current ADMIN만 호출한다. MEMBER는 `403 ADMIN_REQUIRED`다. 성공 transaction은 `trip.deleted_at`, `deleted_by_user_id`를 설정하고 version을 1 증가시키며 active invitation을 revoke한다. `trip_member`, `trip_item`, command history, cover metadata/file과 User는 물리 삭제하지 않는다. 성공/replay는 `204`다.

R2에는 restore/hard-delete/purge endpoint와 자동 purge job이 없다. soft-deleted trip은 list/detail/basket/member/media와 모든 새 mutation에서 absent와 같은 결과다. Delete transaction은 해당 trip을 반환하는 unexpired V3 create execution을 `invalidated_at`으로 표시한다. 같은 create request replay는 trip snapshot을 공개하거나 새 trip을 만들지 않고 `409 REQUEST_RESULT_NO_LONGER_AVAILABLE`를 반환한다. 24시간 expiry 뒤 같은 UUID는 R1 retention 규칙대로 새 intent로 처리될 수 있다. delete commit 뒤 pre-delete member recipient set을 사용해 best-effort `TRIP_DELETED` callback을 한 번 시도한다.

### 5.8 R2a 오류

기존 error envelope를 사용한다. `current_plan_version`은 version conflict에만, `scheduled_outside_count`는 date conflict에만 존재한다.

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | JSON structure/field/type와 UUID/text/date/role/version validation; issue token noncanonical |
| 400 | `INVALID_CURSOR` | member cursor invalid/tampered/expired/mismatch |
| 401 | `AUTHENTICATION_REQUIRED` | verified principal 없음 |
| 403 | `ADMIN_REQUIRED` | current member이지만 ADMIN-only command |
| 403 | `ADMIN_CANNOT_LEAVE` | ADMIN의 leave |
| 404 | `TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE` | absent/deleted/nonmember trip |
| 404 | `MEMBER_NOT_FOUND` | authorized ADMIN의 target membership 없음 |
| 404 | `INVITATION_NOT_FOUND_OR_NOT_USABLE` | join token string의 noncanonical shape 또는 canonical unknown/expired/revoked/deleted-trip token |
| 409 | `PLAN_VERSION_CONFLICT` | expected/current 또는 member page frozen version 불일치 |
| 409 | `PLAN_VERSION_LIMIT_REACHED` | version 증가 불가 |
| 409 | `REQUEST_ID_REUSED` | 같은 scope key에 다른 canonical input |
| 409 | `REQUEST_RESULT_NO_LONGER_AVAILABLE` | soft-deleted trip의 unexpired R1 create replay |
| 409 | `TRIP_DATE_RANGE_CONFLICT` | 새 기간 밖 scheduled item 존재 |
| 409 | `LAST_ADMIN_REQUIRED` | demote/remove 후 ADMIN 0명 |
| 409 | `ADMIN_SELF_REMOVAL_FORBIDDEN` | ADMIN remove endpoint로 자기 제거 |
| 409 | `INVITATION_TOKEN_UNAVAILABLE` | global token-hash collision; 다른 trip 정보 비노출 |
| 429 | `INVITATION_RATE_LIMITED` | issue/join bound 초과 |
| 503 | `DATABASE_BUSY` / `DATABASE_OPERATION_FAILED` | bounded lock/DB failure |

### 5.9 R2a state transition

| Aggregate | 허용 transition | 금지/효과 |
| --- | --- | --- |
| Trip | `ACTIVE -> DELETED` by current ADMIN | R2 restore/restart 없음. `DELETED`는 모든 제품 read/mutation에서 absent이며 rows는 보존 |
| Invitation | `NONE|EXPIRED|REVOKED -> ACTIVE` by ADMIN issue | 새 ACTIVE는 기존 unrevoked row를 REVOKED. membership 변화 없음 |
| Invitation | `ACTIVE -> REVOKED` by ADMIN stop/reissue 또는 trip delete | stop 뒤 restart는 별도 상태 복원이 아니라 새 token issue |
| Invitation | `ACTIVE -> EXPIRED` when DB clock reaches `expires_at` | 신규 join만 거부; membership 변화 없음 |
| Membership | `ABSENT -> MEMBER` by valid join | version +1, caller identity로 한 row |
| Membership | `MEMBER <-> ADMIN` by ADMIN | 결과 ADMIN count >=1; same role은 no-op |
| Membership | `MEMBER -> ABSENT` by self leave 또는 ADMIN remove | shared trip data/User는 보존 |
| Membership | `ADMIN -> ABSENT` by another ADMIN remove | self remove/leave 금지, 결과 ADMIN count >=1 |

## 6. R2b exact cover 계약

### 6.1 upload/replace

`POST /api/trips/{trip_id}/cover`는 `multipart/form-data`를 사용한다. 이는 2026-10-04 manager DAG에서 backend/Android 양쪽에 적용된 구현 정정을 2026-10-06 verifier가 현재 상호 구현 계약으로 조정한 engineering choice이며 direct-user `CONFIRMED` 결정이 아니다.

- part는 정확히 둘이다: 파일 part `image` 하나(`Content-Type: image/jpeg|image/png`, filename은 신뢰하거나 저장하지 않음)와 `Content-Type: application/json`인 `command` 하나다. `command` body는 exact `{ "expected_plan_version": <nonnegative int64>, "request_id": "<canonical UUID>" }`이며 unknown/duplicate/missing/null/type 오류는 `400 INVALID_REQUEST`다. unknown/duplicate part도 `400 INVALID_REQUEST`다.
- multipart parser file cap 8 MiB, request cap 9 MiB이며 application은 image stream을 독립적으로 8 MiB + 1 byte에서 중단한다. 한도 초과는 `413 COVER_TOO_LARGE`다.
- declared type과 decoder format이 일치해야 한다. full pixel allocation 전에 header에서 한 frame, width/height 1-8192, decoded pixel count 최대 24,000,000을 검증한다. malformed/truncated/animated/multi-frame, decoder failure와 한도 초과는 원본을 저장하지 않는다.
- server는 decoder가 반환한 pixels만 사용해 sRGB, metadata 없는 canonical JPEG로 다시 쓴다. display file은 aspect ratio를 유지해 longest edge 최대 2048, thumbnail은 최대 512이며 확대하지 않는다. JPEG quality는 0.85다. display 최대 4 MiB, thumbnail 최대 512 KiB를 넘으면 `413 COVER_TOO_LARGE`다. trailing source metadata/payload는 재인코딩 결과에 복사하지 않는다.
- Android는 `ACTION_OPEN_DOCUMENT` with `CATEGORY_OPENABLE`, MIME `image/jpeg,image/png` system picker를 사용하고 선택 뒤 bounded app-private copy와 preview를 만든 다음 사용자의 명시적 저장 확인으로 foreground upload한다. copy 동안에만 URI grant를 확보하고 즉시 release하며 persistent/background permission/session, camera, custom gallery, background retry와 WorkManager는 없다. `androidx.exifinterface` 하나는 API 23에서 source orientation을 upload pixels에 적용하는 데만 허용한다; server decode/re-encode와 bounds가 최종 신뢰 경계다.

ADMIN과 MEMBER 모두 호출한다. 최초 새 cover resource는 `201`, same-request replay는 `200`이다.

```json
{
  "result": "SET",
  "cover": {
    "cover_id": "b9d8ae64-654a-4fe3-a569-a7c9f8706524",
    "media_type": "image/jpeg",
    "byte_size": 483210,
    "width": 1600,
    "height": 1200,
    "thumbnail_byte_size": 42510,
    "thumbnail_width": 512,
    "thumbnail_height": 384,
    "file_path": "/api/trips/940e857d-b025-4f72-b420-2ee354a514ad/covers/b9d8ae64-654a-4fe3-a569-a7c9f8706524/file",
    "thumbnail_path": "/api/trips/940e857d-b025-4f72-b420-2ee354a514ad/covers/b9d8ae64-654a-4fe3-a569-a7c9f8706524/thumbnail"
  },
  "plan_version": 7
}
```

`byte_size` fields는 positive int64, dimensions는 positive int32다. upload fingerprint는 canonical headers, input byte SHA-256와 decoded dimensions/format을 포함한다. 기존 cover와 input bytes가 같아도 새 request는 새 사용자 의도이므로 새 immutable cover를 만들고 version을 증가시킨다. 이 command의 ledger scope는 `(principal_user_id,trip_id,SET_TRIP_COVER_V1,request_id)`다.

### 6.2 metadata/file/thumbnail read와 Trip DTO 확장

R2b 뒤 R1 list/detail/create와 R2 metadata edit이 반환하는 trip object에는 required nullable `cover` field를 위 object shape로 추가한다. create는 `cover:null`; legacy/current no-cover도 null이다. Join의 compact response shape는 바뀌지 않는다. R2b Android parser와 backend response를 같은 increment에서 배포하며 R1 strict response tests를 additive field에 맞춘다.

`GET /api/trips/{trip_id}/covers/{cover_id}/file`과 `GET /api/trips/{trip_id}/covers/{cover_id}/thumbnail`은 current ADMIN/MEMBER만 bytes를 `200 image/jpeg`로 읽는다. `cover_id`는 해당 trip의 current cover이거나 아직 만료되지 않은 successful command snapshot이 참조하는 retired cover여야 한다. 이 규칙으로 replacement 뒤 24시간 replay snapshot의 path와 bytes가 일치한다. absent/deleted/nonmember trip은 동일 trip 404, authorized trip의 unknown/expired-retired cover는 `404 COVER_NOT_FOUND`다. redirect, filesystem path, storage key, signed/public URL은 반환하지 않는다. file response hard cap은 4 MiB, thumbnail은 512 KiB이고 Android도 각 cap + 1 byte에서 중단한다. 모든 cover metadata와 bytes response는 `Cache-Control: private, no-store`, `X-Content-Type-Options: nosniff`다.

### 6.3 cover remove

`DELETE /api/trips/{trip_id}/cover`

```json
{
  "expected_plan_version": 7,
  "request_id": "7a569449-402f-4f15-9568-fae7d0c7a63d"
}
```

ADMIN/MEMBER 모두 호출한다. 이 command의 ledger scope는 `(principal_user_id,trip_id,REMOVE_TRIP_COVER_V1,request_id)`다. current cover가 있으면 pointer를 null로 하고 version을 1 증가시켜 exact `200 {"result":"REMOVED","plan_version":8}`다. 이미 null이면 expected version을 먼저 확인한 뒤 exact `200 {"result":"UNCHANGED","plan_version":7}`를 ledger에 저장하고 version/callback/file을 바꾸지 않는다. 같은 remove scope replay는 cover가 나중에 생겨도 current authorization 재검사 뒤 stored status/body `200`을 그대로 반환한다.

Upload와 remove가 같은 UUID를 사용해도 command type이 다르므로 `REQUEST_ID_REUSED`가 아니며 서로 별도 ledger row다. 같은 expected version으로 동시에 오면 Trip lock 승자만 commit하고 loser는 `PLAN_VERSION_CONFLICT`; 순차 요청이 각각 current expected version을 보내면 둘 다 실행된다. cover blob/file에는 `(principal,trip,request_id)` 또는 그 변형의 별도 uniqueness/claim을 만들지 않는다.

### 6.4 file transaction과 cleanup

- backend property `stog.media.root` / environment `STOG_MEDIA_ROOT`가 static resource tree 밖의 private local directory를 지정한다. configuration이 없거나 root가 symlink/reparse traversal, writable regular directory 조건을 만족하지 않으면 R2b startup은 fail closed한다. source/default/public directory를 fallback으로 쓰지 않는다. generated verifier는 task-owned temp root만 설정한다.
- request body는 task-owned temp file에 bounded streaming하고 decode/re-encode한 두 output을 temp에 만든다. token, user ID와 original filename을 경로/metadata로 사용하지 않는다.
- DB transaction에서 Trip/caller membership/version을 잠그고 검증한 뒤 random `cover_id` final directory로 same-volume atomic move한다. 그 다음 `trip_cover` row, `trip.cover_id`, version과 execution snapshot을 commit한다. DB commit 전 final move 실패는 rollback이다. commit 실패 시 새 final directory를 삭제 시도하고 실패하면 orphan marker 없이도 reconciliation 대상이 된다.
- startup과 하루 한 번의 bounded reconciliation은 DB에 없는 final directory 또는 1시간 넘은 temp만 삭제한다. current pointer 또는 unexpired successful command가 참조하는 file은 삭제하지 않는다.
- replace/remove된 cover row는 `retired_at`을 기록한다. 해당 cover를 참조하는 unexpired command가 없고 24시간이 지난 뒤에만 bytes를 지운다. metadata row는 R2에서 hard delete하지 않는다. cleanup 실패는 current response를 실패로 바꾸지 않고 non-secret warning/metric 후 다음 bounded pass에서 재시도한다.
- soft-deleted trip의 current/retired cover bytes와 metadata는 R2에서 자동 삭제하지 않는다. hard deletion/retention은 별도 운영 승인 전 `NOT_IMPLEMENTED`다.

### 6.5 R2b 오류

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | missing/malformed header 또는 request |
| 400 | `INVALID_COVER` | decode, format mismatch, dimensions/frame validation |
| 404 | `TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE` | absent/deleted/nonmember trip |
| 404 | `COVER_NOT_FOUND` | authorized current trip에 cover 없음 |
| 409 | `PLAN_VERSION_CONFLICT` / `PLAN_VERSION_LIMIT_REACHED` / `REQUEST_ID_REUSED` | 공통 규칙 |
| 413 | `COVER_TOO_LARGE` | input/decoded/output bound 초과 |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | JPEG/PNG 외 Content-Type/format |
| 503 | `MEDIA_STORAGE_FAILED` | private file root/write/move failure |
| 503 | `DATABASE_BUSY` / `DATABASE_OPERATION_FAILED` | DB failure |

## 7. additive V4/V5 데이터 계약

적용된 V1-V3 파일과 checksum을 수정·재실행하지 않는다. implementation은 `V4__trip_collaboration_lifecycle.sql`, 이후 `V5__trip_private_cover.sql` 두 migration만 추가한다. 아래 legacy row는 이름/date/role/item/user를 rewrite하거나 삭제하지 않는다.

### V4

1. `trip.deleted_at TIMESTAMPTZ NULL`, `trip.deleted_by_user_id UUID NULL REFERENCES stog_user(id) ON DELETE RESTRICT`, 둘 다 null 또는 둘 다 non-null check. `trip_create_execution.invalidated_at TIMESTAMPTZ NULL`과 `trip_create_result_trip_idx ON trip_create_execution(result_trip_id) WHERE invalidated_at IS NULL`을 additive로 추가하며 delete transaction만 해당 result trip의 unexpired row에 값을 쓴다. `trip_active_status_start_idx ON trip(start_date,id) WHERE deleted_at IS NULL`, `trip_active_status_end_idx ON trip(end_date,id) WHERE deleted_at IS NULL`을 추가하고 existing indexes를 교체하지 않는다.
2. `trip_invitation`: `id UUID PRIMARY KEY`, `trip_id UUID NOT NULL REFERENCES trip(id) ON DELETE RESTRICT`, `token_hash BYTEA UNIQUE NOT NULL CHECK(octet_length(token_hash)=32)`, `created_by_user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT`, `issued_at TIMESTAMPTZ NOT NULL`, `expires_at TIMESTAMPTZ NOT NULL`, `revoked_at TIMESTAMPTZ NULL`, expiry exact `issued_at + interval '10 minutes'`, revoke는 issue 이후 check. trip당 unrevoked row는 partial unique `(trip_id) WHERE revoked_at IS NULL` 하나다. usable ACTIVE는 `revoked_at IS NULL AND DB clock < expires_at`로 판정하며 새 issue는 expired unrevoked row도 revoke한 뒤 insert한다.
3. `trip_command_execution`: `principal_user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT`, `command_type VARCHAR(64) NOT NULL`, `request_id UUID NOT NULL`, `trip_id UUID NOT NULL`(snapshot 보존을 위해 FK 없음), `input_sha256 BYTEA NOT NULL CHECK(octet_length(input_sha256)=32)`, `response_status SMALLINT NOT NULL CHECK(response_status BETWEEN 200 AND 299)`, `result_json JSONB NOT NULL CHECK(jsonb_typeof(result_json)='object')`, `completed_at TIMESTAMPTZ NOT NULL`, `expires_at TIMESTAMPTZ NOT NULL`, PK `(principal_user_id,trip_id,command_type,request_id)`, expiry exact 24h. `204` command는 empty `{}` snapshot을 저장한다. Issue row의 `result_json`은 `result`와 `expires_at`만 포함하고 raw token/URI/ciphertext를 금지한다. V4 command allowlist는 `UPDATE_TRIP_V1|UPDATE_MEMBER_ROLE_V1|REMOVE_MEMBER_V1|LEAVE_TRIP_V1|ISSUE_TRIP_INVITE_V1|REVOKE_TRIP_INVITE_V1|JOIN_TRIP_V1|DELETE_TRIP_V1`다.
4. `trip_invitation_attempt`: `id BIGSERIAL PRIMARY KEY`, `principal_user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT`, `token_hash BYTEA NOT NULL CHECK(octet_length(token_hash)=32)`, `attempted_at TIMESTAMPTZ NOT NULL`, `succeeded BOOLEAN NOT NULL`; rate index `(principal_user_id,attempted_at)`와 `(token_hash,attempted_at)`. raw token/trip identity/error text는 없다. inline bounded cleanup은 1시간보다 오래된 rows만 지운다.
5. `trip_member.member_label VARCHAR(40) NULL`과 nullable이 아니면 `character_length BETWEEN 1 AND 40` check를 추가한다. legacy membership은 null로 보존하고 새 join만 normalized non-null label을 쓴다. last ADMIN은 service가 Trip lock과 ADMIN membership row locks 아래 검사한다. PostgreSQL check로 cross-row count를 가장하지 않는다. 기존 `(trip_id,user_id)` PK와 role check를 유지한다.

### V5

1. `trip_cover`: `id UUID PRIMARY KEY`, `trip_id UUID NOT NULL REFERENCES trip(id) ON DELETE RESTRICT`, `created_by_user_id UUID NOT NULL REFERENCES stog_user(id) ON DELETE RESTRICT`, `media_type VARCHAR(32) NOT NULL CHECK(media_type='image/jpeg')`, `storage_key UUID UNIQUE NOT NULL`, `byte_size BIGINT NOT NULL`, `width INTEGER NOT NULL`, `height INTEGER NOT NULL`, `thumbnail_byte_size BIGINT NOT NULL`, `thumbnail_width INTEGER NOT NULL`, `thumbnail_height INTEGER NOT NULL`; checks는 `byte_size BETWEEN 1 AND 4194304`, `thumbnail_byte_size BETWEEN 1 AND 524288`, display width/height `BETWEEN 1 AND 2048`, thumbnail width/height `BETWEEN 1 AND 512`다. `input_sha256 BYTEA NOT NULL CHECK(octet_length(input_sha256)=32)`, `created_at TIMESTAMPTZ NOT NULL`, `retired_at TIMESTAMPTZ NULL` 및 `retired_at IS NULL OR retired_at >= created_at` check.
2. `trip.cover_id UUID NULL REFERENCES trip_cover(id) ON DELETE RESTRICT`. DB constraint trigger는 cover가 같은 trip인지 강제하고 deferred transaction end에 검사한다. replace는 새 row insert와 pointer switch를 한 transaction에서 수행한다.
3. V5는 `trip_command_execution` command check를 drop/recreate해 V4 allowlist에 `SET_TRIP_COVER_V1|REMOVE_TRIP_COVER_V1`을 더한다. V4 migration 파일을 수정하지 않는다. 두 cover command는 기존 R2 PK `(principal_user_id,trip_id,command_type,request_id)`만 atomic execution claim으로 사용한다. `trip_cover` 또는 별도 blob table/path에는 principal/trip/request 기반 unique constraint를 추가하지 않고 `id`와 `storage_key` identity uniqueness만 둔다.
4. 기존 trip과 legacy rows의 cover는 null이다. V5는 existing media를 발명하거나 file을 backfill하지 않는다. soft delete는 V1 cascade를 호출하지 않는다.

## 8. 동시성, delete와 retention 판정

- 같은 version의 두 metadata/member/cover command는 Trip lock 승자 하나만 version을 올리고 loser는 current version conflict다.
- concurrent demote/remove 두 건은 ADMIN rows를 같은 순서로 잠가 둘 다 last ADMIN을 없애는 결과를 만들 수 없다.
- join과 delete는 같은 Trip lock으로 serialize한다. join 먼저면 MEMBER+version이 commit된 뒤 delete가 soft-delete하고 모든 access를 숨긴다. delete 먼저면 join은 generic unusable invitation이다. 반쪽 membership이나 deleted trip 신규 join은 없다.
- invitation reissue와 join은 공통 prefix lock 순서를 `Trip -> invitation`으로 통일하고 join만 그 뒤 principal `stog_user`를 잠근다. join이 먼저 commit되면 membership은 유지되고 뒤 reissue는 link만 revoke한다. reissue가 먼저면 old token join은 거부된다.
- member remove/leave와 in-flight plan edit은 Trip lock으로 serialize한다. edit이 먼저 commit되거나 membership removal이 먼저 commit되어 edit이 404/403이 되며, 제거된 principal은 과거 성공 snapshot을 읽지 못한다.
- soft delete는 shared rows를 물리 삭제하지 않는다. R2 verifier cleanup도 generated fixture가 아닌 existing/real/shared row나 media를 삭제하지 않는다. generated isolated schema 전체 drop만 ownership receipt 뒤 허용한다.

## 9. Android R2 계약

### R2a

- 기존 `TripViewModel`, shared auth transport와 FragmentManager를 확장한다. 별도 auth/client/navigation/DI stack을 만들지 않는다.
- TripDetail의 `편집`은 두 role 모두 보이고, `구성원 관리`, `초대 공유/공유 중지`, `여행 삭제`는 ADMIN에게만 보인다. `여행 나가기`는 MEMBER에게만 보인다. server 권한이 최종 기준이며 stale UI action의 403/404를 처리한다.
- member list는 UUID를 화면 text/content description에 표시하지 않는다. `나` 또는 trip-scoped `member_label`과 role을 표시하고 legacy null만 stable ordinal+role fallback을 쓴다. role 변경/제거에는 확인 dialog와 target stable ID를 사용한다.
- invite share는 ACTION_SEND chooser를 실제 연다. ACTION_VIEW 또는 paste/raw token은 로그인 전이면 token을 memory에만 보존하고 login 성공 뒤 join 확인 화면으로 이어진다. process death에서는 secret token을 복원하지 않고 다시 링크를 열거나 붙여넣도록 안내한다.
- edit/member/join/delete의 ambiguous transport failure는 same canonical input/request ID로 retry한다. input 변경, definitive validation/conflict, explicit discard 뒤 새 intent만 새 ID를 쓴다. conflict는 current detail/member first page를 다시 읽고 사용자가 다시 제출하게 한다.
- success delete/leave/remove-self-equivalent 뒤 list/detail/private state와 local cursor를 지우고 My Trips first page를 다시 읽는다. 이는 fresh UI policy다. 다른 client가 가진 R1 My Trips cursor는 anchor trip의 visibility가 사라져도 signed raw tuple로 계속 유효하며 backend가 anchor 존재 검사를 하지 않는다. 초대 stop/reissue는 existing members/pages를 지우지 않는다.

### R2b

- thumbnail과 file은 shared Bearer/refresh-one-time transport로만 읽고 redirect를 거부한다. bitmap decode는 main thread 밖, target bounds를 적용하며 memory cache만 허용한다. logout/auth-ended/member removal/trip delete에서 request와 private bitmap/cache/state를 지운다.
- picker URI, bitmap, raw bytes와 invite token은 SavedState에 넣지 않는다. foreground upload 중 recreation은 in-flight operation을 retained state owner가 유지할 때만 계속하고, process death에서는 자동 재개하지 않는다.
- old image request는 row/cover generation으로 취소·무효화한다. loading/missing/error/content와 upload progress/retry를 구분한다. ambiguous upload retry는 같은 bytes hash/request ID를 사용하고 사용자가 다른 이미지를 고르면 새 intent다.
- list에는 thumbnail만, detail에는 display file만 요청한다. original input은 server에 남지 않으며 UI에서 원본 보존을 암시하지 않는다.

## 10. Behavioral RED와 검증

### R2a backend RED

- authenticated member edit succeeds while ADMIN-only controls reject MEMBER after membership lookup.
- date contraction with outside scheduled rows returns count and mutates nothing.
- issue persists token hash plus non-secret response recipe only, expires at exact DB 10 minutes, same-token replay reconstructs URI without recoverable server token storage, reissue revokes old, stop/restart leaves memberships intact.
- malformed JSON/missing/duplicate/wrong token type are 400; join noncanonical Base64URL/length and canonical unknown/expired/revoked/deleted are the same 404 before lookup disclosure. Join creates MEMBER/version/event once; valid duplicate is `ALREADY_MEMBER`; response-loss replay does not duplicate.
- My Trips cursor continues from its signed raw key after anchor visibility loss; Android-observed delete/leave still triggers a fresh first-page read.
- concurrent demote/remove never produces zero ADMIN; ADMIN leave/self-remove and MEMBER delete are rejected.
- concurrent join/delete and edit/remove serialize with no orphan access; soft delete preserves all shared rows and hides every read/media route.
- command scope, 24h replay, changed input conflict, current authorization, rollback and after-commit callback exact scopes.

### R2a Android RED

- role-based control visibility plus server-denial rendering, metadata draft/date conflict/version recovery.
- deep-link/raw-token strict parser, ACTION_SEND exact URI, login-resume in memory and no saved/logged token.
- two-user member list/role/remove/leave/delete states, recreation/Back/stale callback/auth-ended/private-state cleanup.

### R2b RED

- backend byte cap, MIME/decode/frame/pixel limits, metadata stripping, exact dimensions, private auth on trip DTO/file/thumbnail.
- replacement/version/replay, command-scoped upload/remove same-UUID behavior and exact no-op remove replay, DB failure and bounded serialized crash-window orphan reconciliation, retired cleanup and soft-delete retention.
- Android picker contract, bounded off-main decode/upload, private image cancellation/cache clearing, list thumbnail/detail image states.

### actual gate와 실제 사용자 흐름

R2a verifier는 generated isolated V1-V4 PostgreSQL과 normal synthetic-provider exchange로 **서로 다른 real auth user 두 명**을 security filter를 통해 로그인시킨다. principal injection, controller bypass와 직접 UUID 제어 UI는 성공 증거가 아니다.

1. User A가 기존 제품 UI에서 trip을 만들고 metadata를 edit한다.
2. A가 invitation을 발급하고 ACTION_SEND payload를 test intent monitor로 관찰한다. User B는 actual auth exchange 뒤 ACTION_VIEW URI로 join한다.
3. B가 같은 trip detail을 열고 metadata를 edit해 두 역할의 동등 편집을 증명한다. stale A edit은 conflict 후 REST reload로 회복한다.
4. A가 B role을 변경·복원하고, B가 old reissued link와 expired link에서 같은 404를 받지만 membership이 유지되는 것을 확인한다. Actual HTTP는 missing/duplicate/null/number/object token을 400으로, 42/44-char·padding·invalid alphabet token과 canonical unknown token을 404로 확인하며 noncanonical token은 hash/rate/DB lookup 전에 끝나야 한다.
5. disposable trips에서 MEMBER leave, ADMIN last-admin guard, ADMIN soft delete와 모든 후속 비노출을 UI/API로 확인한다. 삭제된 cursor anchor의 R1 My Trips continuation은 `200` current-visible page이고, 삭제를 직접 관찰한 Android는 first page를 다시 읽는다.

R2b verifier는 generated JPEG/PNG content URI를 system picker result로 전달해 A가 실제 UI에서 upload한다. B가 list thumbnail/detail file을 실제 backend에서 읽고, nonmember는 metadata/file/thumbnail 모두 동일 비노출을 받는다. B replacement와 A reload, response-loss replay exactly one current cover, no-cover remove의 exact replay, upload/remove same UUID의 distinct command rows와 expected-version serialization, remove와 soft-delete retention/orphan cleanup receipt를 확인한다.

모든 async test는 action 전에 exact state/event를 subscribe하고 bounded timeout으로 신호를 기다린다. fixed sleep/polling은 금지한다. 2026-10-06 최신 사용자 결정에 따라 STOG host Activity는 portrait-only이며, 양쪽 landscape device rotation에서도 portrait를 유지한다. lifecycle 복원은 방향 전환 대신 `ActivityScenario.recreate()`로 별도 검증한다. Backend changed diagnostics + 전체 PostgreSQL/bootJar, Android changed diagnostics + JVM/instrumentation/Lint/assemble, 실제 Pixel_8 API 36 native light/dark·portrait lock·1.3 font·keyboard/insets·Back·accessibility focus와 crash scan을 각 increment 끝에 수행한다. verifier는 owned process, reverse, emulator settings, generated schema/role/files만 정리하고 pre-existing aggregate/file fingerprint 불변을 기록한다.

## 11. 완료 조건과 미실행 경계

R2a는 `IMPLEMENTED / LOCAL VERIFIED`다. isolated V1-V4 PostgreSQL 91/91과 `bootJar`, generated normal-auth user 두 명의 실제 Android -> Spring -> PostgreSQL collaboration/lifecycle 흐름과 정리는 통과했다. 기존 member screenshot과 programmatic `assertRow`만으로 viewport 도달성을 증명하지 못했던 실패와 세 차례 blocked proof는 보존했다. native member-list 높이 correction 뒤 test harness는 Activity 생성 전에 exact font configuration event를 기다리고, 실제 64dp swipe만 사용해 font 1.30 portrait/landscape에서 `나`와 `민지` 각각의 역할 변경·제거 버튼을 90% 이상 전역 노출한 뒤 Espresso tap으로 정확한 dialog를 열고 취소했다. 표적 proof 4/4, 16개 secret-free 화면 검수, 변경 Android JVM 16개·instrumentation 33/33(`CollaborationApiClientTest` 포함)·Lint·assemble, crash scan과 emulator/settings/reverse/app cleanup이 통과했다. layout/test-only delta라 backend 91/91+bootJar와 strict protocol E2E는 재실행하지 않고 기존 PASS를 재사용했다. 상세 결과는 [R2a final verification](../../.omo/artifacts/mvp-trip-002/r2a/final-verification.json)에 있다.

R2a와 R2b는 `IMPLEMENTED / LOCAL VERIFIED`다. R2b final gate는 corrected backend 111/111+bootJar, Android JVM16/instrumentation44/Lint/assemble과 native `ACTION_OPEN_DOCUMENT` -> selective response-loss proxy -> corrected bootJar -> isolated PostgreSQL V1-V5/private root 실제 흐름을 통과했다. Initial green SET/display/thumbnail, blue replacement의 committed `201` response loss와 same-request `200` replay, removed/deleted metadata·file·thumbnail generic 404, native remove cancel/confirm, exact UNCHANGED replay, invalid byte/pixel/MIME 입력, soft-delete retention, expected pixels와 logout cleanup이 PASS했다. DB는 cover 4, SET ledger 4, REMOVE ledger 3, UNCHANGED ledger 1, main version 9였고 private directory/display/thumbnail은 각각 4쌍이었다. role/schema `0|0`, private root와 owned runtime/reverse/settings cleanup 및 기존 metadata 불변도 확인했다. 최종 증거는 `.omo/artifacts/mvp-trip-002/r2b/final-verification.json`이다. R3 구현은 시작하지 않았다. HTTPS App Link, public host/TLS, real Kakao, external verification, account/secret/real-user mutation도 `NOT_RUN`으로 유지한다. current account profile 부재는 join-time trip-scoped member label로만 보완하며 email signup/global profile/account flow를 R2에 발명하지 않는다.
