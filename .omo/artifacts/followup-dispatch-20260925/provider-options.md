# 한국 Android 인증 공급자 선택지

**상태: 제안됨 / 차단됨 (2026-09-25).** 이 문서는 범위를 제한한 비교이며, 공급자 선택 승인, 개발자 프로젝트 생성, 서비스 연결, 자격 증명 생성, 로그인 구현을 승인하지 않는다. `docs/PLAN.md:68-70`은 공급자 선택이나 구현 TASK 전에 정확한 사용자 입력을 요구한다. 현재 서버는 검증된 내부 `principal_user_id`만 받는다. 공급자 로그인, 토큰·세션 발급, 로그아웃, 최초 사용자 provisioning은 계속 미결정이다(`docs/BACKEND.md`, `인증 principal 경계`).

## 적용한 제약

- Android 앱은 Java/XML과 `minSdk 23`을 사용한다. 이 사실만으로 특정 공급자 SDK 또는 버전과의 호환성은 검증되지 않는다(`README.md`, `AGENTS.md`, `현재 범위와 기술`).
- 제품은 유료 대안을 허용하지 않고 추가 비용 0원을 요구한다. 연결 전에 책임 소유자가 실제 공급자 계정의 이용 자격, 한도, 과금 차단 수단을 확인해야 한다. 공개된 무료 등급은 그 계정의 확인 증거가 아니다(`AGENTS.md`, `범위·공급자·비용 통제`).
- 선택된 공급자는 기존의 실패-차단 내부 principal 경계에서 끝나야 하며, 클라이언트가 제공한 `user_id`를 받으면 안 된다(`docs/API.md`, `확정 의미`; `docs/BACKEND.md`, `인증 principal 경계`).

## 가능한 단일 공급자 선택지: 최대 3개

| 선택지 | 현재 공식 Android 근거 | 계정·설정 의미 | 비용 상태 | 적합성 및 미해결 위험 |
| --- | --- | --- | --- | --- |
| **Kakao Login** | Kakao는 Android Login 안내와 Android redirect intent filter/custom `kakao{NATIVE_APP_KEY}://oauth` scheme을 문서화한다. Login 개념 문서는 OAuth 2.0 access/refresh token과 선택적 OIDC ID token을 설명한다. [Android 안내][kakao-android], [개념][kakao-concepts] | Kakao Developers 앱과 Android 플랫폼·redirect 설정이 필요하다. 문서의 redirect와 app key는 설정 식별자일 뿐, 이 보고서에 기록할 값이 아니다. | **STOG에 대해 미검증.** 검토한 공식 Login 안내는 STOG 계정의 과금 또는 지출 0원 차단 수단을 입증하지 않는다. SDK 제공 사실만으로 운영 무료 사용을 추론하지 않는다. | Android SDK와 OAuth 흐름이 문서화된 한국 계정 공급자 선택지다. custom-scheme redirect의 소유권·충돌 처리와 서버 측 token 검증은 별도 설계가 필요하다. |
| **NAVER ID Login** | NAVER의 공식 Android 안내는 로그인, 로그아웃, token 관리를 위한 Android SDK를 설명하며, 구현 전에 Open API 이용 신청이 필요하다고 명시한다. 예제는 Kotlin이다. [NAVER Android 안내][naver-android] | NAVER Open API/application 승인과 client 설정이 필요하다. 현재 SDK를 Java/XML 앱 소스에 Kotlin 작성 코드 없이 연결할 수 있는지는 **미검증**이며, 승인 전에 지원되는 Java 통합 경로를 확인해야 한다. | **STOG에 대해 미검증.** 검토한 공식 출처는 최종 소유자의 한도, billing 상태, 0원 지출 차단 수단을 검증하지 않는다. | 한국 계정 대안으로 가능하나, 공개 Kotlin 예제는 저장소의 Java 전용 소스 규칙에 대한 별도 호환성 확인을 요구한다. |
| **Google Sign in with Credential Manager** | Android 공식 Identity 문서는 Credential Manager를 통한 Sign in with Google과 backend ID-token 처리 안내를 제공한다. [Android Identity 안내][google-android] | 제품 소유자는 backend 흐름을 설계하기 전에 OAuth/Google Cloud 프로젝트 소유권과 승인된 client/server 식별자를 정해야 한다. | **이 선택지/STOG에 대해 미검증.** Google Identity Platform 가격 페이지는 Identity Platform 사용 시에만 MAU 가격과 무료 할당량을 문서화한다. 이는 Credential Manager만 쓰는 경우나 소유자의 billing 비활성화 수단을 확인하지 않는다. [Identity Platform 가격][google-pricing] | Android에 문서화된 대안이나, Identity Platform 채택 여부와 Google 프로젝트 billing 소유권은 모두 승인되지 않았다. |

세 선택지는 모두 하나의 Android 로그인 공급자와 하나의 내부 principal 매핑만 추가하는 동일한 범위 단순성을 가진다. 현재 자료에는 특정 공급자가 STOG의 대상 사용자에게 더 적합하다는 근거가 없다.

## 최소 권고안 하나

**제안됨, 아직 선택하지 않음: 사용자가 대상 사용자와 운영 계정 선호를 확인한 뒤, 그 선호에 맞는 위 세 공급자 중 하나만 첫 공급자로 채택한다.** 선택한 하나는 문서화된 Android 로그인 흐름을 기존의 단일 내부 principal에 연결하는 최소 범위를 유지한다. 현재 근거만으로 Kakao, NAVER 또는 Google 중 하나를 우선 추천하지 않는다. 대상 사용자의 계정 선호와 실제 운영 소유권이 확인되기 전에는 모든 선택지를 `UNDECIDED`로 둔다.

