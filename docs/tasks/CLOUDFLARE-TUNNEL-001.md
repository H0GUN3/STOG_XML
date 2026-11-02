# CLOUDFLARE-TUNNEL-001: local backend 외부 도달 준비 (DEFERRED HISTORICAL)

## 1. 식별과 상태

| 항목 | 값 |
| --- | --- |
| 단계 / 기준 절 | M1 Kakao 인증 workstream / historical external-test preparation |
| 작업 상태 | DEFERRED / SUPERSEDED FOR CURRENT EXECUTION |
| 승인 상태 | 과거 문서 준비만 CONFIRMED / tunnel 실행 미승인 |
| 구현 상태 | HISTORICAL DOCUMENTED / runtime NOT_STARTED |
| 검증 상태 | 과거 QA-BY-READ VERIFIED / runtime NOT_RUN |
| 선행조건 | 현재 direct public access 방향과 별개인 미래 명시적 재선택 |

**현재 실행 방향 정정:** 사용자는 Cloudflare Tunnel이 아니라 router port forwarding을 통한 direct public access 준비를 요청했다. 따라서 이 TASK의 Quick/named 권장안, 명령과 실행 계약은 현재 실행에 적용하지 않으며 Cloudflare Tunnel은 선택되지 않았다. 아래 내용은 당시의 문서 준비를 보존한 역사 기록일 뿐이다. 현재 direct-access 선행조건과 승인 gate는 [PLAN](../PLAN.md)과 [QUALITY_OPERATIONS](../QUALITY_OPERATIONS.md)가 소유한다.

## 2. 목적과 현재 근거

사용자가 요청한 외부 app 시험을 위해 local backend를 Cloudflare Tunnel로 한시적으로 도달시키는 최소 운영 절차를 준비한다. 현재 backend는 `127.0.0.1`에만 bind하고 production authentication provider가 없다. 따라서 현재 외부 성공 기준은 HTTPS tunnel을 통한 기존 보호 endpoint의 `401 AUTHENTICATION_REQUIRED` 관찰뿐이며 login 성공, authenticated API 성공, callback 또는 배포 완료가 아니다.

설치 조사에서는 `cloudflared` 2026.8.2가 발견됐고 사용자·시스템 cloudflared config directory와 실행 중 process는 없었다. 이 사실은 실제 account, tunnel 또는 public URL이 생성됐다는 뜻이 아니다.

## 3. 범위

포함:

- Quick Tunnel과 remotely-managed named tunnel을 미결정 대안으로 기록한다.
- 초기 reachability에는 Quick Tunnel을 권장한다.
- loopback HTTP origin, 종료, 증거 redaction, DB와 application auth 경계를 정의한다.
- 기존 중앙 주소 이름 `stog.server.baseUrl`과 `STOG_SERVER_BASE_URL`을 재사용한다.

제외:

- server, DB, migration, tunnel, login, account 또는 public hostname 실행·생성.
- product config/code, Android base URL consumer, network client, callback, auth 또는 SSE 구현.
- shared/production DB 자동 migration, auth disable, DB/admin/debug port 공개.

추가 비용은 0원이고 유료 fallback은 없다. Quick Tunnel은 공식 문서상 무료 개발·시험 기능이다. named tunnel은 사용자가 Cloudflare account/domain과 Free 조건을 확인하기 전에는 선택하거나 시작하지 않는다.

## 4. 대안과 미결정

| ID | 상태 | 대안 / 제약 | 결정 시점 |
| --- | --- | --- | --- |
| CT-O01 | RECOMMENDED / UNSELECTED | Quick Tunnel: account/domain 불필요, 매 실행 random `trycloudflare.com` URL, SLA 없음, 200 concurrent request 제한, SSE 미지원. 최초 401 reachability에만 권장 | public start 승인 전 |
| CT-O02 | UNSELECTED | remotely-managed named tunnel: stable hostname 가능. Cloudflare account, Cloudflare에 추가된 domain, hostname, Free 조건 확인 필요 | stable callback/base URL이 실제 계약에 필요할 때 |
| CT-O03 | PENDING | 실제 public tunnel 시작 | 사용자의 별도 최종 승인 필요 |

Quick Tunnel random URL을 provider callback allowlist나 지속 Android 설정으로 취급하지 않는다. Android native social callback은 자동으로 server callback이 아니며, backend callback은 인증 계약이 별도로 선택할 때만 연다.

공식 근거:

- Quick Tunnel: https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/
- named tunnel: https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/create-remote-tunnel/
- tunnel 용어: https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/tunnel-useful-terms/
- 현재 plan 가격: https://www.cloudflare.com/plans/

