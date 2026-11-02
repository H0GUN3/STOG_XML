# DIRECT-PUBLIC-ACCESS-001: 안전한 local backend 설정 준비

## 1. 식별과 상태

| 항목 | 값 |
| --- | --- |
| 단계 / 기준 절 | Operations / Kakao 외부 시험 선행조건 |
| 작업 상태 | ITEM 1 SPLIT COMPLETED / ITEM 2 EXECUTION BLOCKED |
| 승인 상태 | restricted-owner backend item 1 APPROVED; tool/public/network item 2 NOT_APPROVED |
| 구현 상태 | local backend + restricted-owner gate IMPLEMENTED; external route NOT_IMPLEMENTED |
| 검증 상태 | restricted-owner PostgreSQL/local HTTP VERIFIED; public/TLS/external runtime NOT_RUN |
| 선행조건 | 사용자의 direct-access 방향과 best-judgment 위임 |

## 2. 목적과 범위

Spring Boot backend의 안전한 local 기본 address/port와 datasource·server-address 설정 이름, 재현 가능한 시작 명령을 중앙 문서에 고정한다. `backend/src/main/resources/application.properties`의 loopback binding을 유지하면서 명시적 `server.port=8080`과 설명 주석만 추가했다.

포함: local 설정, Spring Boot 환경 override 이름, ignored Android/automation backend base-URL 이름, local build/test와 정적 확인. 제외: public listener, Android network consumer, DB/service/migration 실행, router/firewall/DNS/TLS/proxy 변경, 외부 요청, credential, dependency/schema/API 추가.

## 3. 결정과 계약

