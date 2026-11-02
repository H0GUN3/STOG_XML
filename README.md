# STOG Android 개발 루트

이 디렉터리는 실제 개발 루트다. Android bootstrap, authenticated travel foundation과 native username/password auth + real Home/My Trips entry는 완료된 baseline이다. Native backend/V7/full gate/retained loopback runtime과 실제 Pixel signup → Home → create/detail → My Trips → logout → relogin persistence evidence를 보존한다. 2026-11-02 최신 사용자 승인에 따른 현재 단일 작업 슬롯은 [NATIVE-AUTH-UI-001](docs/tasks/NATIVE-AUTH-UI-001.md)의 기존 core-flow/layout repair이며 `PENDING / CHECKS NOT_RUN`이다. 기존 Kakao identity/data와 historical local evidence는 보존하지만 current app flow는 외부 로그인을 사용하지 않는다. 이전 `MVP-TRIP-005` R3c ranked Place search는 계약·DB prerequisite·behavioral RED를 보존한 채 후속 V8 작업으로 deferred다. Instagram은 design only, Mapbox는 비용·계정·무과금 통제 검증과 별도 지도 작업 승인이 필요한 조건부 대상으로 실제 연결하지 않는다. 이는 STOG 전체 제품 구현이나 public release 승인을 의미하지 않는다.

## 기준과 읽는 순서

변경 전에 다음 순서로 읽는다.

1. 현재 작업에 대한 최신 명시적 사용자 결정
2. 제품 기준 원문인 [STOG Android App Project Specification v1.4](reference/STOG_Development_Starter_v0.1/docs/reference/STOG_Android_App_Project_Specification_v1.4.docx)
3. 저장소 공통 시작·검증·인계 규칙 [AGENTS.md](AGENTS.md)
4. 프로젝트 전체 completion/backlog의 단일 원장 [docs/PLAN.md](docs/PLAN.md). 과거 기록은 필요할 때만 [docs/PLAN_HISTORY.md](docs/PLAN_HISTORY.md)를 읽는다.
5. 현재 TASK, 이어서 변경 영역의 [docs/BACKEND.md](docs/BACKEND.md), [docs/DATABASE.md](docs/DATABASE.md), [docs/API.md](docs/API.md), [docs/ANDROID.md](docs/ANDROID.md)

PLAN은 프로젝트 상태를, PLAN_HISTORY는 이력을 관리한다. `.omo/wiki/`는 설계 이유·오류 원인·검증 근거를 모은 파생 자료다. TASK는 한 변경의 contract, scope, acceptance, evidence를 기록한다. domain 문서는 각 영역의 규칙을 소유한다. 같은 전체 보고서를 여러 문서에 만들지 않는다.

활성 운영 문서 원본은 루트 README/AGENTS와 `docs/`다. `reference/STOG_Development_Starter_v0.1/`은 역사적 입력 자료이며 두 번째 활성 지침이 아니다. 그 안의 상대 링크는 과거 패키지 구조를 설명할 수 있다. 구현이 일치하는 것처럼 보이게 하려고 v1.4 DOCX를 수정하지 않는다.

[reference/](reference/)에는 원본 자산을 유지한다.

- `reference/STOG_APP_icon/`: 제공된 로고 원본. 존재 자체는 생성된 Android 리소스가 올바르다는 증거가 아니다.
- `reference/STOG_Development_Starter_v0.1/`: 최초 운영 제안과 규범적 v1.4 DOCX.

## 현재 상태

