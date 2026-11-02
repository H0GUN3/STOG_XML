# STOG 저장소 작업 가드레일

사람과 자동화 작업자 모두에게 적용한다.

## 모든 작업의 시작과 인계

작업 시작 전에 다음 순서로 읽는다. 최신 사용자 지시, canonical checklist인 [docs/PLAN.md](docs/PLAN.md), PLAN이 가리키는 현재 TASK, [.omo/wiki/index.md](.omo/wiki/index.md)에서 관련 있는 페이지, 그리고 변경 영역의 domain 문서(`BACKEND.md`, `DATABASE.md`, `API.md`, `ANDROID.md`)다. 긴 문서는 제목을 보고 이번 작업에 필요한 구역만 읽으며, 판단이 서지 않을 때만 전체를 읽는다. 저장소 구조나 절차를 모를 때 `README.md`를, 과거 결정과 증거가 필요할 때 [docs/PLAN_HISTORY.md](docs/PLAN_HISTORY.md)를 읽는다. 위키는 파생 자료이므로 원본 문서와 다르면 원본을 따른다. PLAN의 `[x]`, 상태 용어, evidence link를 먼저 확인하고, 완료된 작업을 다시 만들지 않는다. 현재 TASK가 정한 scope와 exclusions를 지키며, PLAN의 단일 현재 작업 슬롯에 없는 기능이나 provider, policy를 발명하지 않는다.

시작 전에 이전 evidence가 실제 파일과 맞는지 확인한다. `VERIFIED`는 실행 결과가 있는 경우에만 유지하고, 실행하지 않은 명령을 완료로 쓰지 않는다. domain 문서는 각 규칙의 권위 원본이고 PLAN은 상태를, PLAN_HISTORY는 history를 관리하므로, 같은 상세 보고서를 병렬 문서에 복사하지 않는다.

작업을 마치거나 다음 작업자에게 넘길 때 현재 TASK의 범위, 변경 경로, 결정, acceptance, 실제 evidence, 실패·`NOT_RUN`, blocker를 기록하고 PLAN의 체크리스트와 PLAN_HISTORY의 chronological work entry를 갱신하고, 재사용할 설계 이유·오류 원인·검증 근거는 위키에 반영한다. 구현이 없는 문서 작업도 `IMPLEMENTED`와 `VERIFIED`를 구분한다. 테스트나 build를 실행하지 않았다면 `NOT_RUN`으로 남기며, 다음 작업 슬롯은 정확히 하나만 유지한다.

## 기준 원본과 상태 용어

- 최신 명시적 사용자 결정이 v1.4보다 우선하고, 그 외 제품 기준은 `reference/STOG_Development_Starter_v0.1/docs/reference/STOG_Android_App_Project_Specification_v1.4.docx`다.
- 역사적 starter Markdown과 부록 D 보고서는 현재 정책을 덮어쓰지 않는다. 보고된 Instagram 표본 두 개는 배포·재현 가능한 구현의 증거가 아니다.
- **CONFIRMED**는 확정 요구·결정, **PROPOSED**는 미승인 제안, **UNDECIDED**는 미결정, **IMPLEMENTED**는 파일·동작 존재, **VERIFIED**는 관찰 증거가 있는 검증, **NOT_RUN**은 실행 증거 없음이다. 서로 바꾸어 쓰지 않는다.

## 현재 범위와 기술