## 5. 실행 전 계약

1. dedicated local application DB/schema와 least-privilege application role을 별도로 준비하고 datasource credential은 local environment에서만 공급한다. 기존 `Run-PostgresIntegration.ps1`은 일회성 test fixture runner이며 application startup이 아니다.
2. backend는 fixed local port `8080`과 기존 loopback `127.0.0.1`에서 시작해야 한다. DB migration은 해당 dedicated local application schema에만 명시적으로 수행하며 shared/production schema를 자동 대상으로 삼지 않는다.
3. local `http://127.0.0.1:8080`에서 anonymous protected request가 기존 `401 AUTHENTICATION_REQUIRED`를 반환하는지 먼저 확인한다. auth를 끄거나 test principal을 runtime에 주입하지 않는다.
4. 최종 public-start 승인을 받은 뒤에만 초기 Quick Tunnel을 다음 정확한 명령으로 시작한다.

```text
cloudflared tunnel --url http://127.0.0.1:8080
```

5. 출력된 HTTPS URL로 같은 protected request가 `401 AUTHENTICATION_REQUIRED`인지 확인한다. 이것만 현재 external success다. DB `5432`, admin/debug tool 또는 다른 local port를 tunnel 대상으로 지정하지 않는다.
6. 시험 직후 foreground `cloudflared`에 `Ctrl+C`를 보내 종료하고 `Get-Process cloudflared -ErrorAction SilentlyContinue`가 process를 반환하지 않는지 확인한다. backend와 DB 종료는 각각의 승인된 local 운영 절차를 따른다.

Named tunnel을 선택하면 dashboard published application의 origin service도 정확히 `http://127.0.0.1:8080`으로 제한한다. dashboard가 발급한 connector token/command는 secret으로 취급하고 source, 문서, shell history, log 또는 evidence에 복사하지 않는다.

## 6. 중앙 설정과 보안

- Android local developer 주소 이름: ignored root `local.properties`의 `stog.server.baseUrl`.
- 자동화 주소 이름: `STOG_SERVER_BASE_URL`.
- 현재 둘은 이름 규칙만 존재하며 Android consumer는 구현되지 않았다. random URL을 committed resource나 fallback으로 넣지 않는다.
- Tunnel은 connector를 Cloudflare에 연결할 뿐 STOG application user를 인증하지 않는다. Cloudflare Access도 `AuthenticatedUser`를 대신하지 않는다.
- 공개 origin은 backend HTTP port 하나뿐이다. DB, migration/admin interface, IDE/debug port는 계속 local-only다.

증거를 남기기 전 다음을 제거한다.

- tunnel token, certificate, cookie, Authorization header, datasource/provider credential.
- 전체 random public URL과 개인 사용자 경로. 필요한 경우 scheme과 redacted host만 남긴다.
- request/response의 개인·여행 data, stack trace, SQL 및 process environment.

증거에는 UTC 시각, 선택한 tunnel 유형, `cloudflared --version`, redacted origin/public host, local/external HTTP status와 error code, 시작·종료 관찰만 기록한다.

## 7. 수용 기준과 실제 증거

| ID | 관찰 조건 | 결과 |
| --- | --- | --- |
| CT-AC01 | Quick/named 대안, 제약, 권장안과 미결정이 분리됨 | QA-BY-READ VERIFIED |
| CT-AC02 | exact loopback invocation, DB/auth 경계, teardown과 redaction이 정의됨 | QA-BY-READ VERIFIED |
| CT-AC03 | PLAN의 current slot이 하나이고 이 TASK가 Kakao prerequisite임 | QA-BY-READ VERIFIED |
| CT-AC04 | server/DB/tunnel/account/public request 실행 없음 | runtime NOT_RUN |

변경 파일은 이 TASK, `docs/PLAN.md`, `docs/QUALITY_OPERATIONS.md`뿐이다. Build, test, server, DB, migration, tunnel, account, external request와 deployment는 모두 `NOT_RUN`이다. 문서 상대 링크, 상태어, 단일 current slot과 세 파일 scope를 QA-by-read한다.

## 8. blocker와 인계

이 역사적 tunnel 절차를 다시 사용할 경우에만 Quick/named 재선택과 별도 승인이 필요하다. 현재 direct public access 실행 blocker는 [PLAN](../PLAN.md)과 [QUALITY_OPERATIONS](../QUALITY_OPERATIONS.md)를 따른다.

Production auth와 Android network consumer가 없으므로 현재 401 이상을 성공으로 주장할 수 없다. 다음 작업은 [PLAN](../PLAN.md)의 단일 Kakao authentication-path slot 안에서만 진행한다.
