# 품질과 운영

실제 환경 사실, 검증 절차, 보안 통제, 복구를 기록한다. 도구가 설치되어 있다는 사실은 프로젝트 pin 승인이 아니며 파일이 존재하는 것만으로 build가 검증되지 않는다.

## 환경·버전 표

| 항목 | 상태 | 값 / 증거 |
| --- | --- | --- |
| Host OS | CONFIRMED | Windows `10.0.26200.7840`, x64 |
| Android Studio | 설치 CONFIRMED | `C:/Program Files/Android/Android Studio1`, Quail 3 `2026.1.3`, build `AI-261...15948027`; 다른 Studio 디렉터리는 불완전 |
| Oracle JDK | 설치 CONFIRMED | `17.0.11` |
| Global Java | CONFIRMED | `JAVA_HOME`/`PATH`는 Temurin `25.0.4.1`; global 설정 변경 금지 |
| Studio JBR | 설치 CONFIRMED | `25.0.2` |
| Android SDK root | CONFIRMED | `C:/Users/GUN/AppData/Local/Android/Sdk` |
| SDK platform / Build Tools | 설치 CONFIRMED | API 37만 설치 / `36.0.0` |
| Platform Tools / emulator | 설치 CONFIRMED | `37.0.1` / `37.1.11`; WHPX 사용 가능 |
| AVD | 존재·중지 CONFIRMED | `Pixel_8`, API 36.1 Google Play x86_64 |
| 연결 기기 | CONFIRMED | 조사 당시 없음 |
| `sdkmanager` / `avdmanager` | 부재 CONFIRMED | 발견된 SDK tooling에 없음 |
| PATH Gradle | 부재 CONFIRMED | `gradle` 없음 |
| Gradle Wrapper | VERIFIED | Gradle 9.5.0, `gradle-wrapper.properties` SHA-256 pin `553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746`, direct `downloads.gradle.org` 경로 |
| AGP | VERIFIED | `9.3.2`, clean-copy build에서 확인 |
| Cached Gradle / AGP | 실행 가능 CONFIRMED | 9.5.0 / 9.3.2. cache 사실은 Wrapper 및 project 검증과 별도 |
| 기존 app/build/Wrapper | bootstrap 전 부재 CONFIRMED | source, build, wrapper, root `.git` repository 없음. Git CLI는 설치되어 있으며 `.idea`는 Temurin 25 일반 Java 설정 |

### 위임된 개발 설정

2026-09-22 질문 timeout 뒤 사용자가 best-judgment 계속 진행을 지시한 위임에 따라 다음 개발 기본값을 채택했다: `applicationId`/namespace `com.stog.app`, app label `STOG`, JDK 17.0.11을 build process/project 범위에서만 사용, AGP 9.3.2, Gradle 9.5.0, `compileSdk` 37, `targetSdk` 36, `minSdk` 23, AppCompat 1.7.1, Fragment 1.8.9, Material 1.13.0. 앱 source는 Java/XML만 사용하고 전이 Kotlin runtime은 허용한다. 이는 사용자의 항목별 명시 답변이나 store identity·소유권·publication 승인이 아니다. AppCompat/Fragment/Material은 실제 Material theme와 Fragment host에 필요하며, 실제 상태·data access가 생기기 전에는 ViewModel/Repository source를 추가하지 않는다. 설정 채택과 build 검증은 별개였다. 현재 root와 clean source copy의 build 검증은 완료됐으며, dependency/secret/source review summary는 아직 대기 중이다.

### 환경 조사 공식 출처

Context7는 사용할 수 없었으며 아래 공식 자료를 확인했다. 자료 확인은 실제 build 검증이 아니다.

- Android Studio releases: https://developer.android.com/studio/releases
- Android Gradle Plugin 9.3 release notes: https://developer.android.com/build/releases/past-releases/agp-9-3-0-release-notes
- Java versions in Android builds: https://developer.android.com/build/jdks
- Google Play target API requirements: https://developer.android.com/google/play/requirements/target-sdk
- Configure the app module: https://developer.android.com/build/configure-app-module
- View Binding: https://developer.android.com/topic/libraries/view-binding
- AndroidX AppCompat releases: https://developer.android.com/jetpack/androidx/releases/appcompat
- AndroidX Fragment releases: https://developer.android.com/jetpack/androidx/releases/fragment
- Material Components Android releases: https://github.com/material-components/material-components-android/releases

## 개발자 설정과 비밀값