- Android bootstrap과 완료된 backend foundation은 보존한다. 현재 작업은 PLAN의 단일 current TASK scope만 진행한다. 이는 전체 제품 구현 승인이나 다른 provider, Android 기능, full REST/SSE 기능의 blanket approval이 아니다.
- Android 앱 소스는 **Java + XML**이다. Material components, Fragment 화면, View Binding을 사용하며 Kotlin 또는 Compose 앱으로 전환하지 않는다.
- 2026-09-22 timeout 후 best-judgment 계속 진행 위임에 따른 개발 설정은 `applicationId`/namespace `com.stog.app`, `minSdk` 23, `targetSdk` 36, `compileSdk` 37, process/project-scoped JDK 17.0.11, AGP 9.3.2, Gradle 9.5.0, AppCompat 1.7.1, Fragment 1.8.9, Material 1.13.0이다. Java/XML source만 작성하고 전이 Kotlin runtime은 허용한다. 항목별 명시 선택이나 store identity·소유권·배포 승인을 뜻하지 않는다.
- 추측성 feature module, DI framework, plugin/provider infrastructure, event bus, 범용 service/repository 계층, 미래 대비 추상화를 추가하지 않는다. 실제 데이터 접근이 있을 때만 Repository를 만들며 빈 계층을 만들지 않는다.
- AppCompat, Fragment, Material은 실제 Material theme와 Fragment host에 필요한 범위로 사용한다. 실제 상태·data access가 생기기 전에는 추가 ViewModel/Repository source를 만들지 않는다.
- 범위 밖 redesign, package 이동, style 정리, 대규모 refactoring을 섞지 않는다.

## 단순화해도 지킬 제품 불변조건

- 공통 `Place`를 재사용한다. 검색, marker, 상세, import 후보 확인, 여행 저장을 공급자별 Place 저장소로 나누지 않는다.
- 바구니와 일정은 같은 `TripItem`의 상태다. 이동 시 복사본을 만들거나 실패 시 항목을 잃지 않는다.
- 계획 변경은 `plan_version` 덮어쓰기 방지, `request_id` 멱등성, 상태와 결과의 단일 transaction, commit 후 SSE를 유지한다. SSE는 원본이 아니며 재연결 시 REST로 다시 읽는다.
- CELL은 본인의 실제 STOG camera 기록 또는 사용자가 시작한 활성 기록 session에서 확인된 방문으로만 활성화한다. 검색·링크·import·바구니·gallery·타인 media 열람은 CELL/EXP를 만들지 않는다.
- 여행 비공개 media는 작성자와 해당 여행 구성원에게만 허용하고, 여행과 무관한 비공개 media는 본인만 본다. metadata, thumbnail, file, 대표 이미지에도 같은 규칙을 적용한다.
- H3 식별은 server-only다. Android H3 의존성을 추가하지 않는다.

## Android 구현 규칙

- Fragment는 `onCreateView`부터 `onDestroyView`까지만 binding을 소유하고 `onDestroyView`에서 해제한다. View, binding, adapter callback, view-lifecycle observer를 그 이후까지 보관하지 않는다.
- ViewModel은 복원 가능한 화면 상태를 소유하며 View, Activity, Fragment, 장기 Context를 소유하지 않는다. 실제 상태가 있을 때 LiveData로 노출하고 흩어진 widget flag 대신 상태를 렌더링한다.
- 해당 화면의 loading, content, empty, 복구 가능한 error/retry, 종결/권한 오류를 구분한다. 작업이 요구하는 선택 ID, 검색어·결과·scroll, 미확정 사용자 입력을 뒤로 가기와 재생성에서 보존한다.
- main thread는 rendering과 짧은 상태 전환에만 쓴다. network/disk/image 작업은 밖에서 수행하고 UI 상태는 main thread에 전달한다. 소유자가 끝나거나 새 요청이 이전 요청을 대체하면 취소하거나 오래된 결과를 무시한다.
- XML은 구조와 접근성 metadata, Java는 동작을 맡는다. 문자열, dimension, color, theme, 공통 style은 resource로 둔다. 자격정보, server 주소, UI 문자열, dimension을 source에 hard-code하지 않는다.
- list는 adapter가 소유하는 item model과 필요한 stable identity를 사용한다. row에는 전체 이미지 대신 제한된 thumbnail을 로드하고 누락/loading/error를 구분하며 비공개 URL을 노출하지 않는다.
- 위치 권한/service/queue 상세는 보류한다. CameraX, Room, WorkManager, Mapbox는 `docs/ANDROID.md`의 승인된 도입 조건에 도달한 작업에서만 추가한다.

## 범위·공급자·비용 통제

