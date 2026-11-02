# STOG 프로젝트 기준 체크리스트와 작업 원장

갱신일: 2026-11-02. 이 문서는 프로젝트 전체의 현재 상태, 완료·미완료 체크리스트, blocker, 다음 작업 슬롯을 관리하는 단일 원장이다. 과거 결정, 완료 슬롯, 작업 이력은 [PLAN_HISTORY.md](PLAN_HISTORY.md)에 있다. v1.4와 최신 사용자 결정을 기준으로 하며, 실제 증거가 없는 완료 표시는 하지 않는다.

현재 `NATIVE-AUTH-UI-001` monochrome amendment 및 Pretendard typography follow-up은 build/install 및 signed-out auth 관찰까지 수행했다. signed-in 화면은 미검증이다. 버전, 라이선스와 상세 증거는 해당 TASK를 따른다.

## 상태 표기

| 표기 | 의미 |
| --- | --- |
| `[x]` | 연결된 artifact, 소스, 테스트 또는 관찰 증거로 완료를 확인함 |
| `[ ]` | 미완료, 미검증, 차단 또는 범위 밖 |
| `CONFIRMED` | 요구 또는 결정이 확정됨 |
| `PROPOSED` | 제안이며 승인 전임 |
| `UNDECIDED` | 중요한 선택이 남음 |
| `IMPLEMENTED` | 파일 또는 동작이 존재함. 실행 검증과 다름 |
| `VERIFIED` | 명시된 실행·관찰 증거가 있음 |
| `NOT_RUN` | 실행 증거가 없음 |

`IMPLEMENTED`와 `VERIFIED`, `CONFIRMED`와 `PROPOSED`를 서로 바꾸어 쓰지 않는다. 계획 선택과 구현 승인도 서로 바꾸어 쓰지 않는다.

## Latest sequencing decision

2026-11-02 최신 Lint 상태: signup logo follow-up에서 FAILED였던 API 31 전용 splash theme 항목 4건을 `values-v31`, `values-night-v31`로 옮겨 수정했다. process-local JDK 17 `:app:assembleDebug :app:lintDebug`가 exit 0이다. 시작 화면 자체의 표시와 API 31 미만 실행은 NOT_RUN이다. [TASK](tasks/NATIVE-AUTH-UI-001.md)

2026-11-02 password/copy follow-up: latest explicit minimum is 8 characters.
Server/Android 8-128 boundary and concise reachable copy are IMPLEMENTED under
`NATIVE-AUTH-UI-001`; focused backend and Android boundary tests each passed 1/1
and assembleDebug succeeded. Delivery then rebuilt stale bootJar with explicit
process-local JDK 17, started the approved loopback launcher (anonymous 401),
and installed/launched the APK on existing Pixel_8 without clearing data.
Eight-character signup through the app to Home is now VERIFIED on Pixel_8 (see TASK); no further tests/lint ran.
No migration, reset, new dependency, redesign or broader product scope.

2026-11-02 scoped repair 종료: `NATIVE-AUTH-UI-001` native baseline과 기존 core-flow/layout repair 구현은 완료됐다. 최종 assembleDebug + lintDebug와 짧은 visible flow는 통과했고, 이번 pass의 Place detail/basket-save는 검색 결과 없음으로 `NOT_VERIFIED`다. [최종 evidence](../.omo/artifacts/native-auth-ui-20261102/android/ui-repair-evidence.md)를 따른다. Synthetic QA password의 local tool transcript 노출에 따른 rotation/revoke는 사용자 허가 요청 후 pending이며 실행하지 않았다. 전체 제품 완료가 아니며 Instagram은 design only, Mapbox는 조건부·미연결이다. 다음 제안은 deferred R3c 하나뿐이며 착수하지 않는다.

2026-11-02 사용자 결정: current app flow는 `아이디와 비밀번호` native signup/login을 사용하고 Kakao/외부 로그인은 제외한다. 기존 real Kakao identity/data, 적용된 migration과 local evidence는 삭제·merge하지 않는다. [NATIVE-AUTH-UI-001](tasks/NATIVE-AUTH-UI-001.md)이 현재 단일 작업 슬롯이며 backend는 additive V7, frontend는 real Home + My Trips two-destination shell을 각각 소유한다. 이전 `MVP-TRIP-005`는 history/contract/DB prerequisite/behavioral RED를 보존한 채 future V8로 deferred다. Public listener, email/SMS, account merge와 auth bypass는 승인되지 않았다.