- `server.address=127.0.0.1`을 유지한다. `SERVER_ADDRESS` 변경은 framework상 가능해도 public-start 승인이 아니며 이 TASK에서는 금지한다.
- local 기본 port는 `8080`; 필요할 때 process-local `SERVER_PORT` 또는 `--server.port`가 override한다. 이는 Spring Boot 4.0.3의 외부 설정 precedence를 사용한다.
- application datasource 이름은 실제 Spring Boot/project surface인 `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, 필요 시 dedicated schema용 `SPRING_DATASOURCE_HIKARI_SCHEMA`다. 값을 source나 문서에 기록하지 않는다.
- Android/local developer 중앙 이름은 `stog.server.baseUrl`, automation 이름은 `STOG_SERVER_BASE_URL`이다. consumer와 fallback은 추가하지 않았다.
- 명시 `8080`은 framework의 기존 effective default와 같아서 동작 변경이 아니다. 따라서 behavior RED는 만들지 않았고 packaging 결과 안의 값을 직접 확인했다.

## 4. 변경 경로

| 경로 | 실제 변경 |
| --- | --- |
| `backend/src/main/resources/application.properties` | loopback 설명, 명시 port `8080`, override 설명 |
| `docs/QUALITY_OPERATIONS.md` | 중앙 설정 이름, dedicated datasource를 전제로 한 local 시작 절차, external NOT_RUN 경계 |
| `docs/PLAN.md` | local 완료 상태, 단일 blocker/slot, 이력 |
| 이 TASK | 범위, acceptance, evidence, blocker |

새 dependency, service, schema, API, credential, public surface, Android source 변화는 없다.

## 5. 수용 기준과 실제 증거

| 조건 | 결과 / 증거 |
| --- | --- |
| loopback과 명시 port가 packaged config에 존재 | VERIFIED: `unzip -p backend/build/libs/stog-backend-0.0.1-SNAPSHOT.jar BOOT-INF/classes/application.properties`에서 `127.0.0.1`/`8080` 확인 |
| framework key/default와 datasource 이름이 실제 dependency에 존재 | VERIFIED: cached Spring Boot 4.0.3 configuration metadata에서 `server.address`, `server.port` default `8080`, 네 datasource property 확인 |
| environment와 command-line precedence | VERIFIED: actual cached Spring Framework `StandardEnvironment` probe에서 `SERVER_PORT=18080`이 file `8080`을, command line `19090`이 environment를 override. `.omo/artifacts/direct-access-preparation/config-precedence.txt` |
| 관련 existing tests와 package | VERIFIED: process-local JDK 17, `./gradlew.bat -p backend test --tests com.stog.backend.AuthenticationBoundaryTest --tests com.stog.backend.BasketSaveControllerTest bootJar`; exit 0, 9 tests, failure/error/skip 0, bootJar 성공 |
| static/document check | VERIFIED: local Markdown link 0 broken, current slot 1, expected loopback/port와 test XML을 `.omo/artifacts/direct-access-preparation/final-verification.json`에 기록. `.properties`/Markdown LSP는 구성되지 않아 NOT_RUN; Gradle resource processing과 정적 검사로 대체 |
| original task의 backend/DB/migration/public route | 당시 NOT_RUN: 이 TASK의 local-config gate에서는 실행 금지 범위였음 |
| 후속 local backend/auth/domain surface | VERIFIED: `LOCAL-BACKEND-001`에서 dedicated DB/schema/least-privilege credential과 exact loopback `127.0.0.1:8080`; 후속 receipt에서 real Kakao login/logout/relogin과 synthetic-provider protected GET fixture E2E 확인 |
| public HTTPS/external route | NOT_IMPLEMENTED / NOT_RUN |

첫 precedence probe는 logging classpath 누락으로 실패했고, actual cached `commons-logging` jar를 classpath에 포함해 재실행한 위 결과만 acceptance evidence로 사용했다. 이 TASK 당시 fixture DB와 integration runner는 실행하지 않았으며, 위 후속 검증은 연결된 별도 TASK/receipt의 현재 baseline이지 public evidence가 아니다.

## 6. restricted direct HTTPS 최종 준비

### 두 최소안 비교와 선택

현재 요구는 **운영 주체 본인의 mobile/dynamic network에서도 되는 owner-only 첫 외부 시험**이다. 따라서 fixed public source-IP allowlist는 해법이 아니다. 현재 Kakao exchange는 검증된 새 `(KAKAO, subject)`를 모두 자동 provision하므로 기존 app authentication만으로도 owner-only가 아니다.

| 최소안 | 만족 방식 | 비용·변경 | 판단 |
| --- | --- | --- | --- |
| 승인된 private-device overlay | 승인된 owner device만 private overlay에 가입시키고 loopback 앞의 private endpoint로 접근 | 무료 개인 tier의 외부 agent/account와 device enrollment 필요; public certificate와 TCP 80/443 불필요 | 기술적으로 가장 작고 mobile/dynamic IP에 자연스럽지만, 현재 선택된 direct-public 방향을 바꾸므로 사용자 승인 없이 채택하지 않음 |
| direct TLS + restricted-owner app gate | public 443은 mobile source 변화와 무관하게 열되, 기존 Kakao identity mapping이 승인된 내부 owner User와 일치할 때만 session/API를 허용 | backend의 작은 gate와 tests, Caddy 및 Posh-ACME 설치, public-IP certificate의 짧은 갱신 주기와 TCP 80 validation 예외 필요 | **현재 방향에 맞는 권장안** |

권장안은 두 번째 하나다. 한 사람의 짧은 외부 시험만 놓고 보면 이 certificate/proxy/renewal 설정은 private overlay보다 복잡해 정당화하기 어렵지만, 이미 선택된 direct-public 방향을 유지하려면 이것이 owner-only를 만족하는 최소 경로다. Overlay로 바꾸는 것은 최적화가 아니라 방향 변경이므로 별도 사용자 선택이다. mTLS stack, fixed source-IP 가정, Tunnel 자동 전환은 제안하지 않는다.

권장 topology는 다음 하나다.

`mobile/dynamic client -> public TCP 443 Caddy -> 127.0.0.1:8080 Spring backend -> restricted-owner identity gate`

backend와 PostgreSQL은 각각 loopback과 local 전용으로 유지하고 `8080`, `5432`, admin/debug port는 공개하지 않는다. TCP 443은 source-IP가 아니라 TLS와 restricted-owner gate로 제한한다. 이 bounded 시험에서는 비owner의 유효 Kakao login도 session을 만들지 못하므로, 공개 login endpoint에 대한 abuse/DoS 방어가 아님을 명시하고 시험 시간만 연다.

### restricted-owner gate의 실제 mapping 경계

현재 구현은 provider가 확인한 숫자 `subject`를 `auth_identity(provider, subject, user_id)`의 내부 UUID에 매핑하고, `auth_session.user_id`를 protected API principal로 사용한다. 보존된 real Kakao QA evidence는 같은 identity가 같은 내부 User로 해석됐음을 boolean-only로 확인했다. 따라서 새 provider ID 목록이나 token을 proxy에 복제하지 않고 다음 **시험 전용 app-boundary mode**가 최소다.

- ignored local operator 설정의 `STOG_AUTH_RESTRICTED_OWNER_USER_ID`에 보존된 mapping의 내부 User UUID 하나를 넣는다. 값은 source, TASK, command output 또는 evidence에 기록하지 않는다.
- restricted mode의 exchange는 검증된 subject에 이미 존재하는 `auth_identity`를 조회해 configured owner User와 정확히 일치할 때만 session을 만든다. 불일치/미등록 identity는 `401`이고 `stog_user`, `auth_identity`, session을 새로 만들지 않는다.
- refresh와 access authentication도 session의 `user_id`가 configured owner와 일치할 때만 성공시켜 기존 비owner session까지 fail closed한다. logout/revoke는 계속 허용한다.
- restricted mode가 꺼진 local baseline은 기존 자동 provisioning 정책을 유지한다. 이는 global account policy 변경이나 외부 tester rollout 승인이 아니다.

최소 product 변경은 `AuthService.java`, `application.properties`, 기존 auth test와 restricted-owner test 두 개 및 필요한 운영 문서로 완료됐다. schema/migration/API wire 변경과 새 backend dependency는 없다. gate 구현과 local regression은 public 설치·binding보다 먼저 별도 관찰 가능하게 끝났다.

### 정확한 무료 TLS tool/install 경계

기존 공식 문서 조사로 성립한 certificate 경로는 `Posh-ACME + Let's Encrypt short-lived public-IP certificate + HTTP-01`이다. TLS terminator는 **Caddy Windows amd64 공식 binary** 하나로 고정한다. Caddy는 local ignored config에서 승인된 interface의 `:443` certificate/key file을 사용하고 `127.0.0.1:8080`으로 reverse proxy하며, renewal deploy 뒤 documented `caddy reload` 경계만 수행한다. Posh-ACME는 **PowerShell Gallery의 공식 `Posh-ACME` module을 CurrentUser scope**에 설치해 issuance/renewal을 맡긴다. 둘 다 프로젝트 build dependency나 Windows service로 설치하지 않고 user-local operator tooling으로 둔다. 실행 시점의 stable release와 publisher/hash를 공식 배포면에서 기록하되 이 문서가 검증하지 않은 version/command를 발명하지 않는다. Tool 역할의 공식 근거는 [Caddy TLS](https://caddyserver.com/docs/caddyfile/directives/tls), [reverse_proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy), [reload](https://caddyserver.com/docs/command-line#caddy-reload), [Posh-ACME New-PACertificate](https://poshac.me/docs/v4/Functions/New-PACertificate/), [Let's Encrypt IP certificate 안내](https://letsencrypt.org/2025/07/01/issuing-our-first-ip-address-certificate/)다.

현재 host에는 Caddy와 Posh-ACME가 없고 설치·feature 변경은 수행하지 않았다. HTTP-01 동안 CA validator source는 owner mobile IP와 다르므로 TCP 80은 challenge responder만 대상으로 잠시 외부 허용한 뒤 즉시 닫아야 한다. `8080`은 열지 않는다. 약 6일 certificate 주기에 맞춘 renewal, certificate deploy와 Caddy reload가 bounded 시험 기간을 넘겨 필요할 때만 task를 구성한다.

### item 1 완료와 item 2 승인 경계

사용자는 이 packet 중 **item 1 restricted-owner backend gate와 local 검증만 승인**했다. 해당 결과는 [RESTRICTED-OWNER-001](RESTRICTED-OWNER-001.md)에 분리해 완료했다. 아래 1번은 완료됐고 2~4번 및 모든 external/public 작업은 item 2로 `NOT_APPROVED / NOT_RUN`이다.

1. **DONE / local VERIFIED:** restricted-owner mode와 deterministic PostgreSQL tests 및 literal-loopback local HTTP proof를 구현했다. owner UUID/subject/token은 기록하지 않았고 실제 owner run은 설정 부재로 `NOT_RUN`이다.
2. **NOT_APPROVED / NOT_RUN:** Caddy 공식 Windows amd64 binary를 user-local 경로에 두고 Posh-ACME 공식 module을 CurrentUser scope에 설치한다. machine-wide PATH/service, DNS, provider/account, router/firewall, certificate issuance와 public listener는 이 단계에서 변경하지 않는다.
3. 이후 public start는 실행 직전 operator가 **해당 interface의 inbound 사용·변경 권한**과 다음 중 하나를 비공개로 확인한 경우에만 별도 최종 승인으로 연다: `(a)` 승인된 public-address interface에 Caddy `80/443`을 직접 bind할 수 있음, 또는 `(b)` routed private LAN에서 승인된 public `80/443 -> LAN host 80/443` mapping과 복구 권한이 있음. 둘 다 성립하지 않으면 fixed source IP나 router 가능성을 추정하지 않고 중단한다.
4. 최종 승인 범위는 challenge-only TCP 80 임시 허용/즉시 철회, Caddy TCP 443, owner login과 protected GET 1회, teardown뿐이다. DNS, DB/schema 변경, 다른 tester, mTLS/overlay/Tunnel, 유료 fallback은 포함하지 않는다.

위 과거 결합 승인 문구는 item 1 승인으로 분리되어 더 이상 현재 packet이 아니다. Item 2는 별도 명시 승인 전까지 열지 않는다. 그 전에는 install/download, public binding, firewall/router, certificate issuance, provider 설정 또는 external request를 수행하지 않는다.

## 7. 실행·철회·증거 체크리스트 (local gate만 VERIFIED, external 모두 NOT_RUN)

### 실행 전

- [ ] 실제 IP/credential/token을 tracked 파일, shell history, screenshot, log 또는 evidence에 남기지 않고 ignored local operator 설정에만 둔다.
- [x] restricted-owner mode가 configured generated owner만 exchange/refresh/access 허용하고 비owner를 provision하지 않는 PostgreSQL/local HTTP regression 통과. 실제 owner run은 설정 부재로 `NOT_RUN`. [evidence](../../.omo/artifacts/restricted-owner-001/final-verification.json)
- [ ] 실행 직전 inbound 권한과 direct-public interface 또는 승인된 routed mapping 중 정확히 하나가 성립하며 rollback 권한이 있는지 비공개로 확인한다.
- [ ] 승인된 user-local Caddy와 CurrentUser Posh-ACME만 사용하고, validation 예외 -> certificate -> 필요한 경우 renewal/deploy/reload를 비공개 local 설정으로 구성한다.
- [ ] backend가 여전히 exact `127.0.0.1:8080`, DB가 local-only이며 외부 노출은 challenge 동안 TCP 80과 bounded run 동안 TLS endpoint TCP 443뿐임을 확인한다.

### bounded run

- [ ] TCP 80 validation 예외를 열고 certificate를 발급한 뒤 즉시 닫는다. application response를 TCP 80에서 제공하지 않는다.
- [ ] restricted-owner mode를 먼저 적용한 뒤 Caddy TLS endpoint만 bind한다. Routed private LAN일 때만 승인된 router mapping을 켠다.
- [ ] owner mobile/dynamic client에서 public HTTPS certificate trust, unauthenticated protected endpoint `401`, app-authenticated owner login과 protected GET 1회를 순서대로 확인한다.
- [ ] 비owner 거부는 local deterministic integration test로 증명하고, 이 bounded public run을 위해 다른 실제 Kakao identity를 수집하거나 provision하지 않는다.

### teardown

- [ ] 먼저 host firewall allow rule과 TLS public-interface bind를 제거하고 외부에서 더 이상 도달하지 않음을 관찰한다. routed private LAN을 사용한 경우에만 router TCP 443 mapping도 제거한다.
- [ ] proxy/backend를 중지하고 TCP 80 예외가 닫혔는지 확인한다. DB/schema/DPAPI credential과 기존 real identity는 유지한다.
- [ ] 자동 renewal task가 공개 listener를 다시 만들 수 있으면 disable/remove하고, certificate/account 제거 여부는 별도 비파괴 운영 결정으로 남긴다.

### 최소 evidence

UTC 시작/종료 시각, 값이 가려진 실제 interface topology와 restricted-owner mode 활성 여부, certificate issuer/profile/expiry(키 없음), local 비owner 거부 결과, owner external protected `401`/authenticated GET 결과, teardown 뒤 `80/443` 비도달만 남긴다. IP, hostname 전체, owner UUID/subject, key/token, Authorization header, provider/datasource secret, 개인 payload와 router export는 남기지 않는다.

현재 결과는 `ITEM 1 IMPLEMENTED / LOCAL VERIFIED; ITEM 2 EXECUTION BLOCKED / NOT_APPROVED / NOT_RUN`이다. Item 1은 backend source/test/config와 generated local fixture rows/process만 변경·실행했다. Certificate issuance, tool install/download, provider/console/login, router/firewall/DNS/public listener와 external request는 수행하지 않았다. 기존 cleanup receipt가 종료를 확인한 QA process/reverse/fixture 외에 남은 task-owned QA resource가 있다고 주장하지 않는다. Dedicated DB/schema, DPAPI credential과 ignored local config는 의도적으로 보존하며 다시 초기화하지 않는다. 승인된 다음 실행은 없으며 [PLAN](../PLAN.md)의 단일 slot에서 item 2를 보류한다.
