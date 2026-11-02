# KAKAO-AUTH-CONTRACT-001: Kakao 인증, STOG 세션, 로그아웃 계약

## 1. 상태와 목적

| 항목 | 상태 / 값 |
| --- | --- |
| 작업 상태 | BACKEND_SLICE_IMPLEMENTED |
| 승인 상태 | 기존 `CONFIRMED` 정책 보존; backend 수명/현재-session logout은 timeout 뒤 best-judgment 위임에 따른 DEVELOPMENT 기본값 |
| 구현 상태 | local backend exchange/opaque session/refresh/logout IMPLEMENTED |
| 검증 상태 | PostgreSQL 52 tests + bootJar + dedicated localhost fixture VERIFIED / this TASK's provider fixture scope preserved; bounded real Kakao loopback runtime is recorded separately / protected domain and public network NOT_RUN |
| 구현 선행조건 | local backend와 Android local implementation 승인 충족; real settings/provider 항목은 §10 stop gate 유지 |

이 문서는 local-PC-first 환경에서 Kakao identity를 기존 내부 `AuthenticatedUser(userId)` seam으로 바꾸는 최소 계약과 구현 기록이다. backend는 additive V2 migration과 함께 구현됐고, provider 성공 검증은 real Kakao가 아닌 명시적 loopback fixture로만 수행했다.

## 2. 결정 상태와 범위

### CONFIRMED

- 첫 인증 provider는 Kakao Login이다.
- 첫 **성공 검증** Kakao identity는 내부 `stog_user` 하나를 자동 생성한다. 같은 검증 identity는 이후에도 같은 내부 User UUID다.
- email, nickname, phone 또는 profile로 계정을 자동 link/merge하지 않는다.
- Android source는 Java/XML, `minSdk 23`이고 추가 비용은 0원이며 유료 fallback은 없다.
- backend 개발·시험 원본은 local PC다. `LOCAL-BACKEND-001`의 `stog_backend_dev` / `stog_backend_app` / `stog_app`, same-user DPAPI credential, exact loopback launcher와 real protected `401`은 검증됐지만 production 인증 성공은 아니다.
- 보호된 domain 동작은 요청의 `user_id`가 아니라 server가 만든 내부 `principal_user_id`만 사용한다.

### IMPLEMENTED: local backend 단일 경로

**Android Kakao SDK의 native login으로 받은 Kakao access token을 한 번만 STOG backend에 교환하고, backend가 Kakao의 access-token-info와 user-info endpoint에서 token 유효성, Kakao service user ID와 expected app ID를 교차 확인한 뒤 DB-backed opaque STOG access/refresh token을 발급한다.**

- Kakao SDK callback은 Android의 exact `kakao{NATIVE_APP_KEY}://oauth` activity가 소유한다. public server OAuth callback, REST authorization-code flow, OIDC/JWKS, password auth는 첫 구현에 넣지 않는다.
- backend는 Kakao token을 domain credential로 받지 않고, raw Kakao token을 저장하지 않는다. 같은 Kakao token의 교환 fingerprint는 provider가 알려 준 token 만료까지만 보존해 재교환을 거부한다.
- STOG credential은 `Authorization: Bearer`의 random opaque access token과 매회 회전하는 random opaque refresh token이다. Spring `HttpSession`, cookie와 JWT를 사용하지 않으며 현재 `SessionCreationPolicy.STATELESS`를 유지한다.
- access 15분, refresh inactivity 30일, session absolute 90일, 현재 session만 logout은 사용자의 timeout 뒤 best-judgment 계속 진행 지시에 따라 local DEVELOPMENT 기본값으로 채택했다. 사용자가 항목별로 직접 선택한 값이라고 주장하지 않는다.

이 권고는 v1.4의 일반적인 "Spring Security + JWT" 제안보다 현재 한 대의 Spring/PostgreSQL server에 작다. JWT도 즉시 logout·강제 revoke·refresh replay 차단을 하려면 server-side state가 필요하다. opaque token은 이미 필요한 DB row 하나를 조회하는 대신 key rotation, claim/version drift와 denylist를 생략하고 즉시 폐기한다. 규모상 측정된 병목이 생기기 전에는 JWT로 바꾸지 않는다.

### 제외