- 프로젝트 local 개발자 설정 위치는 root `local.properties`다. version control에서 무시해야 하며 값을 채운 파일을 commit하지 않는다.
- server 주소의 중앙 이름은 local `local.properties`의 `stog.server.baseUrl`, 자동화의 `STOG_SERVER_BASE_URL`이다. Android consumer가 구현됐으며 release는 HTTPS, debug는 `adb reverse`용 literal `127.0.0.1` HTTP만 허용한다. 값과 fallback server는 source에 없다.
- Kakao Native App Key의 중앙 이름은 `stog.kakao.nativeAppKey` / `STOG_KAKAO_NATIVE_APP_KEY`다. 이는 APK-visible identifier이며 secret이 아니다. Admin Key/client secret은 Android build 입력으로 만들거나 APK에 넣지 않는다.
- STOG access token은 memory-only이고 refresh token은 Android Keystore AES/GCM ciphertext로 private, backup-excluded preferences에 둔다. credential은 Java/XML/resource, screenshot, log, TASK evidence, sample에 넣지 않는다.
- server 주소는 보통 credential이 아니지만 topology를 발명하거나 공개하지 않는다. 향후 API 계약에서 scheme과 host를 검증한다.

| 예시 이름 | 설명 | 현재 상태 |
| --- | --- | --- |
| `stog.server.baseUrl` | 승인된 STOG server의 개발자 local 주소 | 이름/Android consumer IMPLEMENTED / 값 사용자 local 설정 |
| `STOG_SERVER_BASE_URL` | 자동화 환경의 같은 목적 변수 | environment fallback IMPLEMENTED / 값 미설정 |
| `stog.kakao.nativeAppKey` | ignored local APK-visible Kakao identifier | consumer IMPLEMENTED / 실제 값 미설정 |
| `STOG_KAKAO_NATIVE_APP_KEY` | 자동화 환경의 같은 목적 변수 | environment fallback IMPLEMENTED / 실제 값 미설정 |
| Admin Key/client secret | backend 전용 비밀이어도 Android에는 금지 | NOT_CREATED |

Android consumer는 구현됐지만 fallback server는 없다. 실제 값은 HTTPS base URL을 ignored local/automation 환경에서만 공급하고 source, resource, screenshot 또는 evidence에 전체 public host를 남기지 않는다.

### Backend local 설정과 시작 절차

`backend/src/main/resources/application.properties`가 server 설정의 중앙 원본이다. 안전한 기본값은 `server.address=127.0.0.1`, `server.port=8080`이다. Spring Boot 4.0.3의 일반 application contract에서는 process environment `SERVER_ADDRESS`/`SERVER_PORT`와 command line이 file 값을 override한다. 그러나 dedicated `Start-LocalBackend.ps1`은 caller의 두 environment 값을 보존하고 자기 process에서 정확히 `127.0.0.1:8080`으로 덮은 뒤 종료 시 복원한다. 따라서 이 local-only launcher에는 public/LAN binding이나 alternate port가 상속되지 않으며, framework의 일반 override 계약은 제한하지 않는다.

Application datasource는 source나 `local.properties`에 넣지 않는다. `LOCAL-BACKEND-001`은 local 전용 `stog_backend_dev` database, `stog_backend_app` login, `stog_app` schema를 준비했다. login은 non-superuser이며 database/schema 범위 밖 create 권한이 없다. test 전용 `STOG_TEST_DB_*`, `stog_backend_test`와 fixture role/schema는 application 시작에 재사용하지 않는다.

Credential은 `%LOCALAPPDATA%\STOG\local-backend\stog_backend_app.password.clixml`에 Windows DPAPI `SecureString`으로 보관한다. plaintext config가 아니며 같은 Windows 사용자와 machine context만 복호화할 수 있다. 값을 source, shell argument/history, log, screenshot 또는 evidence에 출력하지 않는다. 파일이 없거나 복호화되지 않으면 startup은 DB 접속 전에 실패한다.

최초 1회 initializer와 이후 startup은 repository root의 Windows PowerShell에서 실행한다.

```powershell
.\backend\scripts\Initialize-LocalBackend.ps1 # only when the named resources and credential do not exist
.\backend\scripts\Start-LocalBackend.ps1
.\backend\scripts\Initialize-RestrictedOwner.ps1 # once, only after the confirmed mapping exists
.\backend\scripts\Start-LocalBackend.ps1 -RestrictedOwner
```

