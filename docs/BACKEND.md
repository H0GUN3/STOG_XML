# Backend 책임 경계

상태: `M1-SHARED-FOUNDATION-002` basket save, `M1-SHARED-FOUNDATION-003` current TripItem GET, 그리고 local Kakao auth backend가 구현·검증됐다. 실제 Kakao loopback runtime은 login/logout/relogin과 cleanup 범위에서 확인됐고, Android protected current TripItem GET은 별도 synthetic-provider/generated-row fixture로 existing local backend까지 확인됐다. real Kakao와 protected request의 production 결합, production HTTPS, 배포, real SSE transport와 나머지 backend 기능은 미실행 또는 미구현이다. 규범 근거와 wire는 [API.md](API.md), 논리 저장은 [DATABASE.md](DATABASE.md)가 소유한다. M1-002 retrospective와 evidence는 [TASK](tasks/M1-SHARED-FOUNDATION-002.md), M1-003 evidence는 [final-verification.json](../.omo/artifacts/m1-foundation-003/final-verification.json)이다.

## 확정 경계

- 주 server는 Spring Boot Java/Spring MVC 하나, DB는 PostgreSQL 하나다. Android는 DB에 직접 접근하지 않는다.
- 이번 동작은 **server가 인증한 principal**이 대상 여행의 현재 구성원일 때, 이미 존재하는 공통 `Place` 하나를 같은 여행의 `TripItem` 바구니 상태로 저장하는 것이다.
- 관리자와 일반 구성원은 모두 바구니를 편집할 수 있다(v1.4 §5.2). 진행중·예정 여행만 저장을 허용하고 저장 시 여행 상태와 membership을 다시 검사한다. ended trip 저장은 `409 TRIP_NOT_ELIGIBLE`이며, 유효한 성공 replay와 현재 member의 GET은 ended trip에서도 허용한다.
- 요청의 `user_id`나 저장 사용자 값은 신뢰하지 않고 인증 principal에서만 얻는다.
- 공통 `Place`를 조회해 연결할 뿐 Place를 자동 생성·수정하지 않는다. 일정/CELL/Record/EXP/Post에는 변화가 없다.
- `TripItem`, 여행 단위 `plan_version`, 성공한 `request_id` 결과는 한 transaction에서 함께 commit하거나 모두 rollback한다. 알림은 commit 뒤에만 시도하며 원본은 REST/DB다.

## 인증 principal 경계

**CONFIRMED:** 보호된 동작에 들어오기 전에 server 인증 계층이 검증된 내부 `principal_user_id`를 제공해야 한다. 서비스는 매 호출마다 그 ID로 여행 구성원 권한을 확인한다. 같은 `request_id` 재시도도 예외가 아니므로, 탈퇴·제거 뒤에는 과거 성공 응답을 공개하지 않는다.

**IMPLEMENTED (local backend):** `POST /api/auth/kakao/exchange`는 enabled/expected app ID 설정이 모두 있을 때만 Java `HttpClient`로 token-info와 user-info를 검증하고 `(KAKAO, canonical service user ID)`를 자동 provision한다. raw Kakao/STOG token은 저장하지 않고 SHA-256만 저장한다. opaque access 15분, rotating refresh inactivity 30일, session absolute 90일과 current-session logout은 timeout 뒤 위임된 DEVELOPMENT 기본값이다. refresh replay는 family를 revoke한다. access filter가 유효 hash/session으로만 `AuthenticatedUser`를 만든다. provider 설정 부재나 untrusted endpoint는 bearer 전송 전 fail-closed 503이다. normal mode는 exact official Kakao HTTPS endpoint만, explicit fixture mode는 literal loopback HTTP만 허용하고 redirect를 따르지 않는다. provider `expires_in`은 PostgreSQL finite timestamp/Java representability를 벗어나면 provisioning 전 503이다.

**IMPLEMENTED / local VERIFIED (restricted-owner test mode):** `STOG_AUTH_RESTRICTED_OWNER_ENABLED=true`이면 `STOG_AUTH_RESTRICTED_OWNER_USER_ID`의 기존 내부 User UUID와 이미 매핑된 identity/session만 exchange, refresh와 protected access를 통과한다. mapped nonowner와 unregistered identity는 generic 401이고 User/identity/session/exchange-consumption을 commit하지 않는다. preexisting nonowner session도 access/refresh에서 401이며 logout/revoke는 204로 계속 사용할 수 있다. enabled인데 owner 값이 없거나 full canonical `8-4-4-4-12` UUID가 아니면 startup이 실패한다. Canonical hex는 대소문자를 모두 허용하며 shortened Java UUID 표현은 거부한다. 실패 예외는 configured value나 parser cause를 포함하지 않는다. disabled mode는 기존 자동 provisioning과 session 동작을 유지한다. 실제 owner 값은 source/evidence에 없고 real-owner run은 `NOT_RUN`이다.