다른 provider, account merge/link UI, password/admin auth, Kakao profile/email 수집, Kakao API의 후속 사용, account deletion UI, unlink webhook, all-device logout, public callback/deployment, full Android UI, travel/SSE 기능은 포함하지 않는다. exchange 성공 뒤 자동 실행하는 `Kakao SDK logout()`은 **이 앱에 발급된 Kakao access/refresh token 무효화와 SDK local token 제거**만 뜻한다. **Kakao Account global logout**과 **Kakao unlink**는 STOG logout이나 이 cleanup에 포함하지 않으며 자동 호출하지 않는다.

## 3. 인증 seam과 처리 순서

### 3.1 Android -> STOG exchange

1. Android는 Kakao SDK `v2-user`의 login을 사용한다. Kakao Talk login을 먼저 시도하고 SDK가 사용할 수 없다고 판정한 경우 Kakao Account login으로 전환한다. 사용자가 취소한 경우 fallback으로 강제하지 않고 signed-out/cancelled 상태로 끝낸다.
2. SDK가 반환한 Kakao access token은 memory에서 즉시 `POST /api/auth/kakao/exchange` 한 번에만 사용한다. saved state, ordinary preferences/DB, log, screenshot 또는 evidence에 넣지 않는다.
3. backend는 HTTPS가 아니면 외부 표면에서 요청을 받지 않는다. local emulator/PC 주소 전달은 별도 network TASK가 소유한다.
4. backend는 `GET https://kapi.kakao.com/v1/user/access_token_info`를 해당 bearer token으로 호출한다. HTTP 200이고 `expires_in > 0`, `app_id == configured expected Kakao app ID`, `id`가 canonical positive decimal일 때만 다음 검증으로 간다.
5. 같은 token으로 `GET https://kapi.kakao.com/v2/user/me`를 호출한다. HTTP 200이고 service user `id`가 token-info의 `id`와 정확히 같아야 identity proof가 유효하다. 이는 Kakao의 service-login 절차와 incomplete-signup 방지 요구를 충족한다. 응답의 email/profile 등 다른 field는 비교·저장하지 않는다.
6. 내부 identity key는 `provider = KAKAO`, `subject = verified id`의 canonical decimal 문자열이다.
7. raw token의 SHA-256 fingerprint를 계산한다. §7 transaction에서 같은 `(provider, credential_hash)`가 없을 때만 소비한다. 동일 token의 동시·후속 교환은 generic 401이며 새 User/session을 만들지 않는다.
8. 한 DB transaction에서 exchange 소비, identity lookup/최초 provisioning, STOG session과 최초 refresh-token row를 commit한다. commit 전 오류는 전부 rollback한다.
9. 성공 응답을 받은 Android는 STOG access token은 memory에, refresh token은 §8 storage에 둔 뒤 Kakao SDK `logout()`으로 **이 앱에 발급된 Kakao access/refresh token을 무효화하고 SDK local token을 제거**한다. 이는 Kakao Account global logout이나 Kakao unlink가 아니다. SDK logout 실패는 STOG token을 log하거나 되돌리지 않지만 사용자에게 provider cleanup 미확인을 표시하고 다음 Kakao API를 호출하지 않는다. backend의 exchange fingerprint가 STOG 재교환은 계속 막는다.

DB commit 뒤 response가 유실되면 raw STOG token은 복구할 수 없고 같은 Kakao token의 재교환도 허용하지 않는다. Android는 provider token을 SDK에서 제거하고 새 Kakao login부터 다시 시작한다. exchange 성공 replay를 위해 raw session token을 저장하는 복잡성보다 명시적 재로그인을 선택한다.

Kakao가 credential을 invalid/expired로 명시적으로 거부하면 `401 AUTHENTICATION_REQUIRED`다. 두 endpoint의 valid response에서 app ID 또는 service user ID가 일치하지 않는 identity mismatch도 같은 401이다. 반면 network timeout/connection failure, provider 5xx/429, 또는 필수 field를 판정할 수 없는 malformed provider response는 `503 AUTH_PROVIDER_UNAVAILABLE`이다. malformed client JSON/body/token field는 provider 호출 전 `400 INVALID_REQUEST`다. 어느 실패도 User, identity, exchange-consumption 또는 session row를 만들지 않으며 provider 장애를 invalid identity로 추정하지 않는다.

### 3.2 OIDC/JWT를 첫 경로로 쓰지 않는 이유