Initializer는 database/role collision이나 credential file 선존재를 발견하면 기존 자원을 변경·삭제하지 않고 중단한다. Starter는 DPAPI credential을 읽어 child process에만 `SERVER_ADDRESS=127.0.0.1`, `SERVER_PORT=8080`, `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_DATASOURCE_HIKARI_SCHEMA`, `SPRING_FLYWAY_DEFAULT_SCHEMA`, `SPRING_FLYWAY_SCHEMAS`, `SPRING_FLYWAY_CREATE_SCHEMAS=false`와 process-local JDK 17을 공급한 뒤 root Wrapper의 `-p backend bootRun`을 실행한다. 세 Flyway schema 설정을 함께 고정해 migration이 `stog_app` 밖에 적용되지 않게 한다. `Run-PostgresIntegration.ps1`은 임시 fixture runner이므로 startup 대체가 아니다.

실제 local startup은 현재 Flyway V7, exact loopback listener와 native/Kakao-preserving auth surface까지 `VERIFIED`다. Launcher는 same-user local application data 아래 persistent normal directory `media`를 만들고 process-local `STOG_MEDIA_ROOT`로 공급하며 reparse point를 거부한다. auth provider 설정은 기본 disabled이며 `STOG_AUTH_KAKAO_ENABLED`, `STOG_AUTH_KAKAO_EXPECTED_APP_ID`와 endpoint 이름을 중앙 `application.properties`가 소유한다. normal mode는 query/fragment/userinfo/port 변형 없는 exact official Kakao HTTPS 두 endpoint만 허용한다. endpoint override는 `STOG_AUTH_KAKAO_FIXTURE_MODE=true`일 때 literal `127.0.0.1`/`[::1]` HTTP와 explicit port에만 허용하고 DNS hostname 및 redirect는 거부한다. 값 부재·untrusted destination은 token 전송 전 503 fail-closed이고 source에는 real app ID/token이 없다.

### Native auth backend verification (2026-11-02)

- Installed `spring-security-crypto:7.0.3`의 PBKDF2-HMAC-SHA-256
  600,000 iteration/16-byte salt/256-bit versioned encoding을 사용하며 새
  dependency나 runtime breach API가 없다.
- Focused non-DB tests와 native/Kakao/restricted PostgreSQL focused gate가
  통과했다. 첫 focused runner command는 PowerShell array binding 전에
  종료됐고 DB write가 없었다. 첫 actual focused DB run은 V7 table set을
  safety gate에 추가해야 한다는 expected contract failure였으며 cleanup
  `0|0`; correction 뒤 green이었다.
- Final canonical runner는 generated non-superuser V1-V7에서 22 classes,
  135 tests, failure/error/skip 0, `bootJar`, media cleanup, role/schema
  `0|0`과 preexisting metadata invariant를 통과했다.
- Retained dev pre-V7 backup은 postgres-owned mode 600 custom archive이며
  `pg_restore --list`를 통과했다. V7 적용 전후 existing Kakao User,
  identity와 preexisting session fingerprints가 같고 기존 domain count도
  변하지 않았다.
- Normal backend는 exact `127.0.0.1:8080`, Kakao disabled/default normal
  config로 실행 중이다. Fresh nonpersonal native QA account는 signup 201,
  empty trips 200, trip create 201, logout 204, login 200, same trip read와
  final logout 204를 통과했다. Credential은
  `%LOCALAPPDATA%\STOG\local-backend\native-qa-account.clixml` same-user DPAPI
  SecureString에만 있고 log/evidence에는 값이 없다.

Independent backend review correction은 migration/data rewrite 없이 다음을
강화했다: exact pinned MIT 10,001-entry compromised-password corpus와 digest
검증, password null/NUL/space/NFC/length wire 분리, declared/chunked auth
body 8192-byte pre-parser cap, production-injected encoder one-match counting,
deterministic concurrent throttle/fifth-failure/100-row cleanup tests, actual
Spring bean test, 그리고 existing directory content/reparse/ACL behavior를
실행하는 PowerShell test. Review-corrected final gate 결과는
`.omo/artifacts/native-auth-ui-20261102/review-implementation.md`가 소유한다.

