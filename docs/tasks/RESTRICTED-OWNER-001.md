# RESTRICTED-OWNER-001: owner 전용 backend 시험 모드

## 1. 식별과 상태

| 항목 | 값 |
| --- | --- |
| 작업 상태 | DONE |
| 승인 상태 | 사용자 선택 item 1만 APPROVED; item 2와 external/public 작업 NOT_APPROVED |
| 구현 상태 | IMPLEMENTED |
| 검증 상태 | PostgreSQL RED/GREEN + synthetic local HTTP + actual configured-owner local boot + cleanup VERIFIED |
| 선행조건 | 기존 Kakao auth mapping/session 계약과 loopback local backend |

## 2. 목적과 범위

첫 외부 시험 전에 backend 경계만 owner 한 명으로 제한할 수 있는 명시적 시험 모드를 추가한다. `STOG_AUTH_RESTRICTED_OWNER_ENABLED=true`일 때 `STOG_AUTH_RESTRICTED_OWNER_USER_ID`의 내부 User UUID와 이미 매핑된 Kakao identity/session만 exchange, refresh, protected access를 통과한다. logout/revoke는 계속 사용할 수 있다. 모드가 꺼져 있으면 기존 자동 provisioning과 session 동작을 그대로 유지한다.

포함: `AuthService`, 중앙 properties 주석, PostgreSQL auth tests, loopback synthetic-provider HTTP proof, 사용자 확인 기존 실제 QA mapping의 일회성 read-only 단일성 확인과 same-user DPAPI pin, opt-in local launcher 소비, 관련 TASK/PLAN/API/BACKEND/QUALITY 기록과 task-owned process cleanup. 제외: Caddy/Posh-ACME 설치·다운로드, 실제 Kakao/외부 요청, public listener, router/firewall/certificate, Android, schema/migration/dependency, 시작 때 owner 자동 재선택·값 출력, durable DB/DB credential 재생성.

## 3. 계약과 seam

- HTTP auth seam과 기존 `auth_identity.user_id` / `auth_session.user_id`를 사용한다. 새 wire나 generic policy/provider abstraction은 만들지 않는다.
- enabled exchange는 provider의 기존 app-binding 검증 뒤 이미 존재하는 `(KAKAO, subject)`가 configured owner UUID에 매핑된 경우만 session을 만든다. mapped nonowner와 unregistered identity는 동일한 `401 AUTHENTICATION_REQUIRED`이며 User, identity, session, exchange-consumption을 만들지 않는다.
- enabled refresh/access는 session의 `user_id`가 configured owner와 일치해야 한다. 따라서 모드 활성화 전에 발급된 nonowner session도 통과하지 못한다.
- logout은 owner 여부와 무관하게 현재 session revoke를 계속 수행한다.
- enabled인데 owner 설정이 없거나 full canonical `8-4-4-4-12` UUID가 아니면 application context가 시작되지 않아 fail closed한다. Hex 대소문자는 모두 허용하고 Java shortened UUID 표현은 거부한다. startup exception은 configured value와 parser cause를 포함하지 않는다. disabled에서는 owner 값이 필요 없다.
- owner UUID, provider subject와 token 실제 값은 source, log, 문서와 evidence에 기록하지 않는다. local proof는 실행 중 생성한 값만 process memory/DB fixture에 사용하고 결과에는 boolean/count/status만 남긴다.

## 4. 수용 기준

1. 먼저 owner 성공, mapped nonowner 거부, unregistered 무변경, preexisting nonowner access/refresh 거부, logout 유지, enabled absent/malformed fail-closed, disabled baseline을 표현하는 PostgreSQL/config RED를 보존한다.
2. PostgreSQL test는 HTTP status와 exact auth table/User count 및 denied session의 revoke/use 상태를 확인한다. runner의 non-superuser/schema ownership 및 cleanup gate를 유지한다.
3. 구현 뒤 관련 GREEN, 변경 Java diagnostics, 최종 전체 PostgreSQL suite와 `bootJar`를 한 번 통과한다.
4. 실제 local backend + literal-loopback synthetic provider + isolated generated identity/session fixture로 owner exchange/protected 404, nonowner exchange/access/refresh 401, nonowner logout 204를 관찰한다. normal filter를 사용하며 runtime principal bypass는 없다.
5. task-owned process/row를 제거하고 listener 부재, aggregate metadata 원복, 기존 durable DB/DPAPI 보존을 확인한다.
6. `%LOCALAPPDATA%\STOG\local-backend\restricted-owner-user-id.clixml`에 unambiguous existing mapping을 same-user DPAPI `SecureString`으로 한 번 고정한다. `Start-LocalBackend.ps1 -RestrictedOwner`는 이 값만 process-local로 소비·복원하며 missing/invalid 설정은 Gradle 전에 fail closed하고 normal mode로 fallback하지 않는다. 실제 configured boot는 restricted enabled, pinned owner/current mapping 일치 boolean, exact loopback listener와 anonymous protected 401만 기록한다.