Kakao OIDC는 discovery, RS256/JWKS, `aud`, `exp`, `nonce`와 PKCE `S256`을 지원한다. 그러나 Android SDK의 문서화된 최소 native flow는 custom-scheme callback을 SDK가 받아 authorization code를 token으로 교환한 뒤 `OAuthToken`을 앱에 제공한다. backend-owned REST callback/OIDC를 추가하면 public redirect, REST API key/client secret, nonce/state 저장과 key cache라는 미승인 표면이 생긴다. 현재는 official token-info의 server-side validity/app binding과 최소 user-info ID 교차 확인이 더 작은 경로다. public multi-server 또는 browser login이 실제 요구가 될 때 별도 계약으로 OIDC를 재평가한다.

## 4. HTTP 계약

모든 token 값은 예시·로그·evidence에 넣지 않는다. 알 수 없는 JSON field, duplicate key, null, 빈 값, 과대한 값은 `400 INVALID_REQUEST`다.

### `POST /api/auth/kakao/exchange`

```json
{"kakao_access_token":"<redacted>"}
```

성공은 기존/신규 User 여부를 구분하지 않고 `200`이다.

```json
{
  "token_type": "Bearer",
  "access_token": "<opaque>",
  "access_expires_in": 900,
  "refresh_token": "<opaque>",
  "refresh_expires_in": 2592000
}
```

provider가 명시적으로 invalid/expired로 판정한 credential, expected app ID 불일치, token-info/user-info ID 불일치, 이미 소비한 token은 기존 최소 envelope의 `401 AUTHENTICATION_REQUIRED`로 합친다. client request의 JSON/type/필수값 형식 오류는 `400 INVALID_REQUEST`이고, timeout/connection failure/provider 5xx·429/malformed provider response는 `503 AUTH_PROVIDER_UNAVAILABLE`이다. 어느 branch도 provider subject, app ID 또는 기존 계정 존재를 노출하지 않는다.

### 보호된 기존 route

`Authorization: Bearer <STOG access token>`만 받는다. filter는 current access-token hash, expiry와 session revoke 상태를 확인한 뒤 정확히 `AuthenticatedUser(user_id)`만 principal로 만든다. Kakao token/subject, String principal, `X-User-Id`, body/path/query `user_id`는 principal이 될 수 없다.

### `POST /api/auth/refresh`

```json
{"refresh_token":"<opaque>"}
```

유효한 현재 refresh token을 row-lock하고 한 transaction에서 기존 token을 `used_at` 처리하고 새 access/refresh token으로 회전한다. 새 refresh expiry는 `min(now + 30 days, session.absolute_expires_at)`이다. 사용·만료·폐기·unknown token은 generic `401 AUTHENTICATION_REQUIRED`다. **사용된 refresh token이 재사용되면 해당 session family 전체를 즉시 revoke**하고 401을 반환한다. 동시에 같은 refresh token을 사용하면 최대 한 요청만 성공하며 replay를 본 요청이 family를 revoke하므로 성공 응답을 받은 token도 이후 사용할 수 없다; client는 Kakao 재로그인한다.

### `POST /api/auth/logout`

```json
{"refresh_token":"<opaque>"}
```

refresh-token history로 current session을 찾아 `revoked_at`을 atomic하게 기록한다. 현재/이미 사용된 같은 family token, 이미 revoked/expired session 또는 unknown 값 모두 외부에는 `204 No Content`로 동일하게 응답해 idempotent하고 account/session oracle이 되지 않는다. logout은 현재 session만 폐기한다. 이후 그 session의 access/refresh token은 401이다. 다른 기기 session, Kakao Account session, Kakao app link와 내부 User/data는 바꾸지 않는다.

network 실패 시 Android는 local token을 즉시 제거하되 "server revoke 미확인" 상태를 숨기지 않는다. background queue는 만들지 않는다. 미확인 server session은 제안된 absolute expiry까지 남을 수 있으므로 실제 release 전에 all-device recovery 또는 사용자 self-service revoke 필요성을 다시 판단한다.

## 5. token 규칙