Owner 전용 시험 모드는 `%LOCALAPPDATA%\STOG\local-backend\restricted-owner-user-id.clixml`의 same-user DPAPI `SecureString`을 사용한다. `Initialize-RestrictedOwner.ps1`은 dedicated `stog_backend_dev/stog_app`을 read-only로 조회해 기존 Kakao identity-to-user mapping이 정확히 하나이고 기존 session이 있을 때만 한 번 pin하며, 설정이 이미 있거나 mapping이 없거나 여러 개면 변경 없이 중단한다. UUID 값은 source, 명령 output, log와 evidence에 쓰지 않는다. `Start-LocalBackend.ps1 -RestrictedOwner`만 고정 값을 읽어 process-local `STOG_AUTH_RESTRICTED_OWNER_ENABLED=true`와 `STOG_AUTH_RESTRICTED_OWNER_USER_ID`를 설정하고 종료 시 inherited 값을 복원한다. 시작 때 DB에서 owner를 재선택하지 않는다. 설정이 없거나 DPAPI 형식/canonical UUID가 아니면 Gradle 전에 fail closed하며 normal mode로 fallback하지 않는다. disabled 기본값에서는 기존 launcher 동작과 provisioning이 유지된다. 이는 public start나 다른 tester 승인이 아니다. `Ctrl+C`로 application/Gradle process만 종료하며 PostgreSQL service와 retained development database/role/schema/credential/owner 설정은 유지한다. 기본 startup 상세는 [LOCAL-BACKEND-001](tasks/LOCAL-BACKEND-001.md), auth evidence는 [KAKAO-AUTH-CONTRACT-001](tasks/KAKAO-AUTH-CONTRACT-001.md)에 있다.

2026-09-28 review-fix 최종 auth 검증은 `Run-PostgresIntegration.ps1`로 52 tests와 bootJar를 통과했고 generated fixture role/schema cleanup 및 preexisting metadata 보존을 확인했다. 이어 DPAPI launcher의 dedicated `stog_backend_dev/stog_app`과 loopback HTTP provider fixture를 실행해 exchange 200, authenticated protected missing-trip 404, logout 204, logout 뒤 access/refresh 401을 관찰했다. fixture와 backend QA process 및 fixture auth rows는 정리했고 migrated durable dev DB만 유지했다. `.omo/artifacts/kakao-auth-backend-001/`에 sanitized 결과가 있으며 real Kakao/account/key/token, Android SDK/device와 public network는 `NOT_RUN`이다.

## Direct public access 외부 시험 준비

사용자의 현재 방향은 Cloudflare Tunnel이 아니라 router port forwarding을 통한 direct public access다. [CLOUDFLARE-TUNNEL-001](tasks/CLOUDFLARE-TUNNEL-001.md)은 역사적 준비로 보존하지만 현재 실행에서는 `DEFERRED / SUPERSEDED`이고 Tunnel은 선택되지 않았다. Safe local 설정은 `IMPLEMENTED / local VERIFIED`이며 server runtime, router, firewall, DNS, TLS, DB와 외부 요청은 모두 `NOT_RUN`이다. 실제 public exposure는 구체적 값과 별도 최종 승인을 기다린다.

실행 전에 다음을 확인·결정해야 하며 이 문서에서 값을 발명하지 않는다.

1. router admin access와 port-forwarding 변경·복구 권한.
2. ISP가 inbound 가능한 public IPv4를 제공하는지, CGNAT인지, 또는 IPv6를 의도적으로 지원할지. CGNAT이면 일반 IPv4 port forwarding만으로 도달할 수 없다.
3. stable hostname/DNS와 public HTTPS termination·certificate route. 평문 public HTTP를 app endpoint로 채택하지 않는다.
4. `LOCAL-BACKEND-001`에서 검증한 dedicated local application DB/schema, least-privilege login과 DPAPI-backed startup을 유지한다. 기존 `backend/src/test/scripts/Run-PostgresIntegration.ps1`은 임시 test fixture runner이지 application startup이 아니며 shared/production DB migration을 자동 실행하지 않는다.
5. exact intended public interface/port, router의 public-to-LAN mapping, 대상 host의 stable LAN address와 host firewall 범위.
6. HTTPS를 받아 loopback backend로 전달하는 **명시적으로 승인된 LAN-bound TLS reverse proxy** 또는 application 자체의 **명시적 LAN binding** 중 하나. 현재 backend는 `127.0.0.1`에 bind하므로 router forwarding만으로 접근할 수 없다. `localhost`는 같은 host에서만 보이고 router는 LAN address로 전달하므로, 승인 없이 application을 `0.0.0.0`에 bind하거나 새 proxy/dependency를 선택하지 않는다.

외부에 허용할 것은 승인된 HTTPS application endpoint 하나뿐이다. PostgreSQL `5432`, DB migration/admin interface, IDE/debug port와 router administration을 절대 forward하지 않는다. 현재 application auth를 끄거나 test principal을 runtime에 주입하지 않는다. Android auth consumer와 bounded local real-provider login은 확인됐지만 protected domain, public HTTPS, public real-surface callback 또는 authenticated API 성공은 여전히 `NOT_RUN`이다.

