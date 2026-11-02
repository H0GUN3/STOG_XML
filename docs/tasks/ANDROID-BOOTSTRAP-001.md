# ANDROID-BOOTSTRAP-001: Android 프로젝트 bootstrap과 운영 기준 확립

## 1. 작업 식별과 상태

| 항목 | 값 |
| --- | --- |
| 단계 | M0 환경/bootstrap |
| 제품 기준 | `reference/STOG_Development_Starter_v0.1/docs/reference/STOG_Android_App_Project_Specification_v1.4.docx` |
| 목표 | 재현 가능한 최소 Android 프로젝트와 하나의 권위 있는 운영 문서 체계 확립 |
| 작업 상태 | DONE |
| 승인 상태 | 환경/bootstrap/문서까지 CONFIRMED, 전체 제품 구현은 미승인 |
| 구현 상태 | 범위 내 app/build와 운영 문서 IMPLEMENTED |
| 검증 상태 | VERIFIED |
| 담당 / 검토 | 미지정 / 미지정 |

**CONFIRMED**는 확정 요구·결정, **PROPOSED**는 미승인 제안, **UNDECIDED**는 미결정, **IMPLEMENTED**는 변경 존재, **VERIFIED**는 실행·관찰 증거 있음, **NOT_RUN**은 실행 증거 없음이다.

## 2. 목적

STOG 제품 기능을 구현하지 않으면서, 승인된 식별자와 도구 설정을 사용하는 단일 app module, Gradle Wrapper, 실제 Java/XML 최소 화면, 재현 가능한 build·검증 절차, 하나의 운영 문서 원본을 만든다.

## 3. 범위

### 포함

- 현재 개발 루트를 조사하고 v1.4 DOCX와 logo asset을 `reference/`에 보존한다.
- starter README, AGENTS, PLAN, TASK template의 유효 규칙을 root/`docs/` 활성 문서로 승격하고 reference 원본은 역사 자료로 표시한다.
- 위임된 개발 설정의 Application ID/namespace, SDK/toolchain, Kotlin runtime 정책을 실제 project 설정에 반영한다.
- 단일 Android app module과 Gradle Wrapper를 만들고 Java/XML, Material components, Fragment, View Binding으로 실제 표시되는 최소 화면을 구성한다.
- project-local ignored 설정, dependency·secret 검토, clean reconstruction과 build/Lint/test/install/launch 절차 및 결과를 기록한다.
- 이름이 기록된 emulator/device에서 설치·실행하고 최소 화면과 해당되는 recreation/back 동작을 확인한다.

### 제외

- 전체 STOG 제품, backend/server, DB schema/DDL, OpenAPI, 배포, public service 구현.
- Store identity·소유권·publication 결정과 개발 설정을 같은 승인으로 취급하는 행위.
- 위치 권한/service/queue, 지속 기록, camera/media, Room, WorkManager, Mapbox, Android H3, routing, Gemini/STOBEE, Instagram 추출, social provider 연동.
- 유료 fallback, public server/port 가정, credential, live 외부 호출, 과거 표본을 배포 증거로 사용하는 행위.
- 추측성 module, DI, plugin/provider infrastructure, 빈 Repository, 무관한 redesign.

## 4. 결정과 선행조건

### 확정

- Android source는 Java/XML이며 Material components, Fragment, View Binding을 사용한다.
- 추가 비용은 0원이고 유료 fallback은 없다.
- 공통 `Place`를 재사용하며 바구니/일정은 같은 `TripItem`의 상태다.
- 계획 변경은 `plan_version`, `request_id`, transaction, commit 후 SSE 관계를 유지한다.
- CELL은 실제 camera/session 방문만 활성화하며 link/search/basket으로 활성화하지 않는다.
- 비공개 여행 media는 작성자와 여행 구성원 authorization이 필요하다.
- H3는 server-only다.
- 최신 명시적 사용자 결정이 없으면 v1.4가 기준이다. 부록 D의 Instagram 표본 두 개는 배포 구현 증거가 아니다.
- 제안 순서는 M0 -> M1 공통 기반 -> M2 Instagram -> M3 지도/기록이며 좁은 MVP는 승인되지 않았다.
- 2026-09-22 질문 timeout 뒤 사용자가 best-judgment 계속 진행을 지시한 위임에 따라 개발 설정을 `applicationId`/namespace `com.stog.app`, app label `STOG`, `minSdk` 23, `targetSdk` 36, `compileSdk` 37, process/project-scoped JDK 17.0.11, AGP 9.3.2, Gradle 9.5.0, AppCompat 1.7.1, Fragment 1.8.9, Material 1.13.0으로 정했다. Java/XML source만 작성하고 전이 Kotlin runtime은 허용한다. 항목별 명시 선택이나 store identity·소유권·publication 승인은 아니다.
- AppCompat/Fragment/Material은 실제 Material theme와 Fragment host에 필요한 dependency다. 실제 필요가 생기기 전에는 ViewModel/Repository source를 추가하지 않는다.