**IMPLEMENTED / LOCAL VERIFIED / LOOPBACK RUNTIME READY (native auth):**
`POST /api/auth/signup`과 `POST /api/auth/login`은 installed Spring Security
Crypto의 versioned PBKDF2-HMAC-SHA-256 encoder를 사용한다. Signup은
User/native credential/initial opaque session을 atomic commit하고 login은
기존 User에 새 session만 만든다. Valid login은 existing refresh
rotation/replay revoke/current-session logout/Bearer filter를 그대로
사용한다. Unknown user는 process-start dummy encoded hash로 같은 PBKDF2
work를 수행하고 wrong/unknown/blocked/owner-mismatch를 모두 generic 401로
합친다. Five failures in 15 minutes create the same 15-minute block for known
and unknown canonical usernames. Blocklist는 pinned SecLists MIT
`10k-most-common.txt` commit
`913b327317496d062bcc7cace524aaad8a693be2`의 exact 10,001 rows를
gzip/Base64로 repository에 보존하고 startup에서 SHA-256/cardinality를
fail-closed 검증한다. Runtime external call은 없다.

Restricted-owner mode에서는 native signup이 fail closed 503이고 configured
owner에 already mapped된 native credential만 login할 수 있다. Current normal
local launcher는 exact loopback, dedicated DPAPI datasource와 persistent
`%LOCALAPPDATA%\STOG\local-backend\media` root를 사용한다. Startup과
initialization은 existing content를 보존하고 reparse point를 거부한 뒤
current user, SYSTEM, Administrators만 FullControl인 protected ACL을
root/descendant에 적용·검증한다. Public bind나 auth bypass는 추가하지
않는다. Generated non-superuser V1-V7 full gate는
135/135와 `bootJar`, cleanup `0|0`을 통과했다. Retained dev V7은 새 private
backup 뒤 적용됐고 기존 Kakao User/identity/session fingerprint가 그대로
유지됐다. Real loopback native signup/login/trip flow도 통과했다.

인증 실패는 여행 조회 전에 끝낸다. 인증 뒤에는 다음 두 경우를 외부에서 구분하지 않는다.

1. `trip_id`가 존재하지 않는다.
2. 여행은 존재하지만 principal이 현재 구성원이 아니다(재시도 전에 권한이 철회된 경우 포함).

