# M1-SHARED-FOUNDATION-001: 기존 Place의 TripItem 바구니 저장 계약

## 1. 식별과 상태

| 항목 | 값 |
| --- | --- |
| 단계 / 기준 절 | M1 / v1.4 §5.1~5.4, §5.7, §8~8.1, §9.1, §13, §14.8 |
| 작업 목표 | server-authenticated 여행 현재 구성원이 기존 Place 하나를 그 여행의 새 TripItem 바구니 상태로 저장하는 한 동작의 계약 정의 |
| 작업 상태 | 문서 범위 DONE |
| 승인 상태 | 계약 정의 작업 CONFIRMED; 새 wire/schema/product 정책 PROPOSED 또는 UNDECIDED |
| 구현 상태 | 문서 IMPLEMENTED; backend/DB/API/Android NOT_STARTED |
| 검증 상태 | 문서·link·scope VERIFIED; runtime/build/test NOT_RUN |
| 선행조건 | `ANDROID-BOOTSTRAP-001` DONE/VERIFIED. 제품 구현은 별도 승인 필요 |
| 담당 / 검토 | 미지정 / 미지정 |

## 2. 목적과 근거

공통 Place, 단일 TripItem, server authorization, 여행 단위 version, request idempotency, atomic mutation, commit 후 SSE라는 확정 불변조건을 구현 가능한 크기의 계약으로 좁힌다. 문서 독자는 [BACKEND](../BACKEND.md), [DATABASE](../DATABASE.md), [API](../API.md)를 통해 책임별 권위 원본을 읽고, 이 TASK에서는 범위·결정·설계 수용 기준·증거·blocker를 확인한다.

근거:

- v1.4 §5.2: 관리자와 일반 구성원 모두 여행 바구니 편집 가능.
- §4.3.3: "진행중·예정 여행을 선택하되 여행 바구니에서 진입한 경우 해당 여행을 유지한다."
- §5.4: "여행 문맥이 없으면 진행중·예정 여행을 선택한다. 여행 바구니에서 시작하면 대상 여행을 유지하되 저장 시 상태·멤버십을 서버에서 재검사한다." 기존 공통 Place 저장, commit 후 SSE, CELL/Record/EXP/Post 비영향을 함께 유지한다.
- §5.7: 여행 단위 `plan_version`, `request_id`, item/version/result transaction, commit 후 알림.
- §8~8.1: User/Trip/TripMember/Place/TripItem/RequestExecution 논리 관계, 바구니 NULL 날짜·순서, 중복 retry와 의도적 반복 분리.
- §14.8: 권한과 retry 결과를 먼저 확인하고, 동일 owner/command/request/input은 과거 성공 재응답, 다른 입력은 충돌, 오래된 expected version의 성공 retry는 재실행하지 않음.
- 최신 사용자 범위: 대상 여행의 현재 구성원, 기존 Place 하나, 문서만. 진행중만 허용하는 제한, social 인증 상세, 의도적 duplicate 정책은 승인하지 않음.

## 3. 범위

### 포함

- server-authenticated principal과 여행 membership authorization 경계.
- 기존 Place 하나 -> 같은 여행의 새 TripItem `BASKET`.
- `request_id` scope/canonical fingerprint/replay/conflict와 동시 identical request 의미.
- 여행 단위 expected/current version 비교와 성공 시 정확히 +1.
- 최소 논리 관계·필드·FK/check/unique/index 범위와 durable 성공 result.
- atomic transaction, membership 제거를 포함한 lock ordering, rollback.
- commit 후 best-effort SSE와 loss/REST recovery.
- 제안 request/response/error/status와 OpenAPI 생략 gate.
- 초기 상태·행동·DB/HTTP/event 결과가 명시된 DESIGN acceptance criteria.

### 제외