### 확인된 환경

- Windows `10.0.26200.7840` x64. 완전한 Android Studio Quail 3 `2026.1.3`은 `C:/Program Files/Android/Android Studio1`에 있다.
- Oracle JDK 17.0.11이 설치되어 있다. Global Java는 Temurin 25.0.4.1이며 변경하지 않는다. Studio JBR 25.0.2도 있다.
- SDK에는 platform 37, Build Tools 36.0.0, Platform Tools 37.0.1, emulator 37.1.11이 있다. `Pixel_8` API 36.1 Google Play x86_64 AVD는 중지 상태였고 연결 기기는 없었다.
- `sdkmanager`, `avdmanager`, PATH Gradle은 없었다. Cached Gradle 9.5.0은 실행 가능했고 AGP 9.3.2 cache는 완전했지만 프로젝트 승인은 아니다.

### 실행 증거 확보

- 실제 source 경로는 `app/src/main/java/com/stog/app/`, `app/src/main/AndroidManifest.xml`, `settings.gradle`, `app/build.gradle`, Gradle Wrapper다. 단일 `:app` module과 Java/XML Material Fragment/View Binding 최소 화면을 source-copy 검토에서 확인했다.
- root와 fresh source copy, fresh Gradle user home에서 process-local JDK 17.0.11로 Wrapper `assembleDebug lintDebug testDebugUnitTest`가 exit 0이었다. 최초 clean reconstruction timeout과 direct `downloads.gradle.org` 회복은 artifact logs에 보존했다.

## 5. 변경 경로

### 현재 실제 문서 경로

| 경로 | 변경 |
| --- | --- |
| `README.md`, `AGENTS.md` | 활성 진입점과 저장소 가드레일 |
| `docs/{PLAN,ANDROID,QUALITY_OPERATIONS,BACKEND,DATABASE,API}.md` | 계획과 도메인 운영 경계 |
| `docs/tasks/{_TEMPLATE,ANDROID-BOOTSTRAP-001}.md` | 작업 template과 현재 계약 |
| `reference/STOG_Development_Starter_v0.1/{README.md,AGENTS.md,docs/PLAN.md,docs/tasks/_TEMPLATE.md}` | 역사 자료 banner와 활성 문서 link; 본문 보존 |

### Bootstrap code/build 경로 - VERIFIED