2026-11-01 당시 결정 (history; 현재 슬롯은 위의 2026-11-02 결정을 따른다): Kakao, Naver와 같은 외부 로그인 기능의 확장과 최종 제품 연동은 제품 최종 단계로 미룬다. 기존 Kakao 구현과 local verification은 당시 기준선으로 보존하며, 이 결정만으로 제거하거나 비활성화하지 않는다. 최종 단계 전에는 새 provider를 추가하거나 외부 로그인 범위를 확장하지 않는다. 당시 제품 작업 슬롯은 `MVP-TRIP-005`였으며, 결정 기록은 [EXTERNAL-LOGIN-SEQUENCING-001](tasks/EXTERNAL-LOGIN-SEQUENCING-001.md)에 둔다.

## 현재 기준과 완료 체크리스트

### M0, 현재 baseline

- [x] `ANDROID-BOOTSTRAP-001`, `IMPLEMENTED / VERIFIED`: Java/XML, 단일 app module, Material, Fragment, View Binding, Wrapper가 존재한다. [TASK](tasks/ANDROID-BOOTSTRAP-001.md), [품질 운영 기록](QUALITY_OPERATIONS.md), `.omo/artifacts/android-bootstrap/verification-summary.md`
- [x] bootstrap build와 기록된 device gate, `VERIFIED`: clean copy build와 기록된 Pixel_8 emulator 검증이 있다. API 23/37 runtime, physical device, instrumentation test는 `NOT_RUN`이다. [품질 운영 기록](QUALITY_OPERATIONS.md)
- [x] 개발 가드레일, `CONFIRMED / IMPLEMENTED`: Java/XML, 비용 0원, 공통 Place와 TripItem, server-only H3, CELL 조건, 비공개 media 규칙을 [AGENTS.md](../AGENTS.md)에 둔다.

### M1, 인증과 authenticated travel 기반

