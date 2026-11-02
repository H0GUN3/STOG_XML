# Database 책임 경계

상태: `M1-SHARED-FOUNDATION-002` basket save의 구현된 최소 schema와 검증된 불변조건이다. 전체 v1.4 schema와 후속 기능 schema는 포함하지 않는다. 처리 순서는 [BACKEND.md](BACKEND.md), wire는 [API.md](API.md)가 소유한다.

## 확정 데이터 불변조건

- `Place`는 검색·지도·추천·import·여행 저장이 공유한다. 이번 동작은 기존 Place만 참조하며 생성하거나 공급자별 Place를 만들지 않는다.
- 바구니와 일정은 동일 `TripItem`의 상태다. 바구니는 일정 날짜와 순서가 모두 없고, 일정은 둘 다 있다. 이번 동작은 바구니만 만든다.
- 여행 전체의 `plan_version`은 덮어쓰기 방지 단위다. 성공한 신규 계획 mutation 한 건당 정확히 1 증가하며, 실패와 replay에서는 증가하지 않는다.
- 성공한 `request_id`의 입력 trip, place, expected version과 응답 결과, `TripItem`/새 plan version은 원자적으로 보존한다.
- 같은 여행의 같은 Place basket은 사용자와 무관하게 하나다. 새 request key가 이미 존재하는 basket을 가리키면 `ALREADY_EXISTS` result를 저장하고 기존 item, metadata, version, event를 바꾸지 않는다.
- SSE는 DB 원본이 아니며 이번 범위에 durable event/outbox table을 만들지 않는다.

## 최소 논리 관계

아래 필드는 현재 migration에 구현된 의미다. ID는 UUID, plan version은 nonnegative BIGINT, sort order는 NULL 또는 양수(>0)이며 Java `long`과 API int64 경계를 사용한다.

| 논리 관계 | 최소 필드 | FK / check / 의미 |
| --- | --- | --- |
| `User` (기존) | `id` | 인증 principal과 저장 사용자의 기준. 인증 provisioning 필드는 범위 밖 |
| `Trip` (기존) | `id`, `start_date`, `end_date`, `plan_version` | `start_date <= end_date`, `plan_version >= 0`. 진행중·예정 지원과 저장 시 상태 재검사에 필요 |
| `TripMember` (기존) | `trip_id`, `user_id`, `role` | `trip_id -> Trip`, `user_id -> User`; `(trip_id,user_id)` 고유; role은 관리자/일반 구성원만 허용 |
| `Place` (기존) | `id` | 이번 저장에는 identity와 존재 여부만 필요. 이름·좌표·공급자 필드를 새로 요구하지 않음 |
| `TripItem` | `id`, `trip_id`, `place_id`, `created_by_user_id`, `created_at`, `schedule_date`, `sort_order` | 각각 `Trip`, `Place`, `User` FK. `created_at`은 기존 `TIMESTAMPTZ`. `(schedule_date IS NULL AND sort_order IS NULL) OR (둘 다 NOT NULL AND sort_order > 0)`. 생성 시 둘 다 NULL |
| `RequestExecution` | `principal_user_id`, `command_type`, `request_id`, `input_trip_id`, `input_place_id`, `input_expected_plan_version`, `result_kind`, `result_trip_item_id`, `result_place_id`, `result_plan_version`, `completed_at`, `expires_at` | `principal_user_id -> User`. 성공 응답 snapshot이며 mutable `TripItem`을 다시 읽어 재구성하지 않음. scope 고유성은 아래와 같음 |

`TripItem.created_by_user_id`는 body 값이 아니라 commit 시 인증 principal이다. `RequestExecution` 결과 snapshot은 `CREATED` 또는 `ALREADY_EXISTS`, item/place identity, 결과 version, 완료·만료 시각을 보존한다. snapshot과 live TripItem의 이후 상태가 달라져도 retry 결과를 바꾸지 않는다.

**IMPLEMENTED check (현재 basket save branch):** 이 command의 `command_type`은 하나의 허용값이고, 입력 version과 결과 version은 nonnegative BIGINT다. `result_kind`는 `CREATED` 또는 `ALREADY_EXISTS`이며 `expires_at`은 `completed_at + 24 hours`다. 기존 항목 반환 duplicate 성공은 200 response, same item metadata, unchanged version과 event로 저장·replay한다.

## 고유성·인덱스 범위

**IMPLEMENTED 최소 제약/인덱스:**