`settings.gradle`, `gradle/wrapper/`, `app/build.gradle`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/stog/app/`, `app/src/main/res/`, `.gitignore`, `local.properties`가 범위 내 경로다. 128 authored files 일치는 earlier snapshot으로 구분하며, 최종 verification에서 root Gradle/config/wrapper와 app build/source/resources 22 executable inputs를 `reconstruction-final/source`와 비교해 누락·불일치 0건을 확인했다. 이후 operational Markdown만 수정했으며 tested build inputs와 동일성을 유지한다.

## 6. 수용 기준

### 운영 문서 - 완료

- [x] root와 `docs/`에 활성 운영 문서 set 하나가 있고 starter package는 역사 자료로 명시된다.
- [x] README에 읽는 순서, reference asset, 증거 기반 실행 절차, 다음 작업 gate가 있다.
- [x] AGENTS에 승인 범위, 0원, Android stack, 비추측, 보안·정합성·멱등성, 증거 규칙이 있다.
- [x] PLAN은 상태를 구분하고 다음 작업을 정확히 하나만 제안한다.
- [x] Android와 품질 문서는 lifecycle/state/threading/resource, 환경, 보안, dependency, 재구성·복구 경계를 다룬다.
- [x] BACKEND/DATABASE/API는 확인된 책임·제약·후속 항목만 담고 구현을 꾸며내지 않는다.

### Android bootstrap 전체 결과 - VERIFIED (범위 내)

- [x] Gradle Wrapper를 포함하고 Android application module이 정확히 하나이며 추측성 module이 없다.
- [x] 실제 실행되는 최소 화면이 Java/XML, Material components, Fragment, View Binding을 사용하고 `onDestroyView`에서 binding을 정리한다.
- [x] 위임된 Application ID/namespace, SDK/JDK/AGP/Gradle/library 값, Kotlin runtime 정책이 실제 설정과 문서에서 일치한다.
- [x] Wrapper 기반 `assembleDebug`가 성공하고 정확한 명령, 환경, output/artifact가 기록된다.
- [x] Android Lint가 성공하고 정확한 명령과 report/output이 기록된다.
- [x] `testDebugUnitTest` 명령은 exit 0이고 `NO-SOURCE`를 기록한다.
- [ ] Unit test suite와 instrumentation test 실행 검증은 `NOT_RUN`이다.
- [x] clean-copy debug artifact를 Pixel_8 emulator-5554 API 36.1에 설치하고 entry 화면을 launch해 crash 없이 확인했다.
- [x] portrait, landscape/recreation, large-font, Back, warm relaunch를 실제 surface에서 확인하고 설정을 복원했다.
- [x] Direct/transitive dependency, Kotlin runtime 영향, source-copy, ignore와 source/resource/log/evidence의 secret 노출을 검토했다.
- [x] 새 source copy와 새 Gradle user home에서 global Gradle/IDE cache 없이 Wrapper build 절차를 실제 검증했다.

## 7. 실제 증거

| 검사 | 상태 | 증거 |
| --- | --- | --- |
| 초기 root 조사 | VERIFIED | bootstrap 전 IDE metadata와 `reference/`만 있었고 app source/build file이 없었음 |
| Starter 문서와 v1.4 관련 절 검토 | VERIFIED | 지정된 starter 4개와 v1.4 1, 4-6, 8-9, 11-14절·부록 D; DOCX SHA-256 `682f4a124c10d627dfeda2363a52545596a3abb69e6f5991af970a9a8907e4ee` |
| 활성·역사 Markdown 상대 link / PLAN 단일 제안 | VERIFIED | Markdown 14개 검사, 깨진 상대 link 0개, 다음 작업 section 1개, `M1-SHARED-FOUNDATION-001` 제안 heading 1개 |
| 최종 active-doc update | VERIFIED | `README.md`, `AGENTS.md`, `docs/PLAN.md`, `docs/QUALITY_OPERATIONS.md`, 이 TASK에 실제 evidence와 범위 제한을 반영 |
| App module / Wrapper / 최소 화면 | VERIFIED | 단일 `:app`, Java/XML Material Fragment/View Binding. 최종 22 executable build inputs source-copy equivalence PASS |
| `assembleDebug` | VERIFIED | root와 clean-copy Wrapper 실행 exit 0 |
| Lint | VERIFIED | clean-copy `lintDebug` exit 0, HTML/SARIF reports 기록 |
| Test command | VERIFIED | `testDebugUnitTest` exit 0, `NO-SOURCE`, zero tests |
| Unit test suite / instrumentation test | NOT_RUN | 실행할 테스트가 없고 instrumentation test는 실행하지 않음 |
| Install / launch / recreation / back | VERIFIED | clean-copy APK 설치 후 Pixel_8 emulator-5554 API 36.1 QA PASS |
| Dependency / secret / 재현 build 검토 | VERIFIED | `verification-summary.md` PASS, dependency log와 fresh Gradle user home build 기록 |
| 외부 service/account | NOT_RUN / 범위 밖 | 제품 기능과 external service는 이 TASK에 포함하지 않음 |

## 8. 결과와 인계

- `verification-summary.md`의 bounded bootstrap PASS로 이 TASK의 수용 기준은 완료됐다. clean-copy APK는 `.omo/artifacts/android-bootstrap/reconstruction-final/source/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `67436050dc85c7f2dd0d9ce928bb4029ca55d4020d07f89f35086e9fd3e58860`이다. root APK와 hash가 달라 byte-for-byte 동일성을 주장하지 않는다.
- 테스트 소스 없음, API 23/37 runtime, physical device, instrumentation test, product behavior, external service, release signing은 미검증으로 유지한다. standalone Java/LSP diagnostics는 실행하지 않았으며 JDK 17 Gradle compilation과 Android Lint가 authoritative evidence다.
- 다음 작업 제안은 PLAN에 있는 `M1-SHARED-FOUNDATION-001` 하나이며 아직 미승인이다.