- access/refresh token은 CSPRNG 256-bit 이상, base64url no-padding opaque 값이다. server는 SHA-256 hash만 저장하고 raw 값은 성공 response 이후 복구할 수 없다.
- access token은 15분 뒤 만료한다. refresh 때 access hash를 교체하므로 이전 access token은 남은 시간이 있어도 즉시 무효다.
- refresh token은 발급/회전 시점부터 최대 30일이지만 session 생성부터 90일 absolute expiry를 넘지 않는다.
- server/DB clock이 expiry 원본이다. revoked/expired/unknown credential은 동일 401이며 자동 grace period는 없다.
- token을 URL, cookie, query, saved state, 일반 log 또는 analytics에 넣지 않는다. `Authorization`과 token body는 모든 request/error logging에서 redact한다.
- `Cache-Control: no-store`와 `Pragma: no-cache`를 exchange/refresh 성공·오류 token response에 적용한다.

## 6. 최소 schema 제안

V2는 V1의 `stog_user(id UUID PRIMARY KEY)`와 domain FK를 바꾸지 않고 아래 의미만 additive하게 추가했다.

| 관계 | 최소 필드 / constraint | 목적 |
| --- | --- | --- |
| `auth_identity` | `provider`, canonical `subject`, `user_id`, `created_at`; PK `(provider, subject)`, `UNIQUE(user_id)`, FK `user_id -> stog_user`; provider check `KAKAO` | 한 Kakao subject = 한 내부 User, 첫 구현에서 한 User = 한 identity. email/profile 없음 |
| `auth_login_exchange` | `provider`, `credential_hash`, `subject`, `consumed_at`, `expires_at`; PK `(provider, credential_hash)` | 같은 Kakao token exchange replay 차단. identity insert 전 reservation이므로 identity FK는 두지 않고 provider token expiry 뒤 hard-delete |
| `auth_session` | `id`, `user_id`, `access_token_hash UNIQUE`, `access_expires_at`, `created_at`, `absolute_expires_at`, nullable `revoked_at`; FK User와 시간 check | current opaque access credential과 즉시 revoke |
| `auth_refresh_token` | `token_hash PK`, `session_id`, `issued_at`, `expires_at`, nullable `used_at`; FK session, 한 session당 `used_at IS NULL` row 최대 하나 | rotation과 concurrent/replayed refresh 판정 |

raw provider/STOG token, email, nickname, profile, Kakao refresh token, IP/device fingerprint는 저장하지 않는다. exchange fingerprint는 provider가 응답한 expiry에 제거한다. terminal session과 refresh history는 absolute expiry 뒤 삭제할 수 있으며 장기 audit retention은 제안하지 않는다.

## 7. atomic provisioning과 동시성

유효 provider 응답을 받은 뒤 다음을 한 transaction에서 수행한다.

1. `(KAKAO, credential_hash)`를 insert한다. unique conflict면 전체 요청을 401로 종료한다.
2. 새 UUID candidate를 `stog_user`에 insert한다.
3. `(KAKAO, subject, candidate_user_id)`를 `auth_identity`에 `ON CONFLICT (provider, subject) DO NOTHING`으로 insert한다.
4. insert 승자면 candidate가 principal이다. conflict loser면 자기 candidate `stog_user`를 같은 transaction에서 delete하고 committed identity의 `user_id`를 읽는다.
5. 선택된 `user_id`로 session과 initial refresh row를 insert한다.
6. 모두 commit된 뒤에만 raw STOG token을 응답한다.

PostgreSQL unique constraint가 같은 subject의 동시 최초 login을 serialize한다. 서로 다른 유효 Kakao token으로 같은 subject가 동시에 들어오면 내부 User는 정확히 하나이고, 각 요청은 그 User의 별도 session을 가질 수 있다. 같은 Kakao token의 동시는 exchange unique constraint 때문에 session도 최대 하나다. DB failure, candidate cleanup 실패 또는 constraint 불일치는 전부 rollback/503이며 orphan User, identity 없는 session 또는 반쪽 credential을 남기지 않는다.

## 8. Android 책임