`stog.server.baseUrl`과 `STOG_SERVER_BASE_URL`의 중앙 설정 이름과 0원/no-paid-fallback 정책을 유지한다. hostname, public/LAN interface, public/internal port, DNS, TLS termination, proxy 종류와 firewall rule은 아직 미결정이다. 실행 evidence에는 UTC 시각, redacted hostname/address, public HTTPS status·error code, 승인된 mapping 범위와 종료 후 forwarding/firewall 복구 관찰만 남기고 credential, Authorization header, datasource/provider secret, 개인·여행 payload, 전체 router 설정 export와 개인 사용자 경로는 제거한다.

## 검증된 명령 대장

모든 build 명령은 Windows 작업 루트에서 `JAVA_HOME=C:/Program Files/Java/jdk-17`을 process-local로 지정해 `./gradlew.bat`를 사용했다. global `JAVA_HOME`/PATH는 변경하지 않았다.

| 작업 | 실제 검증 명령 | 환경 / 대상 | 결과 |
| --- | --- | --- | --- |
| Root build | `./gradlew.bat assembleDebug` | Gradle 9.5.0, AGP 9.3.2 | exit 0, `.omo/artifacts/android-bootstrap/logs/root-assemble-final.log` |
| Clean reconstruction | `./gradlew.bat assembleDebug lintDebug testDebugUnitTest` | fresh source copy, fresh `GRADLE_USER_HOME`, JDK 17.0.11, direct official distribution | exit 0, `.omo/artifacts/android-bootstrap/logs/clean-reconstruction-final-success.log` |
| `assembleDebug` | 위 clean reconstruction command에 포함 | clean-copy APK | exit 0 |
| Lint | 위 clean reconstruction command에 포함 | report `reconstruction-final/source/app/build/reports/lint-results-debug.html` 및 `.sarif` | exit 0 |
| Unit test | 위 clean reconstruction command에 포함 | `testDebugUnitTest` `NO-SOURCE`, zero tests | exit 0, test absence remains explicit |
| Install / launch | `adb -s emulator-5554 install -r C:/Users/GUN/AndroidStudioProjects/STOG_2/.omo/artifacts/android-bootstrap/reconstruction-final/source/app/build/outputs/apk/debug/app-debug.apk`; `adb -s emulator-5554 shell am start -W -n com.stog.app/.MainActivity` | Pixel_8 emulator-5554, API 36.1 | install and launch success |
| Device QA | `adb` portrait, landscape/recreation, large-font, Back, warm relaunch and crash scan sequence | same emulator | passed, settings restored, empty app crash scan |

APK evidence is clean-copy only: SHA-256 `67436050dc85c7f2dd0d9ce928bb4029ca55d4020d07f89f35086e9fd3e58860`, `.omo/artifacts/android-bootstrap/logs/apk-sha256.log`. Root and clean-copy APK hashes differ, so they aren't claimed bit-for-bit identical. Final screenshots are `screenshots/final-{portrait,landscape-end,large-font,relaunch}.png`.

### Android Kakao auth 검증 (2026-09-28)

`ANDROID-KAKAO-AUTH-001` review fix는 process-local JDK 17과 Wrapper로 `:app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug`를 최종 실행했다. 결과는 JVM 9개와 Pixel_8 API-36 instrumentation 9개가 failure/error/skip 0, Lint 0 errors, assemble success다. Lint의 두 warning은 기존 pinned Gradle update 알림과 기존 missing application icon이며 suppression은 없다. HTTP instrumentation은 literal-loopback fixture이며 real Kakao/backend 호출이 아니다. 302/307/308 redirect target 비전달, 400/401/503/malformed 응답, missing/corrupt refresh logout, provider cleanup order/stale callback과 configured UI를 포함한다. `v2-user:2.25.0` Java compile/minSdk 23, Apache-2.0 POM과 transitive dependency graph를 확인했다.