- `TripMember`: unique/primary key `(trip_id, user_id)`. 권한 재검사와 잠금에 그대로 사용한다.
- `RequestExecution`: unique `(principal_user_id, command_type, request_id)`. `request_id` 단독 또는 `(trip_id, request_id)`가 아니다.
- `TripItem`: primary identity `id`, FK 검사와 여행 바구니 조회를 위한 `trip_id` index, Place 참조 점검을 위한 `place_id` index, basket 상태의 `(trip_id, place_id)` partial unique index.
- `Trip`, `Place`, `User`: 기존 primary identity만 이번 동작이 요구한다.

basket 상태에는 `(trip_id, place_id)` partial unique index가 구현되어 있다. 일정 상태와의 후속 이동·정렬 규칙은 이 slice에 포함하지 않는다.

## Transaction·잠금 불변조건

CREATED branch의 하나의 transaction에서 다음 쓰기만 함께 commit한다.

1. 바구니 `TripItem` 1개 생성.
2. 조건이 맞는 `Trip.plan_version`을 `expected + 1`로 갱신.
3. 성공한 `RequestExecution` 결과 snapshot 생성.

ALREADY_EXISTS는 별도의 result-only transaction으로 기존 item/version과 결과 snapshot만 반환·저장하고 version/event를 바꾸지 않는다. 구현된 one-item GET은 read-only이며 쓰기나 새 schema를 만들지 않는다.