- Backend/bootstrap/module, migration/DDL, executable schema, OpenAPI, endpoint, test code 구현.
- Android 화면/network/loading/back/recreation 구현과 기존 bootstrap/source/build/environment 변경.
- social provider/callback/token/session/logout/User provisioning 구현.
- Place 생성·수정·매칭, provider/import/Instagram, public server/deployment.
- 일정 이동·정렬·삭제, 여행 생성/기간/초대, CELL/Record/media/EXP/Post.
- durable event/outbox, Redis/Kafka, 별도 service/DB, 비용 발생.

허용된 외부 호출/data 쓰기/파괴 작업은 없다. 추가 dependency/tool 설치, build/test runtime, cloud 조사도 없다.

## 4. 결정

### CONFIRMED

| ID | 결정 | 근거 |
| --- | --- | --- |
| M1-D01 | 요청 사용자는 body의 user_id가 아니라 server가 인증한 principal이다. 모든 호출과 retry에서 membership을 다시 확인한다. | v1.4 §5.1~5.2, 사용자 범위 |
| M1-D02 | 없는 trip과 비구성원/철회된 구성원은 resource 존재를 누출하지 않게 동일한 외부 결과다. | 사용자 범위 |
| M1-D03 | 현재 관리자와 일반 구성원이 진행중·예정 여행에 저장할 수 있고 server는 저장 시 여행 상태와 membership을 재검사한다. 예정 여행을 배제하지 않는다. | v1.4 §4.3.3, §5.2, §5.4 |
| M1-D04 | Place는 반드시 이미 존재하며 새 Place를 자동 생성하거나 변경하지 않는다. | §5.4, §8 |
| M1-D05 | 저장 결과는 하나의 새 TripItem 바구니 상태다. 날짜·순서는 NULL이며 일정/CELL/Record/EXP/Post에 영향이 없다. | §5.4, §8.1 |
| M1-D06 | scope가 같은 동일 request/input 성공 retry는 저장된 결과를 돌려주고 재실행/version/SSE를 만들지 않는다. version이 이후 진행돼도 같다. 다른 input은 충돌한다. | §5.7, §14.8 |
| M1-D07 | 현재 명세된 최초 생성 성공은 TripItem + plan_version 정확히 1 증가 + 성공 request result를 한 transaction으로 commit한다. 향후 명시적으로 승인되는 기존 항목 반환 duplicate 성공은 status/body, persistence와 exact replay, version, SSE 효과를 별도로 정의하며 201이나 신규 item 생성을 전역 요구하지 않는다. 실패는 모두 rollback한다. | §5.7 |
| M1-D08 | commit 뒤에만 SSE를 시도한다. 영구 event 저장소 없이 유실 가능성을 인정하고 REST 재조회로 회복한다. | §5.3, §5.7 |
| M1-D09 | 새로운 request_id의 같은 Place 저장은 새로운 사용자 의도 문제이며 retry와 합치지 않는다. 신규 item/기존 item 응답/명시 거부·uniqueness 중 승인된 정책을 적용할 수 있지만 조용히 선택·혼합하지 않는다. | §5.4, §8.1 |

### PROPOSED / UNDECIDED