- [x] `M1-SHARED-FOUNDATION-001`, `IMPLEMENTED / VERIFIED`: 공통 Place를 여행의 TripItem basket으로 저장하는 계약과 acceptance 기준이 기록됐다. [TASK](tasks/M1-SHARED-FOUNDATION-001.md)
- [x] `M1-SHARED-FOUNDATION-002`, `IMPLEMENTED / VERIFIED, historical evidence`: authenticated principal, 여행 상태, Place 확인, basket 중복, 24시간 성공 결과, request replay, plan version, rollback, lock, 결과 envelope, commit 후 callback 경계를 backend에 구현했다. 원래 27개 suite와 artifact는 [retrospective TASK](tasks/M1-SHARED-FOUNDATION-002.md)에서 보존한다. [검증 artifact](../.omo/artifacts/m1-foundation-002/final-verification.json)
- [x] `M1-SHARED-FOUNDATION-003`, `IMPLEMENTED / VERIFIED`: current TripItem GET, current membership 비노출, ended-trip member read, distinct item 404, coherent snapshot, read-only 동작이 구현됐다. 전체 suite는 33개, failure/error/skip 0이다. [TASK](tasks/M1-SHARED-FOUNDATION-003.md), [검증 artifact](../.omo/artifacts/m1-foundation-003/final-verification.json)
- [x] Kakao Login을 첫 인증 provider로 선택하고 계정 provisioning 정책을 확정, `CONFIRMED / local implementation complete`: 첫 성공 검증 login은 내부 STOG 계정 하나를 자동 생성하고, 같은 검증된 Kakao identity의 이후 login은 같은 내부 계정으로 해석한다. email로 자동 merge/link하지 않는다. 이는 billing 승인, 운영 계정·프로젝트 provisioning 승인, credential 생성, callback·token·session 설계 승인, 서비스 연결 또는 전체 구현 승인이 아니다. 공식 기능 문서와 STOG별 비용 조건은 별도 확인 대상이다. 사용자가 보고한 `com.stog.app` 등록, Kakao Login 활성화, console key hash 갱신은 설정 보고이며 runtime 관찰 증거가 아니다. [결정 기록](../.omo/artifacts/followup-dispatch-20260925/policy.md)
- [x] `KAKAO-AUTH-CONTRACT-001`, `BACKEND IMPLEMENTED / local VERIFIED`: Java HTTP provider 검증, atomic provisioning, hash-only DB-backed opaque access/rotating refresh, replay family revoke, current-session logout, principal filter와 additive V2 schema를 구현했다. 당시 52-test PostgreSQL suite/bootJar와 dedicated local DB + loopback provider fixture surface가 통과했다. 15분/30일/90일 값은 timeout 뒤 best-judgment 위임에 따른 DEVELOPMENT 기본값이며 항목별 사용자 직접 선택으로 표시하지 않는다. [TASK](tasks/KAKAO-AUTH-CONTRACT-001.md), [evidence](../.omo/artifacts/kakao-auth-backend-001/loopback-surface-result.json)
- [x] `RESTRICTED-OWNER-001`, `IMPLEMENTED / local VERIFIED`: explicit owner-only 시험 모드는 기존 mapped owner만 exchange/refresh/access를 허용하고 nonowner/unregistered 및 preexisting nonowner session을 generic 401로 거부하며 logout은 유지한다. disabled mode는 기존 provisioning을 보존하고 enabled missing/malformed owner는 startup fail-closed다. canonical-config review까지 PostgreSQL 62개 + bootJar와 기존 generated literal-loopback local HTTP/cleanup이 통과했다. 사용자가 restricted owner 의도 계정이 prior real Kakao login/logout/relogin QA와 **SAME account**임을 확인했으며, sanitized receipt의 동일 내부 identity·단일 mapping·중복 없음 aggregate가 모두 `true`라 intended identity readiness는 unambiguous다. 사용자 확인 실제 QA 계정의 단일 mapping을 read-only 재검증한 뒤 실제 owner config를 same-user DPAPI operator 설정에 고정했고 `-RestrictedOwner` local 시작, exact `127.0.0.1:8080`, anonymous protected `401`, 환경 복원과 cleanup을 검증했다. 실제 owner bearer 인증 요청은 `NOT_RUN`; item 2/external/public은 `NOT_APPROVED / NOT_RUN`이다. [TASK](tasks/RESTRICTED-OWNER-001.md), [evidence](../.omo/artifacts/restricted-owner-001/final-verification.json), [real Kakao receipt](../.omo/artifacts/real-kakao-login-runtime/cleanup-receipt.json)
- [x] `ANDROID-KAKAO-AUTH-001`, `IMPLEMENTED / local fixture + missing-config device VERIFIED`: official `v2-user:2.25.0` Java/API-23 compile, exact callback, finite login state, platform HTTP exchange/refresh/logout, Keystore refresh storage, missing-config UI와 Pixel_8 API-36 QA를 구현·검증했다. 2026-09-29 실제 Kakao local loopback login/logout/relogin과 cleanup도 제한된 범위에서 VERIFIED다. public HTTPS와 production E2E는 `NOT_RUN`이다. [TASK](tasks/ANDROID-KAKAO-AUTH-001.md), [evidence](../.omo/artifacts/android-kakao-auth-001/)
- [x] `ANDROID-TRIPITEM-GET-001`, `IMPLEMENTED / local fixture + real backend VERIFIED`: signed-in UUID lookup UI, memory Bearer, 401 시 coalesced refresh 1회와 GET retry 1회, exact content/404/retry/auth-ended states와 stale-result invalidation을 구현했다. Pixel_8 UI에서 literal-loopback synthetic provider exchange를 거쳐 기존 local Spring backend/PostgreSQL의 generated fixture를 실제 조회했고 expiry-refresh-retry/logout/cleanup을 확인했다. 이는 real Kakao 또는 production/public E2E 증거가 아니다. [TASK](tasks/ANDROID-TRIPITEM-GET-001.md), [evidence](../.omo/artifacts/android-tripitem-get-001/final-verification.json)
- [x] `MVP-TRIP-001`, `BACKEND + ANDROID EXACT GATES + REAL LOCAL E2E VERIFIED`: authenticated trip create, membership-scoped My Trips, detail와 empty/ID-only basket read의 backend/V3는 canonical JDK 17 runner에서 전체 test, isolated PostgreSQL cleanup과 bootJar를 통과했다. 최종 Android gate는 JVM·instrumentation 24/24·Lint·assemble을 통과했다. generated isolated V1-V3 real-local runner는 normal synthetic-provider exchange/security filter, create response-loss/replay exactly-one, detail/empty·ID-only basket, recreation, Back, refresh, member/nonmember 비노출, logout/no-private-content, crash scan과 정량 cleanup을 통과했다. 원래 API/UI preimplementation RED 부재와 historical 실패는 보존한다. [정본 TASK](tasks/MVP-TRIP-001.md), [검증 artifact](../.omo/artifacts/mvp-trip-001/final-verification.json).
- [x] `MVP-TRIP-002 R2a`, `IMPLEMENTED / LOCAL VERIFIED`: two-role metadata edit, invitation, member/lifecycle 동작은 PostgreSQL 91/91+bootJar와 generated two-user real-local flow를 통과했다. native member-list scroll correction 뒤 Android JVM 16개·instrumentation 33/33·Lint·assemble이 통과했고, font 1.30 portrait/landscape에서 실제 스와이프와 90% visibility 조건으로 두 멤버의 역할·제거 컨트롤 및 정확한 dialog 16개 화면을 확인했다. [정본 TASK](tasks/MVP-TRIP-002.md), [검증 artifact](../.omo/artifacts/mvp-trip-002/r2a/final-verification.json). R2b private cover/V5도 backend 111/111+bootJar, Android 44/44와 actual native-picker real-local E2E를 통과해 R2 전체가 LOCAL VERIFIED다.
- [x] `MVP-TRIP-003 R3a`, `IMPLEMENTED / LOCAL VERIFIED`: V6와 authenticated prefix/filter/keyset search, text detail, selected-trip basket save 및 Java/XML recovery가 구현됐다. Backend PostgreSQL 116/116+bootJar, Android JVM16/instrumentation49/Lint/assemble과 generated normal-auth Pixel_8 -> Spring -> isolated V1-V6 flow가 통과했다. Native continuation behavioral RED는 bounded RecyclerView viewport로 수정했으며 page2, common detail ID, dropped-201 same-intent replay, exactly-one item/ledger/version, basket reload, Back/recreation/logout과 cleanup `0|0`을 확인했다. 17-symbol 실패는 compile/scaffold RED이고 preimplementation behavioral RED는 `NOT_VERIFIED`다. [정본 TASK](tasks/MVP-TRIP-003.md), [최종 Android/integration evidence](../.omo/artifacts/mvp-trip-003/android/final-verification.json)
- [x] `MVP-TRIP-004 R3b`, `IMPLEMENTED / LOCAL VERIFIED`: official SEMAS `15083033` snapshot `20260630`의 source-specific streaming importer를 existing V6 common Place에 구현했다. Entry-atomic <=500 batches, stable source UUID upsert, Unicode/search normalization, WGS84 six-decimal HALF_UP, branch/category-code aggregate report, invalid/duplicate rollback과 retry를 검증했다. Official 제주 25-row subset은 first/repeat `25|25|25`, authenticated HTTP exact ID를 통과했고 full PostgreSQL gate는 122/122+bootJar, cleanup `0|0`이다. Reflective ClassNotFound failure는 implementation-absence/scaffold check이며 preimplementation behavioral import RED는 `NOT_VERIFIED`다. Retained catalog는 변경하지 않았다. [정본 TASK](tasks/MVP-TRIP-004.md), [evidence](../.omo/artifacts/mvp-trip-004/handoff.md)
- [x] `NATIVE-AUTH-UI-001` native baseline, `COMPLETED / ACTUAL UI PASS`: backend review fixes focused 26/26, full 145/145+bootJar와 corrected loopback runtime, Pixel native signup/Home/create/detail/My Trips/logout/relogin persistence evidence를 보존한다. Final navigation fix는 assembleDebug만 수행했고 dark matrix는 NOT_RUN이다. [TASK](tasks/NATIVE-AUTH-UI-001.md), [review evidence](../.omo/artifacts/native-auth-ui-20261102/review-implementation.md)
- [x] `NATIVE-AUTH-UI-001` scoped core-flow/layout repair, `IMPLEMENTATION COMPLETE / BOUNDED UI PASS`: Back crash와 search guidance clipping 수정 후 assembleDebug + lintDebug PASS. Home/My Trips/trip detail/empty basket/empty cover/Back와 search-empty를 관찰했다. `cafe` 결과가 없어 Place detail/basket-save는 이번 pass `NOT_VERIFIED`; fake/import data는 추가하지 않았다. [Evidence](../.omo/artifacts/native-auth-ui-20261102/android/ui-repair-evidence.md). QA credential rotation/revoke는 사용자 허가 pending이며 전체 제품 완료를 뜻하지 않는다.
- [ ] `MVP-TRIP-005 R3c`, `DEFERRED / CONTRACT APPROVED / DB PREREQUISITE LOCAL VERIFIED / BEHAVIORAL RED VERIFIED / IMPLEMENTATION ABSENT / GREEN NOT_RUN`: exact -> prefix -> infix -> similarity(0.30) ranked search와 V2 rank cursor 계약은 고정됐다. [DB-CONNECTIVITY-SETUP-001](tasks/DB-CONNECTIVITY-SETUP-001.md)에서 retained dev backup, contract-qualified `stog_backend_dev.public` pg_trgm 1.6, exact app-role USAGE, Flyway V1-V6, loopback 401와 generated test-role temporary grant/revoke/GIN capability를 local 검증했다. Historical candidate V7/search/cursor/tests는 production tree에 없고, native auth가 V7을 소유하므로 R3c migration은 future V8이다. Full GREEN/대표 sample plan은 `NOT_RUN`이다. [정본 TASK](tasks/MVP-TRIP-005.md), [DB setup evidence](../.omo/artifacts/db-connectivity-20261102/setup-verification.md)
- [ ] real SSE transport와 구독·재연결, `NOT_IMPLEMENTED / NOT_RUN`: commit 후 callback 경계는 확인됐지만 transport와 full REST synchronization은 미구현이다.