선호가 확인된 공급자도, 실제 소유자 계정의 정확한 약관·한도와 지출 0원 차단 수단을 독립적으로 확인할 때까지는 차단 상태다. 이를 확인할 수 없으면 저장소 규칙상 명시적 사용 불가/오류 상태를 보이고 연결하지 않는다. 유료 대안은 허용되지 않는다.

## 승인된 공급자에 공통인 provisioning·연결 보호책

1. 유효하게 검증한 공급자 assertion을 받으면 외부 identity를 변경 불가한 **`(issuer, subject)`**로 키로 삼고, 정확히 하나의 서버 생성 내부 사용자 UUID에 매핑한다. body/header의 `user_id`는 받지 않는다.
2. 승인된 흐름에 적용되는 issuer, audience/client binding, signature/key material, expiry, nonce/state/PKCE를 인증 경계에서 검증한 뒤에만 provisioning한다. 공급자 SDK/token을 domain authorization 수단으로 사용하지 않는다.
3. email, 표시 이름, 전화번호, profile image로 자동 연결하지 않는다. 기존 계정 연결에는 양쪽 identity의 제어권을 증명하는 명시적으로 승인된 흐름이 필요하며, 충돌·복구·연결 해제 결과는 먼저 사용자가 결정해야 한다.
4. 승인된 최소 identity mapping과 lifecycle metadata만 저장한다. access/refresh token, client secret, cookie, 원본 공급자 profile data를 source, 일반 log, fixture, screenshot, evidence에 넣지 않는다(`AGENTS.md`, `보안·정합성·복구`).
5. 로그아웃, 연결 해제, 삭제, 공급자 장애, token 철회, 분실 기기 복구는 실패-차단으로 동작하고 기존 401/비노출 경계를 보존해야 한다. 이 보고서는 callback, 서버 노출, Android UI를 승인하지 않는다.

## 구현 TASK 전 사용자·소유자가 답할 결정

1. 공급자 하나를 승인하거나 세 선택지 모두 거절할지, 그리고 첫 대상 사용자가 Kakao, NAVER, Google 계정 중 어느 계정 사용자인지 결정한다.
2. 공급자/개발자 프로젝트의 법적·운영 소유자와 billing, quota, redirect, signing, deletion 설정을 변경할 권한자를 지정한다.
3. 선택한 제품의 의도한 사용량에 대한 정확한 API 비용 0원 조건, 해당 한도, 유료 사용을 막는 조치를 자격 증명이 아닌 검증 근거로 제공한다. Google은 Identity Platform을 범위에 포함하는지 별도로 답한다.
4. 최초 로그인 정책을 자동 내부 사용자 provisioning, 초대/승인 전용, 연결 전용 중 하나로 결정한다. 충돌 처리, 계정 복구, 연결 해제, 삭제, 보관 identity 필드·기간도 결정한다.
5. 세션 계약을 서버 발급 짧은 수명의 access token과 회전 refresh token, server session, 또는 다른 명시된 방식 중 하나로 결정한다. 만료, 철회, 로그아웃, 저장 경계, Android-서버 credential 전달을 포함한다.
6. callback/redirect 소유자를 지정하고 필요한 비운영 테스트 환경을 승인한다. 답변에 secret 값은 포함하지 않는다.

## 출처와 읽기 기반 QA

공식 페이지의 기존 확인일은 **2026-09-25**이다. 기존 비교 근거의 Context7 검색은 Kakao Developers 및 NAVER Android SDK 문서 항목을 대상으로 했으며, 공급자별 주장은 색인보다 권위가 높은 각 공급자의 공식 페이지를 인용한다.

- [Kakao Login Android 안내][kakao-android]
- [Kakao Login 개념][kakao-concepts]
- [NAVER ID Login Android 안내][naver-android]
- [Android Developers: Sign in with Google / Credential Manager][google-android] (페이지의 최종 갱신 표기는 2026-09-16 UTC)
- [Google Cloud: Identity Platform 가격][google-pricing]
- 프로젝트 gate: `AGENTS.md`, `README.md`, `docs/PLAN.md:35,64,68-70`, `docs/BACKEND.md`, `docs/API.md`의 인증 경계, 그리고 이 artifact 디렉터리의 기존 `policy.md`, `readiness.md`.

**읽기 기반 QA: 통과.** 비용과 platform 관련 주장은 현재 공식 URL을 인용하거나 명시적으로 미검증으로 표시했다. 실제 STOG 소유자 계정의 비용·quota·billing 차단은 어떤 공개 무료 등급 주장과도 구분했다. 권고안은 `PROPOSED`일 뿐 승인이나 공급자 선택이 아니다. provider/account 연결, credential 생성, 코드, canonical 문서, test, build 변경은 없었다.

[kakao-android]: https://developers.kakao.com/docs/latest/en/kakaologin/android
[kakao-concepts]: https://developers.kakao.com/docs/latest/en/kakaologin/common
[naver-android]: https://developers.naver.com/docs/login/android/android.md
[google-android]: https://developer.android.com/identity/sign-in/credential-manager-siwg
[google-pricing]: https://cloud.google.com/identity-platform/pricing
