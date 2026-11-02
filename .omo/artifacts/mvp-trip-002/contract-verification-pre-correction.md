# RESULT

**BLOCKED - 생산자 정정 전 구현 배정 불가.** 재검토 대상 네 기준인 cover ledger/고유성, invitation replay 비밀·키 수명, 삭제 뒤 R1 cursor, join parse-before-lookup은 실제 정본에서 모두 해소됐다. 그러나 invitation rate 동시성, 실제 surface 수용 범위, verifier principal 서술, file reconciliation 상한은 이전 차단 상태 그대로여서 계약 전체는 아직 coherent하지 않다.

# EVIDENCE

- **PASS - mapping/unique semantics:** `MVP-TRIP-002` §4, §6.1, §6.3, §7 V5는 execution PK를 `(principal_user_id,trip_id,command_type,request_id)`로 고정한다. `SET_TRIP_COVER_V1`과 `REMOVE_TRIP_COVER_V1`은 같은 UUID여도 별도 row이며 blob/file에는 request 기반 unique를 두지 않는다. no-cover remove는 exact `200 UNCHANGED` snapshot을 replay한다.
- **PASS - invitation replay와 key lifecycle:** §4와 §5.5는 lookup에 SHA-256만, issue ledger에 `result/expires_at` recipe만 저장한다. raw token/URI/recoverable ciphertext와 암호화 key를 두지 않으며, caller가 같은 raw token을 다시 보낸 때만 URI를 재구성한다. restart 뒤 token이 없거나 Android process death가 나면 복구하지 않고 새 명시적 reissue를 사용한다.
- **PASS - cursor deletion behavior:** §4의 `R1 My Trips cursor supersession`, §9와 `docs/API.md` R1 cursor 문단은 삭제·membership 철회로 anchor가 사라져도 signed raw sort tuple 뒤 current-visible page를 `200`으로 계속 반환한다고 일치한다. restart/expiry/tamper/mismatch만 `400 INVALID_CURSOR`이며, mutation을 직접 관찰한 Android의 first-page reload는 UI fresh-state 정책이다.
- **PASS - parse-before-lookup statuses:** §5.6은 인증 -> JSON 구조/type -> request ID/member label -> token canonical parse -> hash/idempotency/rate/DB 순서를 고정한다. malformed/missing/duplicate/null/wrong type은 `400 INVALID_REQUEST`, string이지만 noncanonical Base64URL/length인 token과 canonical unknown/expired/revoked/deleted token은 동일 `404 INVITATION_NOT_FOUND_OR_NOT_USABLE`이며 canonical parse 전 hash/rate/lookup을 금지한다.
- 이전 검증에서 통과한 role/state, last-ADMIN locking, 10분 expiry/reissue, join idempotency, edit/delete/leave 권한, scheduled-date conflict, version/ledger/atomic commit-after-callback, membership 상실 뒤 metadata/thumbnail/file 비노출, local-only/zero-cost R2a->R2b 분할과 cover 보존 판정은 그대로 유효하다. public link/provider/object store/account/secret을 검증했다고 주장하지 않는다.
- **BLOCK - rate 원자성:** §5.5는 token-hash당 rolling 10분 50회를 보장하면서 valid token만 `Trip -> invitation -> principal`로 잠그고 invalid token은 principal row만 잠근다. 서로 다른 principal이 같은 canonical unknown token을 동시에 제출하면 공통 serialization key가 없어 token-hash cap의 count+insert를 원자적으로 보장할 수 없다. §7 V4의 attempt table도 index만 정의하고 이 경합을 막는 lock/unique mapping을 정의하지 않는다.
- **BLOCK - acceptance와 real surface 불일치:** §10 backend RED는 ADMIN leave/self-remove, MEMBER delete, valid duplicate `ALREADY_MEMBER`, join response-loss replay를 요구하지만 `actual gate` 1-5는 ADMIN leave, MEMBER delete, MEMBER의 invitation/role/remove 같은 ADMIN-only 호출, valid duplicate join, join 응답 유실 replay를 실제 Android/API surface에서 실행한다고 고정하지 않는다. RED 존재만으로 real-surface gate를 대체할 수 없다.
- **BLOCK - principal 계약 불명확:** §10은 정확히 두 real auth user라고 선언하면서 R2b에서 A/B의 member read 뒤 별도 `nonmember` media denial을 요구한다. B를 제거해 nonmember로 전환할지 세 번째 generated principal을 쓸지 순서가 없어 fixture와 실제 surface를 단일하게 구현할 수 없다. `docs/PLAN.md`도 two-user flow로 반복한다.
- **BLOCK - reconciliation 판정 불가:** §6.4는 startup/일 1회 pass를 `bounded`라고만 하고 pass당 directory 수 또는 시간 예산, 중복 pass 직렬화, DB 조회 실패 시 무삭제 규칙을 정하지 않는다. §10 R2b RED의 `bounded serialized` 문구에도 machine-observable 상한과 실패 동작이 없다.

# REMAINING PROBLEMS

1. §5.5에 canonical unknown token도 principal 간 token-hash count+insert가 원자화되는 DB lock/key를 명시하고, replay·`ALREADY_MEMBER`·unknown/expired/revoked 등 각 결과의 quota 소비 여부를 exact 표로 고정해야 한다.
2. §10 actual gate에 ADMIN leave와 MEMBER delete 거부, MEMBER의 role/remove/invitation issue·stop 거부, valid duplicate `ALREADY_MEMBER`, join response-loss same-request replay를 실제 Android/API 호출로 추가해야 한다.
3. §10과 `docs/PLAN.md`에서 R2b nonmember를 A/B 중 제거된 사용자로 만드는 정확한 순서를 쓰거나 최소 3명의 generated normal auth user로 일치시켜야 한다.
4. §6.4에 reconciliation pass 상한, 단일 실행 serialization, DB 조회 실패 시 무삭제를 고정해야 한다.
5. code, migration, build, test, runtime, DB, device는 계약 단계에서 실행하지 않았고 `NOT_RUN`이다. public HTTPS/App Link, provider/object store/CDN, account/secret/real-user 작업도 승인·검증되지 않았다.
