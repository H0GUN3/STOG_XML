# 인증 결정 패킷

상태: **Kakao 선택 및 provisioning 정책 기록 / 구현 승인 전**

사용자는 Kakao Login을 첫 인증 provider로 선택했고, 첫 성공 검증 login에서 내부 STOG 계정 하나를 자동 생성하며 같은 검증된 Kakao identity의 이후 login은 같은 계정으로 해석하도록 승인했다. email로 자동 merge/link하지 않는다. 이 기록은 해당 provisioning 정책과 provider 우선순위에 대한 결정이며 billing 승인, 운영 계정·프로젝트 provisioning 승인, credential 생성, 서비스 연결, callback·token·session 설계 승인, 로그인 전체 구현 승인이 아니다. 자격 증명, token, cookie, 개인 계정 정보는 요청하거나 기록하지 않는다.

## 현재 사실

- 보호된 backend 동작은 검증된 내부 `principal_user_id`를 받아야 하며 요청 body의 `user_id`로 대신할 수 없다.
- 실제 production authentication provider, Kakao 연결, Android login은 아직 없다.
- 내부 User의 최초 자동 provisioning과 email 자동 merge/link 금지는 확정됐다. 구체적인 identity mapping, duplicate identity, unlink, 삭제·복구는 미결정이다.
- login/callback, 외부 token 검증·갱신, JWT 또는 server session, logout은 미결정이다.
- 사용자는 `com.stog.app` package 등록, Kakao Login 활성화, console key hash 갱신을 보고했다. 이는 사용자 보고 설정이며 관찰된 runtime 검증이 아니다.
- M1-003의 positive test는 test-only principal을 사용했다. `.omo/artifacts/m1-foundation-003/final-verification.json`의 33 tests와 `bootJar`는 역사적 증거이며 이 패킷의 fresh execution 근거가 아니다.
- 사용자는 backend 개발·테스트 환경으로 local PC만 선택했다. public server, deployment, 외부 address/port, 공개 callback 또는 외부 연결은 승인되지 않았다.
- `backend/src/main/resources/application.properties`에는 loopback `server.address=127.0.0.1`만 존재한다. 명시 `server.port`, datasource/provider secret, local backend start 설정은 없다. 이 기록은 변수명·경로만 다루며 local file의 값을 노출하지 않는다.
- root `local.properties`는 `.gitignore`에 있는 ignored local developer config다. `docs/QUALITY_OPERATIONS.md`는 향후 non-secret 주소 변수명 `stog.server.baseUrl`와 automation `STOG_SERVER_BASE_URL`만 정했으며, Android source에는 이를 소비하는 base-URL 설정이 아직 없다.

## 기록된 결정

| 항목 | 상태 | 의미 |
| --- | --- | --- |
| 첫 provider | `CONFIRMED` | Kakao Login을 인증 workstream의 첫 대상으로 삼는다. |
| 운영 계정과 Kakao Developers 앱 | `UNDECIDED` | 소유 주체, 접근 권한, 실제 설정은 확인 전이다. |
| 비용과 무료 조건 | `UNDECIDED` | 실제 계정의 자격, quota, billing 상태, 한도 초과 중단 또는 지출 차단을 확인해야 한다. 유료 fallback은 없다. |
| Java/XML 및 `minSdk 23` 경로 | `UNDECIDED` | 프로젝트 값은 알려져 있지만 Kakao SDK·버전 조합과 지원 통합 경로는 별도 확인해야 한다. |
| 최초 provisioning과 동일 identity 재로그인 | `CONFIRMED` | 첫 성공 검증 Kakao login은 내부 STOG 계정 하나를 자동 생성하고, 같은 검증된 Kakao identity의 이후 login은 같은 계정으로 해석한다. |
| email 기반 자동 linking·병합 | `CONFIRMED` | email을 기준으로 자동 merge/link하지 않는다. |
| 구체적 identity mapping과 계정 lifecycle | `UNDECIDED` | 검증된 `(issuer, subject)`를 내부 User에 매핑하는 세부, duplicate identity, unlink, 삭제·복구가 남아 있다. |
| token/session과 logout | `UNDECIDED` | token 검증·갱신, server 발급·폐기 또는 session, 수명과 logout 범위가 남아 있다. |
| callback과 Android 흐름 | `UNDECIDED` | redirect, PKCE, callback 책임, secure storage와 lifecycle이 남아 있다. |
| backend 개발·테스트 위치 | `CONFIRMED` | 사용자의 local PC만 사용한다. public server/deployment/external authorization은 승인되지 않았다. |
| server/API 설정 관리와 secret 위치 | `CONFIRMED` | non-secret server/API 설정은 중앙에서 관리하고 설명 주석·문서로 의미를 남긴다. secret은 ignored local developer config에만 둔다. |
| local loopback 주소·port·Android 전달 | `UNDECIDED` | backend는 `127.0.0.1` address만 명시한다. port, scheme, base path와 Android의 소비 경로는 아직 없다. |