- 추가 비용은 0원이다. Mapbox, Gemini/STOBEE, routing, Instagram 자동화, social/provider 연결 전 계정 조건과 과금 차단을 검증한다. 무료 운영을 확인할 수 없으면 명시적 오류와 함께 연결하지 않으며 유료 fallback은 없다.
- 별도 승인·검증된 운영 작업 없이 public server, worker, DB, callback, hostname, address, port를 노출하지 않는다.
- Instagram은 공통 기반 다음 첫 외부 링크 연동이다. Instagram feed·개인 보관함·전용 Place DB·개인 login session 복사·접근 제한 우회·OCR/comment/audio 확대·자동 LLM fallback을 만들지 않는다.
- Redis, Kafka, Elasticsearch, vector DB, microservice, Kubernetes, 공급자 병렬 구현은 제외한다.

## 보안·정합성·복구

- 작업을 줄이기 위해 authorization, 비공개 media filtering, 입력/URL 검증, SSRF/network 통제, transaction, idempotency, 상한, cancellation, 보관 검토, recoverability를 생략하지 않는다.
- 외부 text는 data로 취급한다. token, cookie, 개인 GPS, 비공개 media, raw caption, credential을 source, 일반 log, fixture, screenshot, evidence에 넣지 않는다.
- `docs/QUALITY_OPERATIONS.md`에 정한 ignored local developer config만 사용한다. 예시는 변수명과 설명만 담고 자격정보 값을 담지 않는다.
- 적용된 migration을 다시 쓰거나 shared DB를 init/clean/drop하거나 보관 데이터를 삭제하거나 API/DB 성공을 꾸며내지 않는다. 명시적 TASK 승인 전 backend/database/server 운영은 보류한다.

## 작업과 증거 규칙

- 모든 변경은 범위, 제외, 변경 경로, 결정, 수용 기준, 증거, blocker를 포함한 TASK 하나에 연결한다. 제품 동작 확대나 dependency/provider/schema/process 추가는 그 TASK에 명시적 승인을 기록한다.
- 허용된 명령만 실행하고 정확한 명령·환경·결과·증거 경로를 보고한다. `NOT_RUN`, 실패, fixture, mock을 표시하며 문서 완료를 runtime 검증으로 바꾸지 않는다.
- 현재 bootstrap 증거는 `.omo/artifacts/android-bootstrap/`에 있다. root와 새 source copy, 새 Gradle user home에서 process-local `JAVA_HOME=/c/Program Files/Java/jdk-17`와 Wrapper를 사용한 `assembleDebug lintDebug testDebugUnitTest`가 exit 0이었다. `testDebugUnitTest`는 테스트 소스가 없어 `NO-SOURCE`이며 테스트 통과로 과장하지 않는다. direct Gradle 9.5.0 다운로드의 최초 timeout과 official `downloads.gradle.org` 회복 실행을 모두 보존한다.
- clean-copy debug APK만 device QA artifact로 취급한다. root APK와 clean-copy APK hash가 다르므로 동일 APK라고 쓰지 않는다. Pixel_8 emulator-5554 API 36.1에서 portrait, landscape/recreation, large-font, Back, warm relaunch와 빈 crash scan을 확인했으며 font scale, rotation, animation 설정은 복원했다. API 23/37 runtime, physical device/instrumentation test, external service는 실행하지 않았다.
- bootstrap 완료에는 단일 app module/Wrapper, 실제 Java/XML 최소 화면, 승인된 ID/SDK/Kotlin 정책, `assembleDebug`·Lint·test, 이름이 기록된 기기 install/launch와 해당되는 recreation/back, dependency·secret 검토, 재현 가능한 절차의 실제 증거가 필요하다.
- 완료 보고에는 변경 파일, 통과 기준과 증거, 실패/미실행, 보안·데이터·비용 영향, dependency/service/API/schema 변화, blocker, PLAN의 다음 제안 하나를 포함한다.

## Graphify code exploration

- For architecture, call-path, dependency, or cross-file relationship questions, query `graphify-out/graph.json` first when it exists (`graphify query "<question>"`).
- Treat graph edges as navigation evidence, not source-of-truth: distinguish extracted from inferred relationships and verify conclusions in the referenced source files.
- Graphify's no-LLM structural/update pass may not index every file type. Check the latest `graphify-out/GRAPH_REPORT.md` warnings before relying on coverage; authoritative product and policy docs remain the source of truth.
- After relevant source changes, refresh this code graph with `graphify update .`.