실기기 missing-config surface는 portrait/light, landscape/light, font 1.3/light, portrait/dark에서 확인했다. Debug-only deterministic gateway로 configured success/logout/error production UI도 frame-commit 뒤 캡처·검토했다. 설정 precedence는 absent/blank/set 세 경우를 실제 generated BuildConfig로 검증했고 fixture 값은 출력하지 않았다. Keystore test는 ciphertext가 plaintext token을 포함하지 않는 것, decrypt failure clear와 key recovery를 확인했다. 2026-09-29 real Kakao local loopback login/logout/relogin과 cleanup은 [runtime artifact](../.omo/artifacts/real-kakao-login-runtime/cleanup-receipt.json)로 확인됐다. 이 evidence는 protected domain, production HTTPS, public network의 증거가 아니다. real key/account/provider 화면, API-23 runtime, physical device와 TalkBack은 `NOT_RUN`이다.

## 품질 gate

1. 단일 app module, Wrapper, manifest, Java/XML, resource, dependency graph, build script, diagnostics, ignored local file을 검사한다.
2. 상태/계약 logic은 결정적 unit test로, Android platform이 필요한 동작만 instrumentation으로 검증한다. fixed sleep 없이 exact state를 먼저 관찰하고 제한 시간 안에 기다린다.
3. IDE local 생성 상태나 global Gradle 없이 Wrapper와 선언된 repository만으로 재구성한다. Git CLI 설치 여부와 root의 `.git` repository 존재 여부는 별도 사실로 기록한다.
4. 이름이 기록된 지원 target에 설치하고 실제 entry surface를 실행하며 해당되는 recreation/back/retry와 crash log를 확인한다.
5. source, APK-visible resource, log, screenshot, evidence에 credential, token, private GPS/media, raw caption이 없는지 확인한다. network/media가 생기면 authorization test를 추가한다.
6. direct/transitive dependency의 출처, license, Kotlin runtime 영향, network 동작, 0원 조건을 검토한다. cache 존재는 채택 승인이 아니다.

## Log와 증거

task/build variant, operation, 비민감 correlation, error category만 최소 기록한다. JWT/cookie, authorization header, credential, private media/URL, 정밀 개인 위치, raw import text를 log에 남기지 않는다. evidence가 local 밖으로 나가면 사용자 경로를 가린다. fixture/mock을 표시하고 live system과 구분한다.

## 재구성, 문제 기록, 복구

### 재현 가능한 build 기준

tracked project file과 새로 제공한 `local.properties`만 있는 깨끗한 source copy, 새 `GRADLE_USER_HOME`, process-local `JAVA_HOME=C:/Program Files/Java/jdk-17`에서 다음을 실행한다.

```text
./gradlew.bat assembleDebug lintDebug testDebugUnitTest
```

Wrapper는 `downloads.gradle.org`의 pinned Gradle 9.5.0 distribution을 사용한다. 최초 clean reconstruction은 official route timeout으로 실패했으며, direct downloads.gradle.org URL과 bounded `networkTimeout=120000`을 적용한 재실행이 distribution을 받고 위 명령을 exit 0으로 완료했다. 이 실패와 회복은 각각 `clean-reconstruction-final.log`, `clean-reconstruction-official-route.log`, `clean-reconstruction-final-success.log`에 남아 있다. IDE cache와 global Gradle에 의존하지 않는다.

### 문제 기록

실패마다 증상, 정확한 명령, 환경, 첫 관련 오류, 영향 수용 기준, 현재 변경으로 인한 것인지, 수정, 재실행 결과, evidence를 남긴다. 관련 없는 기존 실패를 고치거나 diagnostic을 숨기지 않는다.

### 복구

- 설정/build 실패: 범위 안 bootstrap 변경만 되돌리거나 tracked file을 복원한다. global `JAVA_HOME`/PATH를 변경하지 않는다.
- dependency 실패: 미승인 dependency를 제거한다. 승인 없이 mirror·유료 repository를 추가하지 않는다.
- device/emulator 실패: build evidence는 별도로 보존하고 install/launch는 NOT_RUN 또는 실패로 기록한다.
- data/media/server 복구: backend, schema, 배포, 운영 data가 승인되지 않아 보류한다. 외부 운영 전 backup, restore test, retention, rollback, migration recovery를 정한다.

## 2026-09-29 문서 QA receipt

README, PLAN, ANDROID, API, BACKEND, DATABASE, QUALITY_OPERATIONS와 관련 TASK를 QA-by-read로 대조했다. M1-002 historical 27개 artifact 링크, M1-003 33개 artifact, auth 52개 suite, real Kakao local cleanup receipt, current Android TripItem GET 슬롯, direct access와 Tunnel의 historical label을 확인했다. 링크와 상태를 문서 내용과 실제 artifact에 맞춰 정정했으며 runtime, build, test, network를 실행하지 않았다. Markdown LSP는 이 환경에 구성되어 있지 않아 실행하지 않았다.

