# ACCOUNT-OPERATIONS-001: 계정 수명, 보관, 비용과 소유권 정책

## 상태

| 구분 | 상태 |
| --- | --- |
| 문서 목적 | 운영 정책 결정 패킷 |
| 현재 구현 | `IMPLEMENTED / local VERIFIED`, 운영 정책은 `PROPOSED` |
| 실제 Kakao QA | 일회성 bounded login, logout, relogin `VERIFIED` |
| 운영 표면 | live quota 확인, public HTTPS, 배포, 실제 삭제와 backup restore `NOT_RUN` |

이 문서는 endpoint, schema rewrite, provider 재선택 또는 운영 실행을 승인하지 않는다.

## CURRENT IMPLEMENTATION

* Kakao Login을 첫 provider로 선택했다. 검증된 `(KAKAO, subject)`는 내부 User에 자동 provision되고 같은 subject는 같은 User로 해석된다. email, nickname, profile을 기준으로 자동 merge 또는 link하지 않는다. [PLAN](../PLAN.md), [KAKAO-AUTH-CONTRACT-001](KAKAO-AUTH-CONTRACT-001.md)
* 실제 Kakao QA는 이미 승인된 일회성 local bounded 검증으로 수행됐다. 동일 identity의 login, 현재 session logout, relogin과 session revoke를 확인했지만 live 운영 quota나 public 운영 준비를 확인한 것은 아니다. [PLAN](../PLAN.md), [real Kakao evidence](../../.omo/artifacts/real-kakao-login-runtime/)
* backend는 raw Kakao 또는 STOG token을 저장하지 않고 hash만 저장한다. access 15분, refresh inactivity 30일, session absolute 90일, 현재 session만 logout은 local DEVELOPMENT 기본값이다. refresh replay는 session family를 revoke한다. 현재 route는 exchange, refresh, 현재 session logout뿐이며 all-device logout은 없다. [BACKEND](../BACKEND.md), [API](../API.md#구현된-인증-wire)
* 현재 schema에는 `auth_identity`, `auth_login_exchange`, `auth_session`, `auth_refresh_token`이 있다. raw token, email/profile, IP/device fingerprint는 없다. expired session과 refresh history의 자동 maintenance cleanup은 아직 없다. [DATABASE](../DATABASE.md#v2-backend-authentication-schema)
* TripItem 보호 조회는 principal과 현재 TripMember를 확인한다. Trip, TripMember, Place, TripItem은 FK로 연결되고 TripItem에는 생성 principal이 기록된다. 여행 삭제, member 변경, basket 삭제는 현재 제공하지 않는다. [BACKEND](../BACKEND.md#현재-tripitem-조회), [DATABASE](../DATABASE.md#최소-논리-관계)
* local DB에는 real Kakao QA에서 사용한 Kakao-linked identity와 내부 User가 의도적으로 보존되어 있다. 기존 identity와 user를 승인 없이 삭제하지 않는다. 새 QA에는 불필요한 개인 payload를 넣지 않고 raw token을 저장하거나 기록하지 않는다. fixture와 task-owned row, process와 reverse 설정은 정리하되 retained development DB 자체는 유지한다. [LOCAL-BACKEND-001](LOCAL-BACKEND-001.md), [QUALITY_OPERATIONS](../QUALITY_OPERATIONS.md)

## PROPOSED POLICY

### 1. logout, unlink, STOG 삭제

* **Logout:** 현재 STOG session을 즉시 revoke한다. 다른 기기 session은 남으므로 all-device revoke는 별도 운영 동작이다.
* **Unlink:** 모든 STOG session을 즉시 revoke하고, Kakao 약관과 unlink/webhook 기술 요구를 확인한 뒤 unlink-specific deletion/retention contract를 적용한다. provider-subject mapping을 무기한 보존한다고 권장하지 않는다. 기존 Trip, TripMember, TripItem, Place의 공유 또는 비개인 domain data 보존은 별도 결정이며 provider identity 보존의 근거가 아니다. 현재 schema에는 이 unlink 상태와 contract를 실행할 구조가 없다.
* **STOG 계정 삭제:** unlink와 별개인 삭제다. 명시적 요청과 ownership 확인 뒤 identity mapping, session, refresh history를 처리한다. 공유 Trip, TripMember, TripItem, Place는 다른 member의 접근과 데이터 보존을 위해 자동 삭제하지 않는다. 고립된 데이터와 마지막 member 처리만 별도 계약한다.

Kakao 공식 문서는 `POST https://kapi.kakao.com/v1/user/unlink`을 연결된 서비스에서 사용자의 연결을 끊는 API로 설명하며, unlink webhook이 설정된 경우 서비스가 해당 사용자 정보를 삭제해야 한다고 안내한다. 이는 Kakao 서비스 연결과 webhook 처리 의미에 대한 기술 문서다. STOG 내부 공유 데이터의 삭제 범위나 특정 국가의 법적 보관 의무를 정하는 근거로 확대하지 않는다. [Kakao Login REST API, Unlink](https://developers.kakao.com/docs/en/kakaologin/rest-api#unlink), [Kakao unlink webhook](https://developers.kakao.com/docs/en/app-setting/app#unlink-webhook)

Kakao access 상실 또는 subject 변경은 email 자동 recovery로 우회하지 않는다. 기존 subject와 일치하지 않으면 로그인하지 못한다. 지원-assisted ownership proof는 별도 수동 지원 절차로만 제안하며, proof가 통과해도 복구와 삭제의 의미는 별도 결정한다. 현재 이 절차와 endpoint는 구현되지 않았다. 외부 tester rollout 전에는 unlink-specific contract를 Kakao 약관과 기술 문서에 대조해 확정해야 한다.

### 2. session과 구체적 cleanup

15분 access, 30일 refresh inactivity, 90일 session absolute는 DEVELOPMENT 기본값이며 법정 보관 기간이 아니다. refresh history는 해당 session의 absolute expiry까지 replay detection을 위해 유지한다. absolute expiry가 지난 뒤에는 session row와 그 session의 refresh token hash/history를 purge한다. 별도 audit 목적이 승인되지 않는 한 hash를 무기한 보관하지 않는다.

최소 retained metadata는 내부 User UUID, `(provider, subject)`, 생성과 취소 시각, session 상태, replay 판정에 필요한 hash와 시각이다. raw Kakao token, email/profile, IP/device fingerprint는 보관하지 않는다. 이 cleanup은 현재 구현되지 않았고, 실제 maintenance job과 삭제 관찰은 별도 TASK가 필요하다.

### 3. shared data, backup과 local lifecycle

한 User가 여러 Trip의 member일 수 있고 한 TripItem은 여러 member가 함께 읽을 수 있다. `created_by_user_id`만으로 공유 row를 지우지 않는다. unlink나 account deletion이 다른 사용자의 Trip, TripItem, Place를 연쇄 삭제하지 않도록 한다.

Account/domain backup의 보관 기간과 삭제 전파는 shared-data policy가 결정될 때까지 `UNDECIDED`다. 법적 최소 기간을 만들지 않는다. backup과 restore는 현재 `NOT_IMPLEMENTED / NOT_RUN`이며 복구 뒤 삭제 재적용도 보장하지 않는다.

local DB, schema, DPAPI credential은 기본적으로 유지한다. 새 작업에는 불필요한 개인 payload를 추가하지 않고, fixture와 task-owned row만 정리한다. DB drop, role 삭제, migration reset, credential 삭제와 기존 real QA identity/user 삭제는 명시적으로 승인된 teardown에서만 실행한다.

### 4. ownership과 비용

계정 owner, Kakao Developers app owner, backend 운영자, Trip owner와 member role은 서로 다른 책임이다. 현재 TripMember role은 관리자와 일반 구성원이며 account/project owner 정책은 미구현이다.

제품 비용 0원과 paid fallback 금지는 `CONFIRMED`다. Kakao를 다른 provider로 바꾸거나 비용 정책을 다시 승인받는 질문은 하지 않는다. live operational quota, billing 상태, 초과 차단과 중단 절차는 아직 확인하지 않았다. quota 초과 시 영향을 받는 Kakao 호출과 그에 의존하는 login 또는 provider 작업만 fail closed하고, unrelated import 기능까지 이 정책으로 차단한다고 정하지 않는다.

## DELEGATED BEST-JUDGMENT DEFAULTS

사용자 질문인 beta 삭제와 복구, 외부 tester 범위는 timeout에 따라 아래 권장 기본값을 채택한다. 이는 명시적 개인 선택이 아니며, 삭제·revoke·access control 구현도 아니다. Kakao-only, email recovery 또는 merge 금지, paid fallback 0원, 기존 real identity와 data 보존 원칙은 유지한다.

1. **운영 주체, `CONFIRMED`:** 본인이 단독 운영 주체다. 현재 개발·테스트 Kakao 앱과 STOG backend의 승인 및 데이터 운영을 책임지며, `TripMember` 역할과 별개다. 법적 소유권 확인, console 권한 감사, 비용 확인, 삭제·보관 승인 또는 public exposure 권한을 뜻하지 않는다.
2. **beta 삭제와 복구, `DELEGATED BEST-JUDGMENT DEFAULT / PREPARATION ONLY`:** self-service 삭제 대신 owner-assisted deletion request를 사용한다. 요청자 ownership verification, 영향을 받는 auth identity와 shared FK inventory, all-session revoke 계획, provider identity deletion 규칙을 먼저 확인한다. 실제 삭제와 revoke는 하지 않으며, 구체적인 destructive action에 대한 명시 승인이 있어야 실행한다. 현재-session logout을 all-device revoke로 주장하지 않는다. Kakao access 분실에는 email recovery를 허용하지 않는다.
3. **외부 tester, `DELEGATED BEST-JUDGMENT DEFAULT / PREPARATION ONLY`:** 첫 외부 테스트 대상은 운영 주체 본인만으로 제한한다. 외부 tester 공개와 access control은 구현되지 않았고, owner-only restricted external test 준비만 다음 작업 범위로 둔다.

## SAFE OPERATOR CHECKLIST

* 요청자의 신원을 확인하되 credential을 받거나 저장하지 않는다.
* 영향을 받는 auth identity, session, Trip, TripMember, Place, TripItem과 shared FK를 inventory한다.
* 삭제 대상과 provider unlink 대상을 구체적으로 적고, destructive action 전에 명시적 승인을 요구한다.
* all-session revoke 계획을 별도로 기록한다. 현재-session logout은 all-device revoke가 아니다.
* provider identity 삭제 규칙과 shared data, backup, 법적·vendor 요구사항을 확인하기 전에는 실행하지 않는다.
* 기존 real identity와 data는 명시적 승인 전까지 보존하고, cleanup은 fixture와 task-owned row에만 한정한다.

## RELEASE BLOCKERS와 LOCAL DEVELOPMENT 허용

**Release blocker:** shared-data와 backup deletion policy, 법적·vendor deletion 요구사항, live Kakao quota와 중단 절차, public HTTPS와 production auth E2E, 실제 owner-assisted deletion/revoke/access control 구현과 검증이 남아 있다.

**Local development 허용:** loopback backend, retained existing real QA identity/user, synthetic fixture, generated task rows, current-session logout, 15분/30일/90일 DEVELOPMENT 기본값과 fixture cleanup이다. 이는 새 개인 payload 추가, raw token 보관, 기존 user 삭제, public exposure 또는 production E2E를 허용하지 않는다.

## VERIFY와 NOT_IMPLEMENTED

이 문서는 source, schema, API, real Kakao QA receipt와 Kakao 공식 REST 문서를 읽은 `QA-BY-READ` 정정이다. delegated best-judgment default와 preparation 범위만 기록했으며, 실제 unlink, account deletion, all-device revoke, cleanup job, backup/restore, live quota verification, public service와 tester route는 수행하지 않았다. 90일 absolute session cleanup은 구현 전까지 제안 상태이며 임의 자동 삭제로 확대하지 않는다. Kakao 기술 문서의 unlink 처리 안내와 법적 의무는 구분했으며, 관할별 법률상 보관 의무는 확인하지 않았다.

관련 근거: [PLAN](../PLAN.md), [API](../API.md), [BACKEND](../BACKEND.md), [DATABASE](../DATABASE.md), [QUALITY_OPERATIONS](../QUALITY_OPERATIONS.md), [KAKAO-AUTH-CONTRACT-001](KAKAO-AUTH-CONTRACT-001.md), [ANDROID-KAKAO-AUTH-001](ANDROID-KAKAO-AUTH-001.md).
