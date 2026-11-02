# RESULT

- `docs/tasks/MVP-TRIP-002.md`를 R2 정본으로 유지하고 단일 current 슬롯을 R2a collaboration/lifecycle로 확정했다. R2a gate 뒤 R2b private cover를 순차 실행한다.
- Cover upload/remove는 `(principal,trip,command,request_id)` ledger만 사용한다. 같은 UUID의 서로 다른 command는 충돌하지 않고 no-cover remove는 exact `200 UNCHANGED` snapshot을 replay한다. blob request-ID uniqueness는 두지 않는다.
- R1 My Trips cursor는 삭제·membership 철회로 anchor가 사라져도 signed raw sort tuple로 current-visible page를 계속 반환한다. 자신이 mutation을 관찰한 Android만 first page를 다시 읽으며 visibility generation은 추가하지 않는다.
- Invitation lookup은 token hash만 저장하고 issue replay에는 non-secret result/expiry recipe만 저장한다. raw token/URI/ciphertext는 저장하지 않으며 caller가 같은 token을 다시 보낸 경우만 URI를 재구성한다.
- Join은 malformed JSON, missing/duplicate/null/wrong type을 400으로, token string의 invalid Base64URL/length와 canonical unusable token을 같은 404로 처리한다. canonical parse 전 hash/rate/DB lookup은 없다.

# EVIDENCE

- v1.4 extraction, verified R1 TASK/cursor 계약, `docs/{PLAN,API,DATABASE,BACKEND,ANDROID}.md`와 네 차단점을 QA-by-read로 대조했다.
- V4/V5 exact PK/command allowlist, invitation response recipe와 24시간 expiry/restart 경계, cover same-UUID/no-op response, cursor continuation, validation precedence와 실제 HTTP acceptance를 TASK와 domain 요약에 일치시켰다.
- 수정은 네 reviewer blocker로 제한했다. 기존 role 권한, soft delete/no-purge, invitation rate 수치·locking과 cleanup 설계는 변경하지 않았다.

# REMAINING PROBLEMS

- 구현, V4/V5 migration 생성·적용, build, test, runtime, DB와 device 검증은 모두 `NOT_RUN`이다.
- public HTTPS/App Link, provider/object store/CDN, account/secret/real-user 작업은 승인되지 않았고 `NOT_RUN`이다.