- `ANDROID-KAKAO-AUTH-001`은 official 최소 module `com.kakao.sdk:v2-user:2.25.0`만 pin했다. `v2-all`, Rx API와 provider profile 기능은 추가하지 않았다. Apache-2.0 POM과 전이 dependency graph를 기록했다.
- SDK AAR의 minSdk 23과 Java-callable `KakaoSdk.init`, `UserApiClient` login/logout, `OAuthToken.getAccessToken()` surface를 실제 compile gate에서 확인했고 앱 source는 Java/XML만 유지한다.
- callback activity는 SDK 문서의 exact scheme/host만 받고 다른 domain deep link와 섞지 않는다. callback 성공·취소·오류를 lifecycle-safe 단일 login state로 전달하고 stale callback은 현재 attempt identity와 일치할 때만 소비한다.
- access token은 process memory에만 둔다. refresh token은 Android Keystore의 non-exportable AES/GCM key로 암호화해 private SharedPreferences에 저장한다. 별도 crypto dependency, Room, AccountManager를 추가하지 않는다. backup에는 포함하지 않는다. key invalidation/decrypt 실패면 ciphertext를 지우고 signed-out으로 전환한다.
- process recreation은 refresh로 새 access token을 얻는다. saved state, Fragment arguments, Intent extras, crash report와 log에 credential을 넣지 않는다.
- 401은 한 번의 serialized refresh만 시도한다. refresh 성공 후 원래 read는 한 번 재시도할 수 있지만 mutation은 기존 `request_id`를 그대로 사용한다. refresh 실패/replay는 local token을 지우고 Kakao 재로그인 상태로 간다.
- logout은 STOG revoke를 시도하고 local STOG token을 제거한 뒤 Kakao SDK token도 제거한다. Kakao **unlink** 또는 Kakao Account global logout은 호출하지 않는다.

## 9. 판정 가능한 acceptance scenarios