### M2, Instagram

- [ ] Instagram link/import helper와 access, storage, retention, cost, failure handling, `NOT_STARTED / NOT_RUN`: 기존 product backlog의 미래·미승인 범위다. 인증 workstream에 섞지 않는다.

### M3, map, camera, recording, H3, CELL, EXP, Discover, MY, STOBEE

- [ ] map provider, 위치 권한, camera recording, server H3/CELL/EXP, media privacy와 후속 화면, `NOT_STARTED / NOT_RUN`: 기존 product backlog의 미래·미승인 범위다. 별도 승인과 TASK 없이는 착수하지 않는다.

### Release와 operations

- [x] Direct public access의 local 준비와 restricted-owner backend item 1, `BACKEND GATE IMPLEMENTED / local VERIFIED / external EXECUTION BLOCKED / NOT_RUN`: mobile/dynamic client를 fixed source-IP로 제한하지 않는다. Backend gate만 승인·검증됐고 Caddy/Posh-ACME, public listener/start와 network/TLS 작업은 item 2로 `NOT_APPROVED`다. [TASK](tasks/RESTRICTED-OWNER-001.md), [direct 준비 TASK](tasks/DIRECT-PUBLIC-ACCESS-001.md)
- [x] Dedicated local backend DB/startup, `IMPLEMENTED / VERIFIED`: `stog_backend_dev`/`stog_backend_app`/`stog_app`, same-user DPAPI credential, schema-pinned Flyway V1, inherited server override를 차단하는 exact `127.0.0.1:8080` launcher boundary, loopback `401` surface와 QA process cleanup을 검증했다. 기존 test metadata는 보존했다. [TASK](tasks/LOCAL-BACKEND-001.md)
- [ ] public server, deployment, callback, TLS, backup/restore, monitoring, `NOT_STARTED / NOT_RUN`: direct exposure의 interface/port, network path, DNS/TLS와 최종 public start는 미승인이다. Cloudflare Tunnel은 현재 실행에서 deferred다.
- [ ] release gate, `NOT_STARTED / NOT_RUN`: API 23/37 runtime, physical device, instrumentation, external service, production authentication, real SSE, migration recovery가 남아 있다.

