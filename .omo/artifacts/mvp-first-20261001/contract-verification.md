# R1 canonical contract verification

## RESULT

**PASS — 구현 착수 가능.** `docs/tasks/MVP-TRIP-001.md`가 R1의 정본이며 `docs/API.md`, `docs/DATABASE.md`, `docs/BACKEND.md`, `docs/ANDROID.md`가 이를 모순 없이 요약한다. 구현 시 선택해야 할 충돌 계약은 확인되지 않았다. 범위는 R1 첫 증분이며 전체 MVP가 아니다.

최신 사용자 방향은 product MVP 우선, external tooling/public installation 보류로 기록되어 있다. 따라서 Java/XML, 기존 auth/principal·사용자 데이터, 공통 Place/TripItem, 0원 정책을 유지하고 public/TLS/provider/secret/real-user 작업을 승인된 것으로 확장하지 않는다.

## EVIDENCE

- **DTO/path/status/role/nullability:** TASK §4-§5의 `POST /api/trips`, `GET /api/trips`, `GET /api/trips/{trip_id}`, `GET /api/trips/{trip_id}/trip-items?state=BASKET`와 exact JSON/status/error가 API §“승인된 R1 trip-product wire”, DATABASE §“승인된 R1 additive V3”와 일치한다. create는 `201`, 동일 normalized replay는 `200`, mismatch는 `409 REQUEST_ID_REUSED`; role은 `ADMIN|MEMBER`, version/member_count는 non-null int64; legacy `name`/`region_label`만 nullable, basket `schedule_date`/`sort_order`는 null이다.
- **Principal/non-disclosure:** TASK §5와 BACKEND §인증 principal 경계는 Bearer에서 얻은 `AuthenticatedUser`만 사용하고 body/query `user_id`를 받지 않는다. absent/nonmember detail·basket은 동일 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`; list는 타 사용자 row의 존재/개수도 노출하지 않는다.
- **Replay/retention/normalization:** TASK §4, §5.1과 DATABASE §V3는 `(principal_user_id, CREATE_TRIP_V1, request_id)` scope, NFC·trim·nonblank 1–100 code points normalized input, 24시간 DB-clock success snapshot, changed normalized input `409`, 실패 ledger 미저장으로 일치한다. winner/unique loser 동시성에서 Trip/member/ledger 전체 rollback 후 새 transaction readback을 명시한다.
- **Transaction/version/rollback:** TASK §5.1, DATABASE §Transaction·잠금 불변조건, BACKEND §명령 처리 순서는 creator ADMIN + Trip version 0 + ledger를 원자 commit한다. R1 create는 expected version/SSE가 없고, 기존 basket/TripItem의 plan-version/request-id/after-commit 규칙을 보존한다. 실패·timeout·constraint 오류는 mutation/version/event/실패 ledger를 남기지 않는다.
- **Cursor/status/time/ties:** TASK §4, §5.2, §5.4, §6은 server UTC `as_of_date`를 page 전체에 고정하고 `ONGOING -> UPCOMING -> PAST`를 사용한다. 정렬은 ongoing `end_date ASC, trip_id ASC`, upcoming `start_date ASC, trip_id ASC`, past `end_date DESC, trip_id ASC`; basket은 `created_at DESC, trip_item_id DESC`다. limit은 default 20, 1–50. cursor는 opaque max 1024 ASCII, 15분, startup AES-GCM key이며 principal/endpoint/limit/sort tuple을 bind한다. malformed/tampered/expired/mismatch는 `400 INVALID_CURSOR`; SQL에는 typed bind만 전달된다. basket continuation은 frozen plan version을 검사해 변경 시 `409 PLAN_VERSION_CONFLICT`와 current version만 반환한다.
- **Invalid-input/non-disclosure:** TASK §4, §5, §7은 unknown/duplicate/missing/null fields와 invalid UUID/date/state/limit/text를 `400 INVALID_REQUEST`로 처리하고, cursor 오류를 별도 `400 INVALID_CURSOR`로 처리한다. 오류 message에는 내부 ID·타 사용자·token·SQL/stack을 넣지 않는다.
- **Migration/baseline:** TASK §8과 DATABASE §승인된 R1 additive V3는 applied V1/V2를 수정·재실행하지 않고 nullable `trip.name`, `trip.region_label`, required indexes, dedicated `trip_create_execution`만 추가한다. 기존 rows를 backfill/rename/update하지 않으며 existing `request_execution`, common Place/TripItem, V1/V2 rows와 plan-version semantics를 보존한다.
- **Place/UI truthfulness:** `implementation-gaps.md`의 “Place is ID-only”와 `V1__basket_save_foundation.sql:22-24` 근거에 따라 R1은 Place search/add나 product-quality non-empty card를 포함하지 않는다. TASK §9는 legacy null을 `제목 없음`/`지역 미정`, ID-only Place를 `장소 정보 없음`, empty basket을 명시 상태로 표시하고 name/address/image/search 완료를 가장하지 않는다.
- **Frontend scope:** TASK §3, §9 및 ANDROID §승인된 R1 Android 계약은 기존 authenticated transport와 FragmentManager의 `TripList -> TripCreate -> TripDetail`, Java/XML/Fragment/View Binding, 하나의 feature state owner를 요구한다. draft, selected ID, stable scroll anchor, unresolved request ID를 보존하고 token/full response/View는 저장하지 않는다. ambiguous retry는 같은 request ID, normalized input 변경은 새 ID; loading/content/empty/retryable/auth-ended와 stale callback/logout cleanup을 분리한다.
- **Tests/real-surface/cleanup gate:** TASK §10-§11은 authenticated behavioral RED와 별도 anonymous 401, PostgreSQL atomicity/replay/concurrency/cursor/non-disclosure/legacy tests, Android JVM/instrumentation parsing/UI tests, 그리고 exact-state subscription·bounded await의 `RealBackendTripProductUiTest`를 요구한다. 최종 gate는 backend PostgreSQL+bootJar, Android test/lint/assemble, bounded Android→local backend→PostgreSQL runner, native recreation/Back/font/accessibility matrix, owned fixture/process/settings cleanup 및 pre-existing aggregate 보존이다. fixed sleep/polling은 금지된다.
- **Direct source reuse:** `implementation-gaps.md`는 principal을 `BasketSaveController.java:31-47`, membership non-disclosure를 `BasketSaveService.java:34-49`, V1 schema를 `V1__basket_save_foundation.sql:5-40`, Android auth/lifecycle를 `AuthApiClient.java:47-126,146-180`, `MainFragment.java:50-51,151-156` 및 deterministic test neighbors로 인용한다. 이는 R1이 기존 경계를 재사용하고 새 provider/DB/client stack을 발명하지 않음을 뒷받침한다.

## REMAINING PROBLEMS

- R1 구현·build·test·runtime·DB/device/provider/public은 모두 `NOT_RUN`; 위 PASS는 문서 계약 정합성 판정이지 구현 완료/검증 선언이 아니다. 근거: TASK §1, §11; `verification.md`.
- R2 이후의 cover/media, invite/edit/delete, 기간 단축, 동일 Place 반복, raw location/caption/media retention, provider 무료 조건과 public/TLS는 별도 정책/승인 대상이며 R1 blocker가 아니다. 근거: `roadmap.md` R2-R9 및 deferred decisions.
- R1 이후 Place display/search는 ID-only 경계를 additive contract로 확장해야 한다. 현재 R1에서 이를 제품화하거나 좌표·이름·이미지를 발명할 수 없다.
- 다음 구현 전 반드시 TASK §10의 실제 RED와 §11의 전체 gate를 수행하고, `VERIFIED` 표기는 그 결과 이후에만 가능하다.