## 5. 증거와 제한

증거 위치는 `.omo/artifacts/restricted-owner-001/`이다.

- RED: 60개 중 5개가 예상대로 실패했다. 실패는 nonowner/unregistered/preexisting-session denial 3개와 enabled absent/malformed startup 2개였고 fixture role/schema cleanup은 성공했다. `red-proof.json`
- focused GREEN: 새 PostgreSQL/config 8개가 failure/error/skip 0으로 통과했고 fixture cleanup이 성공했다.
- 변경 Java LSP: 4개 파일 error/warning 0.
- 원래 최종 PostgreSQL suite + `bootJar`: 60개, failure/error/skip 0, non-superuser/own-schema 확인, generated role/schema cleanup과 preexisting metadata 보존. `final-runner-result.json`
- config review fix: 최초 assertion API 선택 오류는 compile 단계 setup failure로 별도 기록했고 behavioral RED는 config 4개 중 2개 실패(shortened form 허용, malformed sentinel nested-cause 노출)였다. Canonical 비교와 cause-free generic error 뒤 config 5/5, 최종 PostgreSQL 62/62 + `bootJar`, Java LSP 2개 error/warning 0, fixture cleanup/metadata 보존이 통과했다. 대문자 canonical은 허용한다. `review-fix/red-proof.json`, `review-fix/final-runner-result.json`, `review-fix/final-verification.json`, `final-verification.json`
- local HTTP: synthetic owner exchange 200, protected missing-trip 404, refresh 200, logout 204/이후 401; mapped/preexisting nonowner exchange/access/refresh 401, logout 204. normal access filter를 사용했고 runtime principal bypass는 없다. `local-http-proof.json`
- cleanup: task-owned row 0, aggregate auth count 원복, backend/provider process와 8080 listener 부재, durable development DB와 DPAPI credential 보존. `cleanup.json`
- 2026-10-01 owner configuration: 사용자 확인 restricted owner는 prior real Kakao login/logout/relogin QA와 **SAME account**다. Dedicated `stog_backend_dev/stog_app`의 read-only query가 Kakao identity 1개, 유효 identity-to-user mapping 1개, distinct mapped user 1개와 기존 session 존재를 확인한 경우에만 내부 User UUID를 same-user DPAPI operator 설정으로 export했다. 불명확하면 쓰기 전에 중단하며 launcher는 DB에서 owner를 재선택하지 않는다.
- launcher TDD: switch 선언 뒤 missing 설정이 fake Gradle까지 도달한 실제 RED를 남겼다. 구현 뒤 missing/invalid fail-closed, invalid 파일 hash 보존, valid process-local 환경 공급과 inherited 환경 복원 3개가 통과했다. 설정 값은 출력하지 않았다.
- actual configured boot: `restrictedOwnerEnabled=true`, `pinnedOwnerMatchesCurrentMapping=true`, `singleExistingKakaoIdentityMapping=true`, listener exact `127.0.0.1:8080`, anonymous protected request `401`, inherited restricted 환경 복원과 최종 listener 0을 확인했다. verifier가 의도적 Java 종료에 따른 Gradle stderr를 오류로 분류한 것은 모든 runtime assertion 뒤 발생했으며, 재현 verifier를 수정하고 두 번째 backend start 없이 cleanup을 확인했다. `.omo/artifacts/restricted-owner-001/owner-configuration/configured-startup.json`, `cleanup.json`.
- protected owner setting과 기존 DB credential/development DB는 의도적 deliverable로 보존한다. artifact의 UUID 형식/민감 문자열 scan은 0이다. 실제 owner bearer 인증 요청은 `NOT_RUN`이며 boot와 anonymous 401만 검증했다.

Item 2, Caddy/Posh-ACME, real Kakao/external request, public listener/router/firewall/certificate, Android는 계속 `NOT_APPROVED / NOT_RUN`이다. schema/migration/dependency/wire 변화는 없다. Public execution도 `NOT_RUN`이다.