## 확정 결정과 실제 blocker

현재 Android는 native signup/login과 actual Home/My Trips를 사용한다. Corrected `127.0.0.1:8080` runtime에서 실제 UI flow를 확인했으므로 base URL 미설정이나 current Kakao SDK flow로 표시하지 않는다. Baseline과 scoped repair 구현은 완료됐으며 이번 pass의 Place detail/basket-save는 NOT_VERIFIED다. Local transcript에 노출된 synthetic QA credential의 rotation/revoke는 사용자 허가 pending이고 계정 변경은 없었다. Public exposure는 계속 미승인이다.

## 사용자 결정과 routine engineer work

사용자에게 필요한 결정은 Kakao를 첫 provider로 유지한다는 선택의 범위, 0원 한도 초과 정책, identity mapping의 구체 방식, duplicate identity·보관 정책, token/session 방향과 callback 공개 범위다. beta 삭제·복구와 첫 외부 tester 범위는 timeout에 따른 `DELEGATED BEST-JUDGMENT DEFAULT`로 owner-assisted deletion preparation과 owner-only restricted external test preparation을 채택했으며, 명시적 개인 선택이나 구현으로 표시하지 않는다. 현재 개발·테스트 Kakao 앱과 STOG backend의 승인 및 데이터 운영은 본인이 단독으로 책임지며, 이는 `TripMember` 역할과 별개다. 최초 자동 provisioning과 email 자동 merge/link 금지는 확정됐다. 사용자는 SDK 호출 순서, 클래스 배치, 테스트 fixture, 오류 매핑의 모든 세부를 설계할 필요가 없다. 엔지니어는 승인된 경계 안에서 공식 문서 확인, 최소 계약 작성, failing-first 테스트, 구현 순서, 보안 저장, 회귀·빌드·실표면 검증을 맡는다.