## 2026-09-29 Android current TripItem local gate

`ANDROID-TRIPITEM-GET-001`의 원래 compile failure는 missing scaffold라 behavioral RED가 아니며 original behavioral-first는 `NOT_VERIFIED`다. 이후 review blocker 수정은 malformed payload와 release fixture에 대한 실제 failing assertion/policy check를 먼저 남겼다. process-local JDK 17 final gate는 다음과 같다.

```text
./gradlew.bat --no-daemon -PstogLocalInstrumentationFixture=true :app:testDebugUnitTest :app:connectedDebugAndroidTest :app:lintDebug :app:assembleDebug -Pandroid.testInstrumentationRunnerArguments.class=<six explicit local/non-external test classes>
```

JVM 13개와 Pixel_8 API-36 instrumentation 17개가 failure/error/skip 0으로 통과했고, Lint는 error 0과 기존 warning 2개, assemble은 성공했다. 별도 bounded launcher는 generated synthetic provider credential/subject와 task-owned UUID rows만 사용해 emulator UI -> `adb reverse` -> existing local Spring backend -> PostgreSQL에서 success, trip/nonmember 404, item 404, access expiry -> refresh 1회 -> GET retry 1회, logout을 통과했다. real Kakao/native key/provider call은 실행하지 않았다. owned rows는 0, aggregate count는 원복됐고 reverse/backend/emulator/font 설정은 제거·복원했으며 dedicated DB와 DPAPI credential은 유지했다. 상세 명령, screenshot, sanitized result는 [TASK](tasks/ANDROID-TRIPITEM-GET-001.md)와 [artifact](../.omo/artifacts/android-tripitem-get-001/final-verification.json)에 있다. Backend source/schema가 바뀌지 않아 backend 52-test suite는 재실행하지 않았다.

## 2026-09-30 restricted-owner backend local gate

`RESTRICTED-OWNER-001`은 item 1만 구현했다. 최초 PostgreSQL RED는 60개 중 5개 실패로 nonowner/unregistered/preexisting-session denial과 absent/malformed config fail-closed가 아직 없음을 확인했다. 구현 후 focused 8개와 당시 최종 PostgreSQL 60개가 failure/error/skip 0으로 통과했고 `bootJar`, non-superuser/own-schema, generated fixture cleanup과 preexisting metadata 보존도 통과했다. 변경 Java 4개 LSP는 error/warning 0이었다.

Parent review config fix는 unsupported AssertJ assertion으로 난 최초 compile failure를 behavioral RED와 분리했다. 고친 test harness에서 config 4개 중 2개가 실제 RED였다: Java shortened UUID가 context를 시작했고 malformed sentinel이 nested parser cause에 나타났다. Full canonical 비교(대소문자 허용)와 cause-free generic startup error 뒤 config 5/5 및 최종 PostgreSQL 62/62 + `bootJar`가 통과했다. 변경 Java 2개 LSP는 error/warning 0, generated role/schema cleanup과 preexisting metadata 보존도 통과했다. Auth/session behavior가 바뀌지 않아 local provider HTTP는 반복하지 않고 기존 PASS를 보존했다.

별도 local HTTP proof는 process-scoped generated owner/nonowner와 literal-loopback provider를 사용해 real local Spring backend/PostgreSQL에서 owner exchange 200, protected missing-trip 404, refresh 200, logout 204/이후 401과 nonowner exchange/access/refresh 401, logout 204를 확인했다. normal security filter를 사용했고 principal bypass는 없다. task-owned row는 0, aggregate auth count는 원복됐고 backend/provider process와 8080 listener는 제거됐다. durable development DB와 DPAPI credential은 보존했다. 당시 실행 전 실제 owner 환경 설정은 없었으므로 real-owner run은 `NOT_RUN`이었다. 2026-10-01 후속으로 사용자 확인 계정의 단일 mapping을 read-only 재검증해 private DPAPI 설정에 pin하고 opt-in launcher를 실제 시작했다. restricted enabled와 pinned/current mapping 일치 boolean, exact `127.0.0.1:8080`, anonymous protected `401`, inherited 환경 복원과 listener cleanup을 확인했다. 실제 owner bearer 인증 요청은 `NOT_RUN`이며 real Kakao/provider 호출도 수행하지 않았다. 증거는 [TASK](tasks/RESTRICTED-OWNER-001.md), [기존 artifact](../.omo/artifacts/restricted-owner-001/final-verification.json), [owner configuration artifact](../.omo/artifacts/restricted-owner-001/owner-configuration/configured-startup.json)에 있다. Item 2, tool install/download, public listener/router/firewall/certificate와 Android는 `NOT_APPROVED / NOT_RUN`이다.