bootstrap 전 조사에서는 IDE 메타데이터와 참조 파일만 있었고 Android 앱 소스와 빌드 파일은 없었다. 현재 단일 app module과 Java/XML 최소 화면은 **IMPLEMENTED**이고, 실제 명령·device QA·review evidence가 기록된 bootstrap 범위는 **VERIFIED**다. M1 Place to TripItem basket server slice와 M1-003 current TripItem GET은 backend와 PostgreSQL에서 **IMPLEMENTED / VERIFIED**다. M1-002 historical suite는 27개, M1-003 시점 suite는 33개였다. M1-002의 retrospective TASK와 원래 검증 artifact를 함께 보존한다. 현재 Kakao local backend auth와 restricted-owner 시험 경계 회귀까지 포함한 PostgreSQL suite는 62개이며, 실제 Kakao의 local loopback login/logout/relogin도 제한된 범위에서 확인됐다. 기존 protected current TripItem GET은 별도의 synthetic-provider/generated-row fixture로 Android emulator -> real local backend -> PostgreSQL까지 확인됐다. 승인된 R1 trip-product backend/V3는 canonical JDK 17 PostgreSQL 전체 gate와 bootJar를 통과했다. Android root-cause correction으로 실제 UI test mapping, legacy-null parsing, bounded page-depth/process-anchor 복원과 real-backend test/harness가 추가됐고 변경 후 최종 exact gate는 JVM·instrumentation 24/24·Lint·assemble을 통과했다. 실제 local 통합 test의 EOF 대기 loss-proxy와 fixture 재부착·정리 결함을 수정한 뒤 정상 provider exchange, 인증 UI, create response-loss/replay, detail/empty·ID-only basket, recreation, system Back의 exactly-one list 복귀, access-expiry refresh, member logout, nonmember 비노출, 최종 signed-out과 crash scan을 실제 backend/PostgreSQL에서 통과했다. generated isolated role/schema와 owned runtime/settings/reverse cleanup도 확인되어 R1 증분은 `VERIFIED`다. 이어서 R2a collaboration/lifecycle V4/backend/Android의 PostgreSQL 91/91+bootJar와 generated normal-auth user 두 명의 Pixel_8 -> Spring -> isolated PostgreSQL 흐름을 통과했다. 멤버 목록 rows0의 RecyclerView LayoutManager 누락과 nested scroll 도달성을 수정한 뒤 최종 변경 Android gate는 JVM 16·instrumentation 33/33·Lint·assemble을 통과했다. font 1.30 portrait/landscape에서 실제 스와이프로 `나`와 `민지` 각각의 역할·제거 컨트롤을 90% 이상 노출해 탭하고 정확한 dialog를 취소하는 16개 화면을 검수했으며, emulator/settings/reverse/app cleanup과 crash scan도 통과해 R2a는 `VERIFIED`다. 원래 Android API/UI preimplementation behavioral RED 부재는 소급 복구하지 않았고, R2b private cover/V5도 corrected backend 111/111+bootJar, Android 44/44와 native-picker real-local E2E를 통과해 R2 전체가 LOCAL VERIFIED다. 이는 real Kakao와 protected domain을 결합한 production E2E, production HTTPS, public network 또는 제품 전체 기능의 완료를 뜻하지 않는다.

확정 기준:

- Android 앱 소스는 Java와 XML이며 Material components, Fragment, View Binding을 사용한다.
- 추가 비용은 0원이며 유료 fallback은 없다.
- v1.4가 제품 기준이고, 이후의 명시적 사용자 결정이 우선한다.
- 제안 순서는 M0 bootstrap -> M1 공통 기반 -> M2 Instagram -> M3 지도/기록이다. 의존 순서 제안일 뿐 좁은 MVP 승인이나 전체 단계 구현 허가가 아니다.

2026-09-22 질문 timeout 뒤 사용자가 best-judgment 계속 진행을 지시한 위임에 따라 개발 설정은 `com.stog.app`, `minSdk` 23, `targetSdk` 36, `compileSdk` 37, process/project-scoped JDK 17.0.11, AGP 9.3.2, Gradle 9.5.0, AppCompat 1.7.1, Fragment 1.8.9, Material 1.13.0으로 정했다. 앱 source는 Java/XML만 사용하고 전이 Kotlin runtime은 허용한다. 이는 항목별 명시 답변이나 store identity·소유권·배포 승인이 아닌 개발 기본값 결정이다. [QUALITY_OPERATIONS.md](docs/QUALITY_OPERATIONS.md)를 따른다.

## 현재 구조

```text
app/
  build.gradle
  src/main/
    java/com/stog/app/
    res/
gradle/wrapper/
settings.gradle
build.gradle
README.md
AGENTS.md
docs/
backend/
  src/main/java/com/stog/backend/
  src/test/java/com/stog/backend/
reference/
```

## 문서 역할