## 현재 및 다음 작업 슬롯, 정확히 하나

### 완료된 단일 작업: NATIVE-AUTH-UI-001 native auth and scoped repair

**2026-11-02 frontend 완료: COMPLETED actual UI pass.** Corrected loopback runtime에서 one native UI flow가 signup -> Home -> create/detail read -> My Trips -> logout -> relogin persisted My Trips까지 통과했고 Pixel은 My Trips에 열린 채 남겼다. My Trips bottom navigation stack overflow를 실제 흐름에서 수정했다. Sanitized screenshots: `.omo/artifacts/native-auth-ui-20261102/android/final-home-empty.png`, `final-trip-detail.png`, `final-my-trips-relogin.png`. Prior JVM/lint/component evidence는 PASS이고 final navigation fix는 `assembleDebug`만 수행했다. Signup은 corrected-runtime explicit notice 전 시작되었고 create/detail/logout/relogin은 notice 후 수행했다. User scope reduction으로 dark matrix는 `NOT_RUN`; R3c는 다음 proposed slot으로 deferred이며 구현하지 않는다.

**현재 상태: BASELINE COMPLETED / REPAIR IMPLEMENTATION COMPLETE / BOUNDED UI PASS.** [최종 evidence](../.omo/artifacts/native-auth-ui-20261102/android/ui-repair-evidence.md)에 최종 build/lint, visible flow와 screenshots가 있다. Place detail/basket-save는 이번 pass NOT_VERIFIED이며 credential rotation/revoke는 사용자 허가 pending이다. 전체 제품, Instagram 구현, Mapbox 연결이나 R3c 착수를 포함하지 않는다.

### 단일 다음 제안 (PROPOSED / DEFERRED): MVP-TRIP-005 R3c pg_trgm ranked search

**상태: DEFERRED / CONTRACT APPROVED / DB PREREQUISITE LOCAL VERIFIED / BEHAVIORAL RED VERIFIED / IMPLEMENTATION ABSENT / GREEN NOT_RUN.** [MVP-TRIP-005](tasks/MVP-TRIP-005.md)의 ranking/cursor 계약과 기존 evidence는 바꾸지 않는다. Native auth가 V7을 소유하므로 R3c additive index는 future V8이다.

## 문서 역할

`PLAN.md`는 프로젝트 상태, completion/backlog, blocker의 단일 원장이고 `PLAN_HISTORY.md`는 과거 결정과 작업 이력을 보관한다. 각 변경의 계약·범위·acceptance·실행 증거는 현재 TASK가 기록한다. `BACKEND.md`, `DATABASE.md`, `API.md`는 각각 server rule, data rule, wire rule을 소유하며 PLAN의 전체 보고서를 복제하지 않는다. `AGENTS.md`는 시작·검증·인계 규칙을 소유한다.