중간 실패, constraint 위반, timeout, process 오류면 위 성공 transaction의 세 쓰기를 모두 rollback한다. commit 전 SSE를 보내지 않는다. 실패 ledger는 만들지 않는다. 구체적인 잠금 순서와 membership 제거 경합은 [BACKEND.md](BACKEND.md#명령-처리-순서와-동시성)를 따른다.

같은 idempotency key의 동시 insert는 고유 제약이 단일 승자를 정한다. unique loser의 PostgreSQL transaction은 domain stage를 포함해 전부 rollback한 뒤 닫는다. 새 transaction에서 target Trip/membership을 다시 검사하고 winner result를 읽어 동일 입력이면 replay, 다르면 conflict로 판정한다. aborted transaction 안에서 재조회하지 않고 범용 retry 계층을 추가하지 않는다.

## 승인된 R1 additive V3

[MVP-TRIP-001](tasks/MVP-TRIP-001.md#8-additive-v3-데이터-계약)은 적용된 V1/V2를 수정하지 않는 V3를 승인한다. 기존 row를 backfill/rename/update하지 않고 `trip.name`과 `trip.region_label`을 nullable로 추가하되 새 create는 normalized non-null 1-100 code points만 저장한다. membership-scoped list/date keyset index와 별도 `trip_create_execution`을 추가한다.

Create ledger key는 `(principal_user_id, CREATE_TRIP_V1, request_id)`이고 normalized input, immutable success result snapshot, DB clock의 `completed_at`과 정확히 24시간 뒤 `expires_at`을 가진다. Trip version 0, creator `ADMIN`, ledger는 한 transaction이다. unique loser는 staged Trip/member/result를 전부 rollback한 뒤 새 authorized transaction에서 winner를 읽는다. 실패 ledger는 없다. 기존 `request_execution`, common Place/TripItem, plan version과 V1/V2 row는 그대로다.

Trip/basket read cursor는 schema에 저장하지 않는다. process-local in-memory key의 authenticated-encrypted token이고 restart/expiry 때 `400 INVALID_CURSOR` 후 첫 page 재조회한다. My Trips는 R2 soft delete/membership visibility 변경에도 DB generation이나 anchor FK를 추가하지 않고 cursor의 raw sort tuple로 current-visible rows를 이어 읽는다. basket page는 cursor에 frozen `plan_version`을 묶어 섞인 version을 반환하지 않는다. 상세 column/check/index 정의는 TASK가 정본이다. V3 checksum `b6079d26454442b1373b65650e46a62218427c7c15f595edd7d784e60a2ab206`은 producer handoff와 실제 파일이 일치하며 canonical JDK 17 runner에서 isolated V1-V3 migration, 전체 PostgreSQL test, generated role/schema cleanup `0|0`과 pre-existing metadata 불변을 확인했다. 상태는 `IMPLEMENTED / EXACT POSTGRESQL GATE VERIFIED`; Android exact gate와 integrated real-local R1도 통과했다. 최종 generated V1-V3 실행은 create response-loss/replay의 trip/member/ledger 각 1개와 generated ID-only basket 1개를 확인했고 종료 뒤 role/schema `0|0`, 기존 metadata와 durable DB 불변을 확인했다.

## 승인된 R2 additive V4/V5

[MVP-TRIP-002](tasks/MVP-TRIP-002.md#7-additive-v4v5-데이터-계약)은 applied V1-V3를 수정하지 않고 R2a V4, 이후 R2b V5를 추가한다. V4는 `trip.deleted_at/deleted_by_user_id`, V3 create result의 additive `invalidated_at`, legacy-nullable trip-scoped `trip_member.member_label`, token-hash lookup만 보존하는 10분 `trip_invitation`, 24시간 `trip_command_execution`, bounded token-hash invitation attempt rows를 추가한다. Issue execution recipe에는 result/expiry만 있고 raw token/URI/recoverable ciphertext는 없다. R2 ledger PK는 `(principal_user_id,trip_id,command_type,request_id)`다. soft delete는 membership, TripItem, User와 command history를 cascade/hard delete하지 않는다. last ADMIN은 Trip과 ADMIN membership row lock 아래 service가 보장한다.

V5는 immutable `trip_cover` metadata와 nullable `trip.cover_id`를 추가한다. Cover upload/remove는 command가 PK에 포함되므로 같은 request UUID를 독립적으로 사용할 수 있고, blob에는 request-ID uniqueness를 중복 생성하지 않는다. legacy trip은 cover null이고 기존 row/file을 발명하거나 backfill하지 않는다. private file root는 DB 밖이며 current pointer가 참조하는 bytes는 먼저 존재해야 한다. rollback orphan, retired cover와 temp cleanup은 TASK §6.4의 1시간/24시간 참조 규칙을 따르고 soft-deleted trip media는 R2에서 자동 purge하지 않는다.

Metadata/member/delete/cover 실제 변경과 join은 Trip lock에서 plan version을 정확히 1 증가시키며 invitation issue/revoke, replay와 normalized no-op은 증가시키지 않는다. V4는 additive migration으로 구현됐고 isolated non-superuser PostgreSQL 전체 91개와 generated two-user runtime에서 `LOCAL VERIFIED`다. 적용된 V1-V3는 수정하지 않았다. V5와 cover runtime은 `NOT_STARTED / NOT_RUN`이다.

## 승인된 R3a additive V6

[MVP-TRIP-003](tasks/MVP-TRIP-003.md)은 applied V1-V5를 수정하거나 legacy `place(id)` rows를 backfill하지 않는 `V6__common_place_catalog.sql`만 승인한다. Same common `place`에 nullable source pair, display/search text와 nullable coordinate pair를 additive로 두며 source pair/coordinates pair consistency, bounds, partial source uniqueness와 normalized-name prefix keyset index를 TASK §7대로 적용한다. Name/search key가 null인 legacy rows는 기존 TripItem FK와 fallback read에는 남지만 catalog search/detail에는 노출하지 않는다.

V6는 `IMPLEMENTED / LOCAL VERIFIED`이며 generated non-superuser V1-V6 PostgreSQL 전체 116/116+bootJar에서 migration/check/index/legacy-row 경계와 role/schema `0|0` cleanup을 통과했다. R3a는 PostGIS/pg_trgm extension, production rows와 ingest job을 만들지 않는다. 이는 v1.4 §7.3의 adopted pg_trgm partial/similarity와 PostGIS spatial behavior를 취소하는 것이 아니라 partial slice limitation이다. 사용자는 `무료 공공데이터` category를 선택했지만 production ingest는 미승인이다. Preserved official snapshot의 source parsing, deduplication, refresh와 normative extension behavior는 full R3 계약에서 닫는다. Generated non-superuser runner가 cluster extension을 설치할 수 있다고 가정하지 않는다.

## R3b SEMAS source identity와 upsert

[MVP-TRIP-004](tasks/MVP-TRIP-004.md)은 새 migration 없이 existing V6 `(source_type,source_key)`를 사용한다. `SEMAS_STORE + 상가업소번호`가 stable identity이고 rerun/update는 기존 `place.id`를 보존한다. 이름이 같다는 이유로 merge하지 않으며 서로 다른 source key의 후보는 별도 Place로 남고 aggregate review count만 기록한다. Import는 CSV entry별 transaction과 최대 500-row temp-stage batch를 사용한다. Parse/validation/duplicate/JDBC 실패는 entry 전체를 rollback하며 absent row를 삭제하거나 closure로 추정하지 않는다.

Official bounded subset과 synthetic rollback/update tests는 generated isolated V1-V6에서 `LOCAL VERIFIED`다. Full suite는 122/122+bootJar, cleanup `0|0`과 metadata 불변을 통과했다. No retained dev/production rows were written. Quarterly disappearance, cross-source identity and production refresh remain unimplemented.

## 구현 상태와 history boundary

이 표의 M1-002 검증 수치는 역사적 27개 테스트이며, current M1-003 포함 suite는 33개다. M1-002의 범위와 원래 artifact 연결은 [retrospective TASK](tasks/M1-SHARED-FOUNDATION-002.md)에 기록한다. 아래 항목은 모두 구현·검증된 history boundary이며 미결정 gate가 아니다.

| ID | 상태 | 필요한 승인 | 막히는 사항 |
| --- | --- | --- | --- |
| DB-O1 | IMPLEMENTED / VERIFIED | 새 request_id의 동일 trip/Place는 기존 basket result를 반환하고 version/event를 바꾸지 않음 | M1-002의 27개 테스트와 migration 검증 완료; current suite는 M1-003 포함 33개 |
| DB-O2 | IMPLEMENTED / VERIFIED | 성공 result 24시간 보관, post-lock 실제 clock, 만료 key conditional delete | named JUnit integration tests |
| DB-O3 | IMPLEMENTED / VERIFIED | 실패 result는 저장하지 않고 mutation/version/event를 rollback | rollback and failure tests |
| DB-O4 | IMPLEMENTED / VERIFIED | 현재 migration의 FK delete action과 성공 result snapshot 보존 | migration 및 integration test |
| DB-O5 | IMPLEMENTED / VERIFIED | V1 Flyway migration, UUID, BIGINT, check, index, 24시간 expiry 제약 | `V1__basket_save_foundation.sql` 및 27개 테스트 |

## V2 backend authentication schema

Additive `V2__kakao_opaque_auth.sql`은 기존 V1을 수정하지 않고 `auth_identity`, `auth_login_exchange`, `auth_session`, `auth_refresh_token`을 추가한다. `(provider,subject)`와 identity당 User, credential hash, access hash, refresh hash/현재 token의 고유성으로 provisioning·exchange 소비·rotation을 DB에서 serialize한다. raw token, email/profile, IP/device fingerprint는 없다. session revoke와 absolute expiry 뒤 history cleanup은 별도 maintenance이며 이번 slice에서 자동 삭제 job을 추가하지 않았다.

Isolated PostgreSQL runner가 V1+V2와 동시 identity/exchange/refresh, rollback, 기존 401/404/idempotency 회귀를 검증했고 generated role/schema를 삭제했다. dedicated `stog_backend_dev/stog_app`은 Flyway current version 2로 유지한다. shared/applied migration은 수정하지 않았다.

ImportJob, provider reference, media, Record, CELL, EXP, schedule proposal, queue, 전체 entity는 이 schema 조각에 없다. 적용된 migration 수정·DB init/clean/drop도 승인되지 않았다.

## Additive V7 native password authentication

[NATIVE-AUTH-UI-001](tasks/NATIVE-AUTH-UI-001.md)은 applied V1-V6를 수정하지
않고 `V7__native_password_auth.sql`을 추가한다.

- `stog_user.display_name`은 기존 Kakao User 보존을 위해 nullable이고,
  native signup은 service에서 normalized 1-40 code point non-null 값을
  요구한다.
- `auth_password_credential`은 `user_id` PK/FK, canonical unique
  `username`, versioned PBKDF2 encoded `password_hash`, created/changed
  timestamp만 저장한다. Raw password, email, phone, recovery answer와
  provider identity를 저장하지 않는다.
- `auth_password_attempt`은 known/unknown canonical username 모두에 같은
  5-failure/15-minute window와 15-minute block을 적용하며 stale cleanup용
  timestamp index를 가진다. IP/device fingerprint는 없다.
- Signup의 User, credential, initial `auth_session`과 current
  `auth_refresh_token`은 한 transaction이다. Username unique collision,
  session insert failure와 다른 DB failure는 partial User/credential을
  남기지 않는다.
- Existing `auth_session`/`auth_refresh_token`, Kakao `auth_identity`와
  domain FK는 변경하지 않는다. Account merge/link schema는 없다.

Native auth가 V7을 소유하므로 deferred R3c trigram index는 future V8이다.
Historical candidate-V7 failure evidence는 변경하지 않는다.

V7은 generated non-superuser full PostgreSQL gate와 retained
`stog_backend_dev/stog_app`에서 `LOCAL VERIFIED`다. Retained 적용 전
postgres-owned mode-600 private custom-format backup을 새로 만들고
`pg_restore --list`를 통과시켰다. 적용 전후 preserved Kakao User,
`auth_identity`, preexisting `auth_session` fingerprint와 모든 기존 domain
count가 일치했다. 후속 native QA User/credential/trip/session은 명시적
runtime acceptance가 만든 신규 row이며 기존 row 수정·삭제가 아니다.