| 문서 | 단일 책임 |
| --- | --- |
| [AGENTS.md](AGENTS.md) | 모든 작업자가 지킬 구현·검증 규칙 |
| [PLAN.md](docs/PLAN.md) | 현재 상태, 선행조건, 결정, 다음 작업 제안 하나 |
| [PLAN_HISTORY.md](docs/PLAN_HISTORY.md) | 과거 결정, 완료 슬롯, 작업 이력 |
| [ANDROID.md](docs/ANDROID.md) | Android 책임, UI 상태, lifecycle, threading, 기술 도입 조건 |
| [QUALITY_OPERATIONS.md](docs/QUALITY_OPERATIONS.md) | 환경 표, 실제 명령/증거, 보안, 의존성, 재구성·복구 |
| [BACKEND.md](docs/BACKEND.md) | 확인된 서버 책임 경계만 |
| [DATABASE.md](docs/DATABASE.md) | 확인된 데이터 불변조건과 후속 계약만 |
| [API.md](docs/API.md) | 확인된 공통 통신 제약만 |
| [tasks/](docs/tasks/) | 작업 하나의 범위, 경로, 결정, 수용 기준, 증거, blocker |

## 실행과 검증 절차

그럴듯한 명령을 예시로 적지 않는다. 실제 실행한 명령만 `docs/QUALITY_OPERATIONS.md`와 현재 TASK에 기록한다. 검증 범위는 현재 TASK에 맞춘다.

1. 문서 작업은 상대 링크, 상태, evidence 경로와 scope만 점검한다.
2. backend 작업은 변경된 controller/service와 관련 JUnit 및 필요한 backend packaging을 검증한다. M1-002의 실제 runner 명령과 27개 통과 결과는 [final-verification.json](.omo/artifacts/m1-foundation-002/final-verification.json)에 기록되어 있으며, 새 실행을 뜻하지 않는다.
3. backend 또는 docs만 변경할 때 이미 VERIFIED인 Android bootstrap을 다시 build, Lint, install, device QA하지 않는다. Android 변경 TASK가 있을 때만 Android gate를 적용한다.
4. 외부 provider, DB 운영, deployment, device 검증은 해당 TASK가 명시한 경우에만 실행한다.
5. 관찰 증거가 있는 수용 기준만 IMPLEMENTED에서 VERIFIED로 변경한다.

현재 bootstrap 검증은 완료된 범위와 제한된 미검증 범위를 구분한다. Wrapper `assembleDebug`, `lintDebug`, `testDebugUnitTest`는 root와 새 source copy, 새 Gradle user home에서 exit 0이었다. 최종 clean-copy APK는 Pixel_8 emulator-5554, API 36.1에 설치·실행했고 portrait, landscape/recreation, large-font, Back, warm relaunch와 빈 crash scan을 통과했다. 테스트 소스는 없어서 `testDebugUnitTest`는 `NO-SOURCE`였다. `verification-summary.md`가 dependency, secret, ignore, source-copy 및 최종 build/device evidence 검토를 PASS로 확인했다. API 23/37 runtime, physical device/instrumentation test, external service는 실행하지 않았다. 상세 증거는 [QUALITY_OPERATIONS.md](docs/QUALITY_OPERATIONS.md)와 [ANDROID-BOOTSTRAP-001](docs/tasks/ANDROID-BOOTSTRAP-001.md)에 있다.

## 현재 작업 진입 조건

아래는 완료된 foundation과 restricted-owner 준비의 역사적 진입 기록이다. 현재 단일 슬롯은 위의 native task 내 core-flow/layout repair다.

`ANDROID-BOOTSTRAP-001`과 `M1-SHARED-FOUNDATION-002`의 완료 evidence는 보존한다. M1-002는 [retrospective TASK](docs/tasks/M1-SHARED-FOUNDATION-002.md)에서 원래 범위와 evidence를 역사 기록으로 연결한다. `M1-SHARED-FOUNDATION-003`은 [docs/PLAN.md](docs/PLAN.md)와 [TASK](docs/tasks/M1-SHARED-FOUNDATION-003.md)에 기록된 범위대로 완료됐다. 실제 Kakao loopback login/logout/relogin은 local runtime으로 기록됐고, 기존 TripItem GET의 Android local integration도 literal-loopback synthetic provider와 generated isolated fixture 범위에서 완료됐다. real Kakao와 protected domain을 결합한 production E2E, production HTTPS, full REST synchronization, real SSE transport는 완료로 표시하지 않는다. 현재 단일 슬롯인 restricted-owner backend 시험 모드는 구현·local 검증됐고, 사용자 확인 실제 QA 계정의 단일 mapping을 same-user DPAPI operator 설정에 고정한 `-RestrictedOwner` loopback 시작도 검증됐다. 실제 owner bearer 인증 요청은 `NOT_RUN`이며 item 2와 external/public 실행은 승인되지 않았다.