두 경우의 wire 결과는 동일한 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`이다. 내부 감사 정보에도 다른 사용자가 볼 수 있는 여행 정보를 넣지 않는다. 여행이 존재하고 구성원임을 확인한 뒤에만 Place 부재와 버전 충돌을 구분한다.

## 명령 처리 순서와 동시성

아래 순서는 구현된 basket save의 실제 처리 계약이다. 추가 operation의 lock 수단과 오류 wire를 이 문서에서 발명하지 않는다.

1. JSON을 검증하고 인증 principal을 얻는다. request 입력은 [API.md](API.md)의 trip, place, expected version 규칙으로 비교한다.
2. 짧은 DB transaction을 시작하고 대상 `Trip` 행을 먼저 잠근다. 행이 없으면 비노출 404 후보로 종료한다.
3. 같은 transaction에서 `(trip_id, principal_user_id)` 구성원 행을 잠그고 확인한다. 없으면 존재하지 않는 여행과 같은 결과로 종료한다.
4. 구성원 권한 확인 뒤 post-lock 실제 DB clock으로 `(principal_user_id, command_type, request_id)` 결과를 조회한다. UNEXPIRED stored success record가 있으면 입력이 같을 때 새 여행 상태/Place/version 검사를 하지 않고 성공 결과를 재응답하며, 입력이 다르면 `409 REQUEST_ID_REUSED`다. 만료 record는 조건부 삭제하고 새 validation으로 진행한다. 실패 record는 저장하지 않는다.
5. 신규 명령이면 DB clock으로 여행 상태를 다시 읽는다. 종료 여행은 `409 TRIP_NOT_ELIGIBLE`로 거부하고 mutation하지 않는다. 다른 상태 정책은 이 slice에 추가하지 않는다.
6. 기존 `Place`를 확인하고 삭제와 경합하지 않도록 해당 identity를 보호한다. 없으면 `404 PLACE_NOT_FOUND`다.
7. 모든 NEW request는 `Trip.plan_version == expected_plan_version`을 비교한다. 다르면 basket lookup과 쓰기 없이 `409 PLAN_VERSION_CONFLICT`를 반환한다.
8. 그 뒤 같은 여행의 basket Place를 조회한다. 기존 item이면 `ALREADY_EXISTS` result-only transaction으로 기존 item/version과 결과 snapshot만 반환·저장하고 version과 callback은 바꾸지 않는다.
9. basket row가 없으면 새 `TripItem`을 만들고 `plan_version`을 정확히 1 증가시킨 뒤, item/version과 성공 응답 snapshot을 한 atomic CREATED transaction에서 저장한다.
10. 셋 중 하나라도 실패하면 transaction 전체를 rollback한다. commit 성공 뒤에만 callback을 실행한다. 오류 경로의 TripItem, plan_version, event와 실패 RequestExecution은 남지 않는다.

여행 삭제, 모든 여행 구성원 추가/제거와 권한 변경, 여행 계획 mutation은 `Trip -> TripMember` 순서로 잠가야 한다. read-only GET에는 이 mutation lock 규칙을 강제하지 않는다. 그러면 저장과 구성원 제거가 경합할 때 (a) 저장이 권한 확인 상태로 먼저 commit된 뒤 제거되거나, (b) 제거가 먼저 commit되어 저장이 거부된다. 제거된 principal의 이후 retry는 어느 경우에도 과거 결과를 받지 않는다. 서로 다른 신규 mutation이 같은 `expected_plan_version`을 보내면 여행 잠금 뒤 먼저 처리된 하나만 version을 올리고 나머지는 version conflict가 된다.

같은 scope/request_id의 동시 요청은 DB 고유성으로 하나의 실행만 commit한다. 같은 key가 서로 다른 trip lock 아래에서 경합하면 둘 다 domain write를 stage할 수 있으나, `RequestExecution` unique 위반을 받은 loser transaction은 PostgreSQL aborted transaction에서 읽기를 시도하지 않고 item/version/result를 전부 rollback한다. 그 뒤 새 transaction에서 요청 대상 Trip -> membership을 다시 검사하고 winner result를 읽는다. 입력이 같으면 replay, 다르면 `409 REQUEST_ID_REUSED`이며, membership이 철회됐으면 비노출 404다. 이는 이 unique-collision 한 건의 수습 절차이지 범용 자동 retry infrastructure가 아니다. 현재 lock limit은 5초다. lock/DB 실패는 non-secret `503 DATABASE_BUSY` 또는 `503 DATABASE_OPERATION_FAILED`로 반환하며 실패 결과는 저장하지 않는다. 무제한 대기나 자동 신규 `request_id` 발급을 가정하지 않는다.

## 현재 TripItem 조회

`GET /api/trips/{trip_id}/trip-items/{trip_item_id}`는 기존 `AuthenticatedUser` principal과 current membership을 확인한 뒤, matching Trip과 TripItem을 한 SELECT로 joins해 item과 현재 `plan_version`을 coherent SQL snapshot에서 읽는다. ended trip의 current member read는 허용한다. trip 부재 또는 nonmember는 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`로 비노출하고, authorized trip에서 item 부재·삭제·다른 trip 소속은 `404 TRIP_ITEM_NOT_FOUND`다.

이 GET은 item, trip, plan version, request ledger, event를 쓰지 않으며 full basket list, pagination, schedule mutation이 아니다. positive tests는 test-only principal과 real PostgreSQL을 사용했고 production authentication provider는 포함하지 않았다. 실제 implementation evidence는 [M1-003 final verification](../.omo/artifacts/m1-foundation-003/final-verification.json)이다.

## Commit 후 SSE

**CONFIRMED:** 최초 mutation commit 뒤 여행 구성원에게 변경 사실을 알리고, client는 권한 있는 REST 조회로 최신 상태를 회복한다. rollback, conflict, replay에는 새 알림을 만들지 않는다.

**IMPLEMENTED callback payload boundary:** `{"trip_id":"<uuid>","plan_version":13,"changed_scope":"TRIP_ITEMS"}`. 사진, Place 전문, 사용자 정보, 전체 여행은 넣지 않는다. 구독/전송 시점에도 인증과 현재 구성원 권한을 확인한다.