| ID | 시나리오 | 기대 결과 |
| --- | --- | --- |
| A-00 | malformed client JSON/body/token field | provider를 호출하지 않고 400 `INVALID_REQUEST`; auth/domain DB 변화 없음 |
| A-01 | valid, unconsumed Kakao token; matching app ID; token-info/user-info ID 일치; 처음 보는 subject | 200; User/identity/session 각 1개 atomic commit; 이후 bearer가 같은 User의 `AuthenticatedUser` 생성 |
| A-02 | 같은 subject의 새 valid Kakao token | 기존 `user_id` 유지; 새 User 없음; 새 session만 생성 |
| A-03 | provider가 credential을 invalid/expired로 명시적 거부하거나 두 valid endpoint response의 service user ID가 불일치 | 401 `AUTHENTICATION_REQUIRED`; auth/domain DB 변화 없음 |
| A-04 | valid token이지만 audience binding인 `app_id`가 configured expected app ID와 다름 | A-03과 외부 결과 동일; subject를 신뢰하거나 저장하지 않음 |
| A-05 | 같은 Kakao token 재교환, 같은 token 동시 교환 또는 committed response 유실 뒤 retry | 최초 commit 최대 하나; 나머지 401; 추가 User/session 없음; response 유실 client는 새 Kakao login |
| A-06 | 같은 새 subject, 서로 다른 valid token의 concurrent first login | `auth_identity`/`stog_user` 정확히 하나; 성공 요청 모두 같은 `user_id`; orphan candidate 없음 |
| A-07 | access expiry/revoke/refresh 후 이전 access 사용 | 보호 route 401, domain service/여행 조회에 진입하지 않음 |
| A-08 | current refresh | old refresh used 처리, access/refresh 모두 회전; old access 즉시 무효 |
| A-09 | used refresh replay 또는 concurrent refresh | session family revoke; replay 요청 401; 그 family의 모든 access/refresh가 이후 401 |
| A-10 | logout 후 access/refresh 재사용, logout 반복 | logout은 204; token 재사용은 401; 반복 logout 204; 다른 session은 유지; SDK cleanup은 app-issued Kakao token만 무효화하고 global Kakao Account logout/unlink는 호출하지 않음 |
| A-11 | provider timeout/connection failure/5xx/429/malformed response 또는 DB transaction 실패 | 503 `AUTH_PROVIDER_UNAVAILABLE` 또는 DB용 generic 503 envelope; partial provisioning/session 없음; raw token/log leakage 없음 |
| A-12 | anonymous, `X-User-Id`, String principal | 기존 protected POST/GET 모두 기존 `401 AUTHENTICATION_REQUIRED`; domain service 호출 없음 |
| A-13 | valid STOG principal이 missing trip 또는 nonmember trip 조회 | 두 경우 모두 기존 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`; authorized trip의 missing/cross/deleted item만 `404 TRIP_ITEM_NOT_FOUND` |
| A-14 | same principal basket replay/mutation | 기존 `(principal_user_id, command_type, request_id)`, membership 재검사, 401/404와 transaction/SSE 규칙이 그대로 통과 |

후속 구현의 failing-first 범위는 A-00, A-03~A-10, A-12~A-14다. 실제 Kakao/device E2E는 account/config/dependency 승인 뒤 별도 gate이며 이 문서로 통과를 주장하지 않는다.

## 10. 외부 선행조건과 정확한 stop gate

필요한 외부 항목은 Kakao Developers app의 권한 있는 소유자, Kakao Login 활성화, Android package/key hash, Native App Key, expected numeric app ID, 공식 Kakao Android SDK repository/`v2-user`, backend outbound HTTPS to `kapi.kakao.com`, Android가 접근할 승인된 HTTPS STOG base URL이다. 사용자 보고의 `com.stog.app` 등록/Login 활성화/key hash 갱신은 runtime 검증이 아니다. Native App Key는 Android 배포 식별자이지 backend secret은 아니지만 실제 값은 이 문서나 evidence에 기록하지 않는다.

**남은 external 실행은 다음 최초 지점에서 반드시 멈추고 사용자 승인/입력을 요청한다:** Kakao Developers console/account를 열거나 변경하기 전, 실제 Native App Key/app ID/key hash 값을 읽거나 설정하기 전, real key/token을 발급·사용하기 전, `kapi.kakao.com` 호출, real Android callback 또는 public network exposure를 시작하기 전. SDK dependency와 Android local fixture/device implementation은 `ANDROID-KAKAO-AUTH-001`에서 승인·검증됐다. secret 값은 대화, source, command line, log 또는 evidence로 받지 않는다.

## 11. 남은 사용자 전용 결정과 bounded default

긴 protocol 선택 질문은 필요 없다. 아래 네 항목만 사용자 결정이며, 괄호가 승인 전 권고 기본값이다.

1. **운영 소유권:** Kakao Developers app과 설정을 누가 소유·변경·복구하는가. (사용자 본인이 유일한 승인자이며 공유 credential 없음.)
2. **0원 통제:** 실제 app의 quota/billing/paid API 상태와 한도 초과 동작. (검증할 수 없거나 한도에 닿으면 login을 fail closed로 중단; 유료 전환 없음.)
3. **session 정책 상태:** access 15분, refresh inactivity 30일, absolute 90일, multi-session/current-session logout을 DEVELOPMENT 기본값으로 구현했다. 이는 timeout 뒤 best-judgment 위임이며 사용자의 항목별 직접 선택은 아니다; all-device logout은 후속 명시 요구가 있을 때만 추가한다.
4. **삭제·복구:** Kakao unlink/account deletion 때 내부 domain data와 identity 삭제, Kakao 접근 상실·subject 변경의 복구 주체. (첫 구현에는 merge/recovery/deletion endpoint가 없고 새 subject는 새 계정; release 전 별도 deletion 계약과 Kakao unlink 요구를 반드시 승인·구현. email fallback 없음.)

1~2가 확인되지 않으면 external connection을 시작할 수 없다. 3은 local DEVELOPMENT 정책으로 승격됐지만 release 정책의 사용자 직접 확인은 남는다. 4는 local 개발 auth slice를 막지 않지만 실제 사용자 release를 막는다.

## 12. 근거와 QA

### 공식 자료 (2026-09-28 read-only 확인)

- Kakao Android getting started: API 23+, Native App Key/key hash, Kakao Maven repository, `v2-user`, Retrofit/OkHttp 전이 의존성 — https://developers.kakao.com/docs/latest/en/android/getting-started
- Kakao Android SDK versions: current page의 latest `2.25.0` / 2026-08-31 — https://developers.kakao.com/docs/latest/en/android/download
- Kakao Login Android: SDK custom redirect `kakao{NATIVE_APP_KEY}://oauth`, SDK authorization-code 수신/token 교환, login/logout 동작 — https://developers.kakao.com/docs/latest/en/kakaologin/android
- Kakao Login concepts: service user ID, access/refresh 수명, email을 identity 기준으로 권하지 않음, unlink/deletion 책임 — https://developers.kakao.com/docs/latest/en/kakaologin/common
- Kakao Login REST: token-info의 `id`, `expires_in`, `app_id`, user-info의 service user ID, OIDC audience/subject/nonce, logout과 unlink 차이 — https://developers.kakao.com/docs/latest/en/kakaologin/rest-api
- Kakao OIDC discovery: issuer, RS256, pairwise subject, PKCE S256 metadata — https://kauth.kakao.com/.well-known/openid-configuration
- Spring Security session management: `SessionCreationPolicy.STATELESS`는 `HttpSession`을 만들지 않고 `NullSecurityContextRepository`를 사용 — https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html
- Spring Security bearer tokens: 기본 bearer token 위치는 `Authorization` header — https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/bearer-tokens.html