## 순차 후속 workstream

안전한 다음 구현 경계 전에는 local PC용 non-secret loopback scheme/port/base path와 중앙 server/API 설정의 Android 전달 방식을 계약으로 확정한다. 이 단계는 source/config 변경, server/DB 실행, migration, 외부 연결을 포함하지 않는다. 확정 뒤 별도 승인 TASK가 중앙 설정 소비를 구현할 수 있으며, secret은 계속 ignored local developer config에만 둔다.

1. **계정·비용·호환성 확인:** 실제 Kakao 계정의 이용 조건과 0원 통제를 확인하고, Java/XML 및 `minSdk 23`의 공식 지원 경로와 redirect 조건을 기록한다. 확인 전에는 연결하지 않는다.
2. **identity 계약:** 확정된 최초 자동 provisioning과 email 자동 merge/link 금지를 구현 가능한 `(issuer, subject)` 매핑, duplicate identity, unlink·삭제·보관의 성공·실패 동작으로 구체화한다.
3. **상태 계약:** 외부 token 검증·갱신, 내부 principal 발급, access token 또는 server session, 폐기와 logout을 정한다.
4. **backend 통합:** 승인된 계약에 한해 callback 또는 exchange, principal 생성, security scheme와 오류를 구현한다. 기존 fail-closed principal, membership, 401/404 non-disclosure를 보존한다.
5. **Android 통합:** Java/XML login, callback, secure storage, lifecycle, network, refresh·logout을 구현하고 최소 failing-first 증거 뒤 관련 테스트를 통과시킨다.
6. **회귀와 실제 표면 QA:** 기존 27개와 33개 backend 증거를 보존하고, Android build·lint와 실제 Kakao 계정, redirect, backend, device의 end-to-end를 확인한 뒤 정리한다.

각 큰 session의 끝에서 관련 전체 test/build/device 또는 실제 provider 검증을 한 번 묶어 실행한다. 작은 항목마다 전체 gate를 반복하지 않는다. 문서 작업인 현재 기록에는 runtime 실행이 없으며 `NOT_RUN`이다.

## 사용자 결정과 engineer 작업

사용자는 Kakao 우선 선택 외에 운영 소유 주체, 0원 한도 초과 정책, 구체적 identity mapping, duplicate identity·삭제·metadata 보관, token/session 방향과 callback 공개 범위를 정한다. 최초 자동 provisioning과 email 자동 merge/link 금지는 이미 확정됐다. 사용자는 SDK 호출 순서, 클래스 배치, fixture, 오류 매핑을 설계할 필요가 없다. 엔지니어는 승인된 경계 안에서 공식 문서 확인, 계약 작성, 최소 RED, 구현, 보안 저장, 회귀·build·실제 표면 검증을 담당한다.

## 출처와 QA 상태

- `docs/PLAN.md`: Kakao 선택과 단일 현재 슬롯, 세션별 선행조건·수용 기준·종료 gate를 기록한다.
- `docs/BACKEND.md`, `docs/API.md`, `docs/DATABASE.md`: 내부 principal과 기존 transaction·non-disclosure 경계를 소유한다.
- `docs/ANDROID.md`: Java/XML, lifecycle, secure storage와 network 책임의 기준이다.
- `AGENTS.md`: 추가 비용 0원, 유료 fallback 없음, credential 비기록 규칙의 기준이다.
- `provider-options-verification.md`: Kakao Android 기능 문서 주장은 출처 범위에서 확인됐지만 STOG별 비용·계정·`minSdk 23` 호환성은 확인하지 못했다고 기록한다.

QA-by-read: 이 문서와 `docs/PLAN.md`를 대조해 Kakao 선택, 최초 provisioning·email 자동 merge/link 금지, local-PC-only backend 개발·테스트, 중앙 non-secret 설정/ignored local secret 원칙을 일치시켰다. local `application.properties`, `.gitignore`, `local.properties` 경로와 Android source의 base-URL 부재를 값 노출 없이 읽어 확인했다. public server/deployment/external authorization은 승인되지 않았고, 테스트, build, server/DB, migration, 서비스, provider runtime은 실행하지 않아 `NOT_RUN`이다.