## 2026-10-01 MVP-TRIP-002 R2a local gate

R2a final backend gate는 기존 isolated runner로 generated non-superuser V1-V4 schema/role에서 11개 class, 91/91 test와 `bootJar`를 한 번 실행해 통과했고 role/schema `0|0`, pre-existing metadata 불변을 확인했다. Android member-row production correction 뒤 final affected gate는 JVM 16개, explicit Pixel_8 instrumentation 31/31(`CollaborationApiClientTest`, default/1.30 font member-row test 포함), Lint와 assemble을 통과했다.

Verifier runner는 두 generated provider subject를 normal exchange/security filter로 인증하고 Pixel_8 -> loopback Spring -> isolated PostgreSQL에서 create/edit/join, role/leave/remove/delete, invitation reissue/stop/new issue, strict malformed 400/404, logout/recreation capability clear와 access non-disclosure를 실행했다. actual font 1.30 portrait/landscape member rows와 dark/landscape 화면을 검사했다. raw invitation token/provider credential은 log/screenshot에 남기지 않았다. final cleanup은 schema/role `0|0`, backend/provider/listener/emulator/device/reverse 없음, font/rotation 복원과 durable DB/DPAPI 보존을 확인했다. 첫 strict rerun의 one-use provider credential 재사용 harness 실패와 원래 rows0 production failure도 별도 로그로 보존한다. 이후 native member-list scroll correction의 최종 delta는 backend/protocol을 바꾸지 않아 backend 91/91+bootJar와 strict protocol E2E를 재실행하지 않았다. event-synchronized font setup 뒤 `TripMembersRenderingUiTest` 4/4가 실제 swipe, 90% visibility, 두 행의 역할·제거 Espresso tap과 정확한 dialog를 portrait/landscape에서 통과했고 16개 화면을 검수했다. 최종 변경 Android gate는 JVM 16개·explicit instrumentation 33/33(`CollaborationApiClientTest` 포함)·Lint·assemble PASS였으며 crash scan, font/rotation/animation, app/reverse와 owned emulator cleanup도 PASS였다. clean-start font propagation failure는 별도 로그로 보존한다. [final verification](../.omo/artifacts/mvp-trip-002/r2a/final-verification.json).

## 현재 확인된 제한

- Android bootstrap 당시 `testDebugUnitTest`는 `NO-SOURCE`였고 instrumentation test도 실행하지 않았다. 이후 `ANDROID-KAKAO-AUTH-001`의 current gate는 JVM 9개와 API-36 instrumentation 9개가 통과했으며, 둘은 서로 다른 historical/current evidence다.
- API 23과 API 37 runtime, physical device, external service/account는 실행하지 않았다.
- `sdkmanager`와 `avdmanager`는 없다. 필요한 SDK contents는 platform API 37, Build Tools 36.0.0, Platform Tools 37.0.1, emulator 37.1.11이며, AVD는 Pixel_8 API 36.1 Google Play x86_64다.
- `.omo/artifacts/android-bootstrap/verification-summary.md`가 dependency/secret/ignore/source-copy 검토와 clean build/device evidence를 PASS로 확인했다. standalone Java/LSP diagnostics는 별도로 실행하지 않았지만, JDK 17 process-local Gradle compilation과 Android Lint는 성공했다.

## 보류된 운영

Dedicated local development DB와 backend startup은 `LOCAL-BACKEND-001`에서 검증했고 QA application process는 종료했으며 PostgreSQL와 durable local resources는 유지한다. backend는 loopback `127.0.0.1`과 명시 port `8080`을 유지하므로 public listener가 아니다. exact network interface/public port, IPv4/CGNAT/IPv6, DNS, TLS route, LAN binding/proxy와 firewall/router mapping은 미결정이고 router/firewall/DNS/TLS/external request/public exposure는 모두 `NOT_RUN`이다. Cloudflare Tunnel 준비는 역사 기록으로 보존하지만 현재 실행에서는 deferred다. production backup/restore, worker, queue와 monitoring 운영도 정의되지 않았다. Mapbox, Gemini, routing, Instagram, social 연동은 계정·약관·privacy·강제 비용 차단·실패 동작이 검증될 때까지 연결하지 않는다. 유료 fallback은 없다.