공개 문서는 STOG 소유 account의 비용 0원, quota/billing 상태 또는 Java source compile을 증명하지 않는다. 그 항목은 §10~11 gate로 남긴다.

### repository QA-by-read

- V1의 `stog_user`/domain schema는 보존하고 additive V2 auth table만 추가했음을 확인했다.
- `SecurityConfig`는 세 auth endpoint만 public으로 두고 나머지는 authenticated이며 form/basic/framework logout disabled, `STATELESS`, JSON 401을 유지한다.
- `BasketSaveController` 두 route는 `Authentication.principal instanceof AuthenticatedUser`만 받아 `userId()`를 service에 넘긴다.
- `BasketSaveService`/tests는 그 ID로 current membership, replay scope와 401/404 non-disclosure를 지킨다. positive principal은 test-only다.
- Android는 Java/XML, `minSdk 23`, `v2-user:2.25.0`, platform HTTP, Keystore crypto와 exact auth callback을 구현했다. manifest backup은 disabled다. real key/provider callback은 NOT_RUN이다.
- `LOCAL-BACKEND-001` local runtime evidence와 PLAN 상태를 보존했다.

### local backend 구현 evidence (2026-09-28)

- `V2__kakao_opaque_auth.sql`, auth controller/service/provider verifier/access filter와 fail-closed 중앙 설정을 구현했다. 새 runtime dependency는 없다.
- 최소 RED는 미구현 public exchange가 401을 반환한 실패로 보존했다: `.omo/artifacts/kakao-auth-backend-001/red-proof.json`.
- 최초 구현의 isolated PostgreSQL suite 46개와 `bootJar`를 검증했다. 이후 review fix 최종 suite는 52개(`AuthPostgresIntegrationTest` 13, destination verifier 5, boundary/controller/PostgreSQL 회귀 34), failure/error/skip 0이며 generated role/schema cleanup과 preexisting metadata 보존도 통과했다: `review-fix/postgres-runner-result.json`, `review-fix/full-postgres-suite.log`.
- dedicated `stog_backend_dev/stog_app`은 Flyway V2를 적용·검증했고 DPAPI launcher와 loopback provider fixture로 exchange 200, authenticated protected missing-trip 404, logout 204, 이후 access/refresh 401을 관찰했다: `loopback-surface-result.json`, `local-backend-runtime.log`.
- review RED는 untrusted configured loopback destination이 bearer를 받은 실패와 `Long.MAX_VALUE expires_in`의 잘못된 외부 결과를 각각 보존한다. normal mode는 exact official Kakao HTTPS URI만, explicit fixture mode는 literal loopback HTTP만 허용하며 redirect를 따르지 않는다. provider expiry는 임의 product lifetime cap이 아니라 PostgreSQL finite `TIMESTAMPTZ` 상한(294276 AD)과 Java representability 안에서만 받아 provisioning 전 503으로 거부한다. 4096-character credential은 provider boundary에 도달하고 4097-character/CRLF credential은 provider 호출 전 400임을 검증했다.
- evidence와 일반 log에는 raw provider/STOG token을 기록하지 않았다. QA backend/fixture process와 fixture auth rows는 정리했고 migrated durable dev DB는 유지했다. 실제 LSP 결과는 `review-fix/lsp-diagnostics.json`에 보존했다.

이 TASK의 backend fixture verification에는 real `kapi.kakao.com`, real key/token/account, configured SDK callback과 public networking이 포함되지 않았다. 후속 bounded real Kakao loopback runtime은 [ANDROID-KAKAO-AUTH-001](ANDROID-KAKAO-AUTH-001.md)과 [cleanup receipt](../../.omo/artifacts/real-kakao-login-runtime/cleanup-receipt.json)에 기록됐으며, protected domain과 public networking은 `NOT_RUN`이다. SDK Java compile, local fixture instrumentation과 missing-config Android device UI는 `ANDROID-KAKAO-AUTH-001`에서 VERIFIED다.