영구 event/outbox 저장소는 이번 범위에 추가하지 않는다. 따라서 DB commit 뒤 process 종료·연결 단절이 생기면 알림이 유실될 수 있고, 알림 전송 실패가 이미 commit된 HTTP 성공을 rollback하거나 실패로 바꾸지 않는다. 화면 진입, 재연결, version 불연속, 알림 수신 때 REST로 재조회하는 것이 복구 경로다.

## 승인된 R1 backend 책임

[MVP-TRIP-001](tasks/MVP-TRIP-001.md)은 기존 `AuthenticatedUser`와 PostgreSQL 경계를 재사용해 trip create/My Trips/detail/basket list를 구현하도록 승인한다. body/query identity는 받지 않고 current membership을 SQL에서 검사한다. create는 normalized input, creator `ADMIN`, version 0과 24시간 result ledger를 atomic commit하며 unique collision loser는 전부 rollback 후 새 transaction에서 winner를 readback한다.

Trip list status는 server UTC `as_of_date`로 계산하고 cursor에 고정한다. cursor는 startup-generated in-memory AES-256-GCM key로 보호하며 principal/route/page tuple mismatch를 SQL 전에 `400 INVALID_CURSOR`로 거부한다. typed keyset parameter만 사용한다. R2 visibility 변경은 My Trips anchor row lookup이나 generation invalidation을 추가하지 않으며 signed raw tuple 뒤에서 current membership/nondeleted filter를 적용한다. Basket first page는 version/items coherent snapshot이고 다음 page 전에 current version을 비교한다. reads는 ledger/version/event를 쓰지 않으며 create도 계획 SSE를 만들지 않는다.

Backend lane은 V3/controller/service와 controller/PostgreSQL tests만 소유한다. 정상 route RED는 test `AuthenticatedUser`로 실행해 endpoint 부재를 관찰하고 anonymous 401 RED로 대체하지 않는다. exact order/error/runner와 final gate는 TASK §10-11이 소유한다. 현재 backend/V3는 `IMPLEMENTED / EXACT POSTGRESQL GATE VERIFIED`: process JDK 17 canonical runner에서 전체 test, isolated V1-V3 PostgreSQL, `bootJar`와 fixture cleanup이 통과했다. Android/integrated real-local R1도 후속 correction 뒤 VERIFIED다.

## 승인된 R2 backend 책임

[MVP-TRIP-002](tasks/MVP-TRIP-002.md)은 R2a collaboration/lifecycle과 R2b private cover를 순차 구현한다. 두 role은 metadata/cover를 동등하게 edit하고, ADMIN만 member role/remove, invitation share stop/reissue와 trip soft delete를 수행하며 MEMBER만 leave한다. 마지막 ADMIN은 demote/remove할 수 없다. 날짜 축소가 existing scheduled item을 범위 밖으로 만들면 item을 옮기거나 지우지 않고 전체 edit을 거부한다.

모든 command는 기존 principal, `Trip -> TripMember` lock, 24시간 request ledger와 commit callback 규칙을 재사용하되 R2 ledger scope는 `(principal,trip,command,request_id)`다. join/delete, invitation reissue/join과 concurrent ADMIN 변경의 exact serialization, 현재 권한 replay와 leave/delete 예외는 TASK §4, §8을 따른다. Invitation raw token은 저장·log하지 않고 lookup hash와 non-secret issue response recipe만 보존한다. 같은 raw token을 보낸 replay만 URI를 재구성하고 restart 뒤에도 caller token이 없으면 복구하지 않는다. Join은 structural/type 400 뒤 canonical token shape를 판정하며 noncanonical string과 unusable canonical token을 같은 404로 처리한다. soft delete와 share stop은 서로 다르며 전자는 trip을 모든 surface에서 숨기되 shared rows를 보존하고 R1 create snapshot을 invalidated 처리하며, 후자는 link만 revoke한다.