| ID | 상태 | 필요한 선택 / 제안 | 막히는 범위 |
| --- | --- | --- | --- |
| M1-O01 | PROPOSED | `POST /api/trips/{trip_id}/trip-items`, UUID wire, 201/error code, SHA-256 canonical fingerprint, 최소 SSE payload | wire/OpenAPI/implementation baseline |
| M1-O02 | PROPOSED | [DATABASE.md](../DATABASE.md)의 최소 논리 필드·check·index와 `RequestExecution` 성공 snapshot | migration/transaction 구현 |
| M1-O03 | UNDECIDED | 새 request_id + 같은 trip/Place의 신규 item, 기존 item 명시 응답, 명시 거부/uniqueness 중 정책과 version/SSE | duplicate branch/status/acceptance/OpenAPI |
| M1-O04 | UNDECIDED | [API.md](../API.md#미결정-gate와-openapi-생략)의 retry window·시계/만료·key/tombstone·만료 후 retry·cleanup 경합 질문 | cleanup/key lifetime/retry guarantee |
| M1-O05 | UNDECIDED | terminal 실패를 저장/replay할지, transient timeout/5xx를 어떤 status로 반환하고 저장하지 않을지 | 완전한 failure contract |
| M1-O06 | UNDECIDED | social provider, token 발급·검증·갱신, session, logout, provisioning, OpenAPI security scheme | 실제 인증 진입점; principal 서비스 경계는 독립 정의됨 |
| M1-O07 | UNDECIDED | 단일 slice 구현 범위, 미고정 concrete version, Flyway migration content/local runtime 승인 | code/migration/test 실행; Spring MVC/PostgreSQL/Flyway 재선정 아님 |
| M1-O08 | UNDECIDED | 진행중·예정 외 여행 상태 eligibility와 HTTP code/status | 완전한 operation/OpenAPI |

## 5. 변경 경로와 영향

| 경로 | 실제 변경 | 영향 |
| --- | --- | --- |
| `docs/BACKEND.md` | principal, non-disclosure, transaction/lock, membership removal, post-commit SSE | 권한·보안·동시성 문서 계약만 |
| `docs/DATABASE.md` | 최소 논리 관계/field/FK/check/unique/index/result와 migration gate | schema 제안만; DDL/data 변화 없음 |
| `docs/API.md` | 제안 route/body/result/error/fingerprint/replay/SSE, OpenAPI 생략 사유 | API 제안만; public surface 없음 |
| `docs/PLAN.md` | bootstrap DONE 보존, M1 문서 상태·gate·다음 구현 제안 하나 | 계획 상태만 |
| `docs/tasks/M1-SHARED-FOUNDATION-001.md` | 범위·결정·acceptance·증거·blocker | 현재 TASK 원장 |

새 dependency, process, provider, executable schema, public endpoint, service는 없다. OpenAPI 파일도 없다.

## 6. 계약 요약과 실패 동작

권위 있는 상세를 중복하지 않는다.

- 인증/인가/lock/transaction/SSE: [BACKEND.md](../BACKEND.md)
- 논리 data/FK/check/unique/index/rollback: [DATABASE.md](../DATABASE.md)
- exact proposed JSON/status/fingerprint/replay/OpenAPI gate: [API.md](../API.md)

핵심 순서는 인증 -> Trip 잠금 -> membership 잠금/확인 -> 성공 request 결과 확인 -> 신규 명령의 여행 상태 재검사(진행중·예정 지원) -> Place 확인 -> duplicate policy -> expected version -> atomic writes -> commit -> SSE 시도다. membership 제거도 Trip -> TripMember 순서로 잠가 stale authorization을 막는다.

성공 결과만 durable replay 대상으로 확정되어 있다. terminal/transient 실패 저장과 retention은 미결정이며 구현자가 임의 기본값으로 채우지 않는다. client도 timeout 때 새 request_id를 자동 생성하지 않는다.

## 7. DESIGN 수용 기준

아래는 실행된 테스트가 아니라 다음 구현이 만족해야 할 결정적 설계 기준이다. `T1`, `U1`, `P1`, `I1` 등은 fixture identity이고 실제 ID type 승인을 뜻하지 않는다. 기본 초기 상태는 별도 언급이 없으면 `T1`이 진행중이고 `plan_version=12`, `U1`이 현재 MEMBER, `P1`이 기존 Place, 관련 TripItem/RequestExecution이 없음이다.

| ID | 초기 상태 / 행동 | 관찰 가능한 HTTP 결과 | 관찰 가능한 DB / event 결과 |
| --- | --- | --- | --- |
| AC-01 정상 저장 | 기본 상태. U1 principal이 request `R1`, place P1, expected 12로 1회 호출 | 제안 `201`; 새 item I1/BASKET/null/null, version 13, R1 응답 | I1 1개, Trip version 13, R1 성공 snapshot 1개가 같은 commit. commit 뒤 version 13 SSE 1회 시도 |
| AC-02 미인증 | 인증 principal 없음. 나머지 입력은 같음 | 제안 `401 AUTHENTICATION_REQUIRED` | TripItem/version 변화와 SSE 0. authenticated key가 없어 성공 result replay 없음 |
| AC-03 비구성원 | T1/P1 존재, U2는 member 아님. U2가 호출 | 제안 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE` | TripItem/version 변화와 SSE 0; 저장된 성공 결과 조회/노출 0. 실패 ledger 여부는 M1-O05 |
| AC-04 없는 trip | U2가 존재하지 않는 TX에 호출 | AC-03과 status/code/envelope가 동일하고 존재 차이를 드러내는 field 없음 | TripItem/version 변화와 SSE 0. 실패 ledger 여부는 M1-O05 |
| AC-05 없는 Place | U1/T1 현재 membership, PX 없음, expected 12/R1 | 제안 `404 PLACE_NOT_FOUND` | Place/TripItem 자동 생성 0, version 12 유지, SSE 0. 실패 RequestExecution 여부는 M1-O05 |
| AC-06 동일 replay | AC-01 commit 뒤 다른 정상 mutation으로 T1 version이 14가 됨. U1 membership 유지. U1이 R1과 원래 expected 12/payload로 재호출 | 저장했던 `201` body와 version 13을 동일하게 재응답 | I1 외 새 item 0, version 14 유지, 새 RequestExecution/SSE 0 |
| AC-07 replay 전 권한 철회 | AC-01 뒤 U1 membership 제거. U1이 R1 동일 재호출 | 제안 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`; 과거 I1/version/Place 정보 미노출 | 성공 snapshot은 변경하지 않지만 읽어 응답하지 않음; mutation/SSE 0 |
| AC-08 같은 request_id 다른 입력 | U1/T1에 R1 성공 snapshot 존재. U1이 R1의 place 또는 expected version을 바꿔 호출 | 제안 `409 REQUEST_ID_REUSED` | 기존 result/item/version 불변; SSE 0 |
| AC-09 version conflict | 기본 상태에서 신규 R2가 expected 11로 호출 | 제안 `409 PLAN_VERSION_CONFLICT`, current 12 | TripItem 0, version 12, SSE 0. 실패 RequestExecution 여부는 M1-O05 |
| AC-10 동시 identical request | 기본 상태에서 exact R3 두 호출을 barrier로 동시에 transaction 진입시킴 | 한 호출이 최초 `201`, 다른 호출은 같은 `201` body. 순서는 비결정이어도 body/item은 동일 | item/result/version increment 각각 1개/1회; post-commit SSE 시도 1회 |
| AC-11 동시 다른 mutation | 기본 상태에서 서로 다른 R4/R5, 서로 다른 Place, 모두 expected 12를 barrier로 시작 | 하나만 제안 `201`; 다른 하나는 `409 PLAN_VERSION_CONFLICT` | item/result/version increment는 승자만; version 13; SSE 1회. 승자 identity 자체는 assertion 대상 아님 |
| AC-12 membership 제거 경합 | 저장과 U1 제거가 같은 Trip lock에서 barrier 후 경합 | 저장이 먼저 linearize되면 201 후 제거, 제거가 먼저면 저장은 AC-03 결과 | 전자는 item/result/version 1회 후 membership 제거, 후자는 mutation 0. 제거 뒤 replay는 AC-07. 부분 상태/SSE 선발행 없음 |
| AC-13 transaction rollback | 기본 상태에서 I1 insert 뒤 version 또는 성공 RequestExecution write에 결정적 DB fault를 주입 | 승인될 transient error 계약에 따른 실패; 성공 응답 없음 | 성공 transaction의 item/result는 rollback, version 12, SSE 0. 별도 실패 ledger 여부는 M1-O05이며 실제 DB transaction을 mock으로 우회하지 않음 |
| AC-14 post-commit 전달 실패 | AC-01에서 commit 완료 직후 emitter가 결정적으로 실패 | HTTP `201`은 유지하며 실패로 뒤집지 않음 | item/result/version은 commit 상태. durable event row 없음. SSE 0일 수 있고 권한 있는 REST 조회가 version 13/I1 복구 |
| AC-15 event ordering | subscriber가 commit visibility를 관찰한 뒤 저장 시작; emitter signal 수신 즉시 별도 DB connection으로 조회 | 최초 응답 201 | signal보다 먼저 commit되어 조회에서 I1/version 13 관찰. rollback path에서는 signal 없음 |
| AC-16 예정 여행 정상 저장 | T2는 예정, version 4, U1 현재 MEMBER, P1 존재. 신규 R10/expected 4 호출 | AC-01과 같은 제안 `201`, 결과 version 5 | TripItem/result 각 1개, version 5, commit 후 SSE 1회 시도. 예정이라는 이유로 거부하지 않음 |
| AC-17 global key/different trip 경합 | U1이 진행중 T1/T2 모두 member. 같은 R11로 T1/P1과 T2/P2를 각 expected version에 barrier 동시 호출해 서로 다른 fingerprint가 result unique에서 경합 | winner `201`; loser는 전체 rollback 뒤 새 transaction에서 T2(또는 target) membership 재검사 후 `409 REQUEST_ID_REUSED` | winner item/result/version만 1회와 SSE 1회. loser trip item 0, version 불변, SSE 0; aborted transaction 내부 재조회 없음 |

### Duplicate 정책 때문에 아직 판정할 수 없는 기준

| ID | 초기 상태 / 행동 | 확정된 관찰 | 필요한 승인 |
| --- | --- | --- | --- |
| AC-D01 | T1에 P1 TripItem이 이미 있고 U1이 **새 R6**으로 P1 저장 | retry로 취급하지 않고 정책을 조용히 선택·혼합하지 않음 | 신규 TripItem, 기존 항목 명시 응답, 명시 거부/uniqueness 중 정책과 HTTP/version/SSE |
| AC-D02 | 같은 T1/P1/expected version, 서로 다른 새 R7/R8 동시 호출 | idempotency 동일 요청이 아님 | 승인 정책의 single winner/허용 수, version/SSE, duplicate 결과 linearization |
| AC-D03 | 기존 P1 item이 일정 상태인데 새 R9로 바구니 저장 | 승인 없이 기존 item을 이동·복사·merge하지 않음 | 기존 item 응답/신규 item/거부와 basket/schedule 정책 |

AC-D01~03은 승인 전 구현 가능한 acceptance가 아니다. 진행중·예정 외 여행 상태도 eligibility/error 승인 전 판정할 수 없으며 OpenAPI 생략의 직접 gate다.

AC-03/04/05/09의 고정 assertion은 domain mutation/version/SSE 0과 기존 성공 snapshot 불변까지다. M1-O05에서 실패 ledger 정책을 고른 뒤, 저장 정책이면 승인된 failure row/replay를, 미저장 정책이면 row 0을 조건부 acceptance로 추가한다.

### 검증 seam

- 실제 관계형 DB transaction과 constraints를 사용하는 integration test로 검증한다. repository/mock이 commit·unique·rollback을 대신하면 AC-10~17을 입증하지 못한다.
- 동시성 test는 두 transaction이 준비됐음을 barrier/latch로 관찰한 뒤 동시에 해제하고 bounded timeout으로 종료를 기다린다. fixed sleep, polling delay, 승자 ID 고정 assertion을 쓰지 않는다.
- SSE test는 listener를 trigger 전에 등록하고 after-commit signal/emitter 호출을 exact signal로 기다린다. 전달 실패는 injectable emitter로 발생시키되 실제 DB commit은 mock하지 않는다.
- membership 제거 test도 둘 다 Trip lock 요청 직전 준비된 신호를 사용한다. 허용된 두 serialization 결과 외 부분 결과를 거부한다.

## 8. 실제 증거

| 검사 | 상태 | 결과 / 근거 |
| --- | --- | --- |
| 활성 문서와 v1.4 targeted 절 검토 | VERIFIED | README, AGENTS, PLAN, BACKEND, DATABASE, API, TASK template 및 DOCX §5.2, §5.7, §8~8.1, §14.8 대조 |
| DOCX 무결성 | VERIFIED | 기존 기록 SHA-256 `682f4a124c10d627dfeda2363a52545596a3abb69e6f5991af970a9a8907e4ee`; reference 수정 없음 |
| 문서 link/anchor 검사 | VERIFIED | 변경 Markdown 5개, local link/anchor 오류 0 |
| allowlist scope/hash 검사 | VERIFIED | 허용 5개 외 authored project file hash 변화 0. 실행 중 자동 append된 `.omo/senpi-task` session/log 2개는 harness 관리 경로로 분리 확인. pre-edit snapshot은 repository 밖에 보관 |
| Backend/DB/API/OpenAPI runtime | NOT_RUN | 구현이 없고 이 TASK 범위 밖 |
| Android build/Lint/test/device | NOT_RUN | source/build를 바꾸지 않았으며 bootstrap 증거를 재실행·대체하지 않음 |
| DESIGN AC-01~17 | NOT_RUN | 다음 구현용 기준이며 실행 결과가 아님 |

Fixture/mock live 구분: 이 TASK는 문서 fixture identity만 사용했고 runtime fixture/mock/live service는 사용하지 않았다.

## 9. Blocker, 복구, 인계

### Blocker와 필요한 정확한 입력

1. 동일 Place를 새로운 사용자 의도로 재저장할 때 신규 TripItem, 기존 항목 명시 응답, 명시 거부/uniqueness 중 정책과 basket/schedule·version·SSE 결과.
2. 보장 retry window, 시작 clock/event와 expiry 포함 경계, response cleanup 뒤 key/tombstone 수명, 만료 retry 거부/새 실행, expiry-cleanup 경합 기준.
3. terminal 실패 저장/replay 여부, transient timeout/DB/5xx의 code/status와 저장 금지 여부.
4. 진행중·예정 외 여행 상태 eligibility/error와 제안 route/UUID/JSON/error/fingerprint/SSE/schema 승인.
5. 단일 slice 구현 범위, 미고정 concrete version, Flyway migration content/local runtime과 실제 인증 principal을 공급할 방식 승인. Spring MVC/PostgreSQL/Flyway 재승인은 아님.

Blocker와 무관하게 authorization 비노출, replay 전 재검사, atomic success, rollback, lock ordering, SSE loss/REST recovery, no-duplicate-default를 정의했다.

복구: 문서만 변경되어 data/media/runtime 복구는 없다. 이 TASK의 허용 문서를 되돌리면 계약 문구만 사라지며 기존 bootstrap과 reference에는 영향이 없다.

## 10. 완료와 후속

실제 authored 변경 파일은 `docs/BACKEND.md`, `docs/DATABASE.md`, `docs/API.md`, `docs/PLAN.md`, 이 TASK뿐이다. 보안/data/API 영향은 문서 계약이며 비용·dependency·service·schema runtime 변화는 0이다. OpenAPI는 duplicate/retention/failure/여행 상태/auth wire가 미결정이라 추가하지 않았다.

후속 제안은 [PLAN의 `M1-SHARED-FOUNDATION-002`](../PLAN.md#다음-구현-작업-제안---정확히-하나) 하나뿐이며, blocker 승인 전 PROPOSED/BLOCKED다.
