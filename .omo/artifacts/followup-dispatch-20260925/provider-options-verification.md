# 인증 공급자 선택지 검증 보고서

## RESULT

**PASS(범위 제한) / 비용·계정 조건은 UNVERIFIED.** 2026-09-25에 인용된 공식 URL에 접근할 수 있었고, 문서에 적힌 Android 기능 주장은 해당 출처와 일치한다. 그러나 어떤 출처도 STOG 운영 계정의 자격, quota, billing 상태 또는 유료 사용을 확실히 차단하는 설정을 입증하지 않는다. 따라서 사용자별 비용 조건은 UNVERIFIED로 유지한다.

## EVIDENCE

| 검증 항목 | 결과 | 근거 |
|---|---|---|
| Kakao Android 기능·호환성 주장 | **PASS(문서화된 흐름에 한함)** | [Kakao Android Login 안내](https://developers.kakao.com/docs/latest/en/kakaologin/android)는 Android Login과 redirect intent/custom `kakao{NATIVE_APP_KEY}://oauth` scheme을 문서화한다. [Kakao Login 개념](https://developers.kakao.com/docs/latest/en/kakaologin/common)은 OAuth 2.0 access/refresh token과 선택적 OIDC ID token을 설명한다. |
| NAVER Android 기능 주장 | **PASS(문서화된 SDK 기능에 한함)** | [NAVER ID Login Android 안내](https://developers.naver.com/docs/login/android/android.md)는 Android SDK의 로그인·로그아웃·token 관리를 설명하고 Open API 신청 필요를 명시한다. 예제가 Kotlin이라는 점은 Java/XML 통합 지원의 증거가 아니며, 그 호환성은 **UNVERIFIED**로 올바르게 남아 있다. |
| Google Android 기능 주장 | **PASS(문서화된 흐름에 한함)** | [Android Sign in with Google / Credential Manager](https://developer.android.com/identity/sign-in/credential-manager-siwg)는 Credential Manager를 통한 Sign in with Google과 backend ID-token 처리를 문서화한다. 이는 저장소의 완성된 통합 또는 모든 설정과의 호환성을 증명하지 않는다. |
| `minSdk 23`과 공급자 SDK 버전의 호환성 | **UNVERIFIED** | 프로젝트의 `minSdk 23`은 저장소 제약일 뿐이며, 위 공식 페이지는 STOG의 특정 SDK 버전과 `minSdk 23` 조합을 검증하지 않는다. |
| Google Identity Platform 가격 주장 | **PASS(출처 범위에 한함)** | [Google Identity Platform 가격](https://cloud.google.com/identity-platform/pricing)은 Identity Platform의 MAU 가격과 무료 할당량을 문서화한다. 이는 Credential Manager만 사용하는 경우의 비용이나 STOG 소유자의 billing 차단을 증명하지 않는다. |
| Kakao/NAVER/Google의 STOG별 비용·무료 조건 | **UNVERIFIED** | 검토한 공식 출처 어디에도 실제 소유자 계정의 이용 자격, quota, billing 상태, 한도 초과 동작 또는 0원 지출 차단 수단이 없다. 공개 무료 등급은 사용자별 billing 증거가 아니다. |
| 권고 및 공급자 선호 | **PASS** | `provider-options.md`의 권고는 `PROPOSED`이며, 대상 사용자 계정 선호와 운영 소유권이 확인되기 전 모든 선택지를 `UNDECIDED`로 둔다. 특정 vendor를 선호하거나 대상 사용자 적합성을 주장하지 않는다. |
| 승인·구현 주장 여부 | **PASS** | 보고서는 공급자 승인, 프로젝트 연결, credential 생성, provisioning, 코드 구현을 주장하지 않는다. `docs/PLAN.md:68-70`도 provider/login/provisioning을 `UNDECIDED / NOT_RUN` 및 `PROPOSED / BLOCKED`로 둔다. |

## REMAINING PROBLEMS

1. 모든 선택지의 사용자별 billing 자격, quota, 현재 billing 상태, 유료 사용 방지 수단이 미검증이다.
2. NAVER의 Java/XML 통합 경로와 `minSdk 23` 조합을 승인 전에 별도로 확인해야 한다.
3. Kakao redirect 소유권·충돌 처리와 서버 token 검증은 설계 미결정이다.
4. Google에서 Credential Manager만 사용할지 Identity Platform을 포함할지, 프로젝트 소유권과 billing 통제를 결정해야 한다.
5. 대상 사용자와 운영 계정의 증거가 없으므로 어느 vendor도 선호·승인되지 않았다. 연결·provisioning·구현은 수행되지 않았다.