R2b는 static resource 밖 private local root에 bounded decode/re-encode 결과만 저장한다. metadata/file/thumbnail마다 current membership을 검사하고 redirect/public URL을 만들지 않는다. Upload/remove는 command-scoped ledger만 execution claim으로 쓰므로 같은 UUID의 서로 다른 command가 충돌하지 않고, no-cover remove는 exact 200 snapshot을 replay한다. final file 존재 뒤 DB pointer commit, rollback orphan과 retired file reconciliation으로 DB가 없는 file만 제한적으로 정리한다. provider/CDN/object store/camera는 도입하지 않는다. R2a/V4 backend는 `IMPLEMENTED / LOCAL VERIFIED`이며 isolated PostgreSQL 91/91, bootJar와 two-user normal-auth Android real-local 흐름을 통과했다. R2b/V5와 cover runtime은 `NOT_STARTED / NOT_RUN`이다.

## 승인된 R3a backend 책임

[MVP-TRIP-003](tasks/MVP-TRIP-003.md)은 existing principal과 PostgreSQL/JdbcTemplate 패턴 위에 common Place bounded engineering search seam/text detail을 추가한다. Search/detail은 authenticated general catalog read이며 trip membership을 요구하지 않고, normalized prefix plus optional exact region/category filters, typed keyset parameters, requested limit+1과 authenticated cursor를 사용한다. Displayable catalog row만 반환하고 legacy ID-only/missing detail은 같은 `404 PLACE_NOT_FOUND`다. Reads는 DB/domain state를 쓰지 않는다.

Basket entry는 기존 `BasketSaveService`만 호출하며 별도 Place 저장 service, provider repository 또는 mutation abstraction을 만들지 않는다. R3a backend V6/controller/service/cursor는 `IMPLEMENTED / LOCAL VERIFIED`이며 generated non-superuser V1-V6 PostgreSQL 116/116, corrected 4096-character cursor bound, bootJar와 private media root/cleanup gate를 통과했다. Anonymous/nonmember/ended/conflict/read-only와 same-Place basket 회귀는 이 backend proof가 소유한다. Android real-local composition도 native continuation correction 뒤 page2/detail/lost-response replay/exactly-one basket과 cleanup을 통과했다. Production free-public-data ingest/dedup/refresh, representative image, shared map/detail, and v1.4-adopted pg_trgm partial/similarity plus PostGIS spatial behavior remain required full-R3 work outside this increment; no provider/network/extension is silently approved.

## R3b SEMAS ingestion 책임

[MVP-TRIP-004](tasks/MVP-TRIP-004.md)은 official preserved ZIP만 읽는 source-specific Java streaming parser와 explicit operator command를 추가한다. Parser는 quoted comma/newline/doubled quote와 exact 39-column UTF-8 shape를 처리하고 field/record/batch bounds를 적용한다. Each CSV entry is atomic; a temp stage primary key rejects ambiguous duplicate source IDs before one set-based V6 upsert. Rerun updates mapped fields while preserving Place UUID, and failure recovery is explicit rerun. Importer logs/reports contain aggregate counts only, not raw rows.

R3b는 `IMPLEMENTED / LOCAL VERIFIED`: synthetic parser/rollback/update/HTTP cases와 official 제주 25-row subset repeat-import를 generated isolated V1-V6에서 확인했고 backend full gate는 122/122+bootJar다. No external call, retained DB write, migration, extension or generic ETL framework was added. Read-only inspection observed pg_trgm 1.6 preinstalled and PostGIS 3.6.2 available but not installed; R3b changed neither.

## 제외와 구현 gate

- 완료된 최소 backend bootstrap/module과 M1-002 migration/DDL foundation은 보존한다. R1에 승인된 additive V3와 네 route 외 endpoint/schema/operation, provider/import, Place 생성, 일정 이동, CELL/EXP, public host/port와 deployment는 제외한다.
- backend Kakao exchange/session/provisioning과 restricted-owner 시험 모드는 local fixture로 구현·검증됐고, 실제 Kakao local loopback login/logout/relogin은 별도 runtime evidence로 확인됐다. Android current TripItem은 synthetic-provider local integration까지 확인됐지만 real Kakao 결합, production HTTPS, 실제 SSE transport/subscription과 R1 밖 travel edit/delete/invite, basket delete, schedule 및 외부 기능은 남아 있다.
- M1-002 basket save와 M1-003 current TripItem GET은 구현·검증됐다. R1 backend create/list/detail/basket list, Android exact gate와 generated isolated real-local acceptance도 `VERIFIED`다. real-local은 auth, create loss/replay, empty·ID-only basket, recreation/Back/refresh, member/nonmember, logout과 crash scan까지 통과했다. 다음 작업 슬롯은 [PLAN.md](PLAN.md#현재-및-다음-작업-슬롯-정확히-하나)에 하나만 있다.
