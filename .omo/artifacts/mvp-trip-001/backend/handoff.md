# MVP-TRIP-001 backend handoff

## 결과

R1 backend와 additive V3를 구현했다. 기존 `AuthenticatedUser`, Spring Security, JDBC/`TransactionTemplate`, V1/V2와 basket POST/current GET은 유지했다.

- `POST /api/trips`: NFC + Unicode 경계 whitespace 정규화, code-point/control/date/UUID 검증, 최초 201/replay 200, principal-scoped 24시간 immutable snapshot, changed normalized input 409.
- `GET /api/trips`: current membership, UTC frozen `as_of_date`, `ONGOING -> UPCOMING -> PAST`, 계약 순서의 SQL keyset, role/member count와 nullable legacy metadata.
- `GET /api/trips/{trip_id}`: current-member flat detail, absent/nonmember 동일 404.
- `GET /api/trips/{trip_id}/trip-items?state=BASKET`: coherent single-statement plan/item snapshot, deterministic reverse keyset, continuation version conflict와 honest ID-only Place.
- cursor: startup-generated process-local AES-256-GCM key, 15분, base64url no-padding/1024자 상한, typed binary payload, principal/endpoint/limit/trip/state/version binding. 임의 SQL identifier/fragment를 payload에서 소비하지 않는다.
- create 동시성: trip + creator ADMIN + ledger를 한 transaction에 stage하고 ledger PK가 승자를 정한다. unique loser transaction 전체 rollback 뒤 새 transaction에서 winner snapshot을 readback한다. 일반 자동 retry는 없다.

선택한 방식은 범용 JSON cursor/retry layer가 아니라 endpoint별 고정 binary payload와 create 전용 collision recovery다. 계약 밖 field/SQL 선택지를 cursor에 넣는 대안을 거부해 payload allowlist와 typed bind를 구조적으로 고정했다.

## 변경 경로

Production:

- `backend/src/main/java/com/stog/backend/TripProductController.java`
- `backend/src/main/java/com/stog/backend/TripProductService.java`
- `backend/src/main/java/com/stog/backend/TripCursorCodec.java`
- `backend/src/main/java/com/stog/backend/TripProductException.java`
- `backend/src/main/java/com/stog/backend/ApiErrorHandler.java`
- `backend/src/main/resources/db/migration/V3__trip_product.sql`

Tests/fixture hooks:

- `backend/src/test/java/com/stog/backend/TripProductControllerTest.java`
- `backend/src/test/java/com/stog/backend/TripCursorCodecTest.java`
- `backend/src/test/java/com/stog/backend/TripProductPostgresIntegrationTest.java`
- `backend/src/test/java/com/stog/backend/RealJdbcProbe.java`: create-ledger insert barrier 추가.
- `backend/src/test/java/com/stog/backend/BasketSavePostgresIntegrationTest.java`: V3 table을 fresh-schema allowlist에 추가.
- `backend/src/test/scripts/Run-PostgresIntegration.ps1`: 기본 동작은 유지하면서 `-Tests`, `-SkipBootJar`, `-ArtifactSubdirectory` 선택 hook 추가.

Migration:

- 이름: `V3__trip_product.sql`
- SHA-256: `b6079d26454442b1373b65650e46a62218427c7c15f595edd7d784e60a2ab206`
- `trip.name`, `trip.region_label` nullable 추가; legacy update/backfill 없음.
- `trip_member_user_trip_idx`, `trip_status_start_idx`, `trip_status_end_idx` 추가.
- 전용 `trip_create_execution`과 exact PK/check/FK/24시간 expiry 추가; result trip FK 없음.

## RED / GREEN evidence

Behavioral RED:

```text
JAVA_HOME='C:/Program Files/Java/jdk-17' ./gradlew.bat -p backend --no-daemon test --tests com.stog.backend.TripProductControllerTest --rerun-tasks
```

인증된 `AuthenticatedUser` POST가 실제 MVC/security 표면에 도달했지만 handler 부재로 expected 201 / actual 404였다. compile failure가 아니다. `red-evidence.txt`, `controller-red.log`.

Focused unit/controller GREEN:

```text
JAVA_HOME='C:/Program Files/Java/jdk-17' ./gradlew.bat -p backend --no-daemon test --tests com.stog.backend.TripProductControllerTest --tests com.stog.backend.TripCursorCodecTest --tests com.stog.backend.BasketSaveControllerTest --tests com.stog.backend.AuthenticationBoundaryTest --rerun-tasks
```

Exit 0. `controller-regression.log`. 새 controller/cursor만의 이전 focused run도 exit 0 (`unit-green.log`).

Focused isolated PostgreSQL GREEN:

```powershell
& 'backend/src/test/scripts/Run-PostgresIntegration.ps1' `
  -Tests @('com.stog.backend.TripProductPostgresIntegrationTest','com.stog.backend.BasketSavePostgresIntegrationTest') `
  -SkipBootJar `
  -ArtifactSubdirectory 'mvp-trip-001/backend'
```

Exit 0. R1 9개 + inherited basket 24개 = 33개, failure/error/skip 0. Generated non-superuser/private schema 사용 후 invocation resource `0|0`, 기존 metadata 불변. `postgres-regression.log`, `test-runner-result.json`. 별도 connection-only cleanup receipt도 `cleanup-check/`에 있다.

모든 변경 Java 파일 LSP diagnostics 0. SQL LSP는 workstation에 구성되지 않아 migration은 isolated Flyway V1+V2+V3 적용과 PostgreSQL test로 검증했다.

## Verifier 실행 정보

최종 verifier는 canonical 전체 명령을 그대로 실행하면 된다.

```powershell
$env:JAVA_HOME='C:/Program Files/Java/jdk-17'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File backend/src/test/scripts/Run-PostgresIntegration.ps1
```

이 기본 호출은 선택 hook을 지정하지 않으므로 기존처럼 전체 backend test + `bootJar`를 실행하고 기존 artifact 위치를 사용한다. 전용 재실행이 필요하면 위 focused 명령을 사용한다. 테스트 fixture는 환경 변수 `STOG_TEST_JDBC_URL`, `STOG_TEST_DB_USERNAME`, `STOG_TEST_DB_PASSWORD`, `STOG_TEST_DB_NAME`, `STOG_TEST_DB_SCHEMA`, `STOG_TEST_DB_ROLE`을 runner가 process scope로 생성/복원한다. 실제 값은 handoff에 기록하지 않는다.

Android 통합이 사용할 wire는 TASK와 동일하며 별도 test principal bypass는 없다. 정상 real-surface fixture는 기존 synthetic-provider exchange로 access token을 얻은 뒤 네 route를 호출할 수 있다.

## 미실행 / 주의

- Producer는 지시대로 full backend suite와 `bootJar`를 실행하지 않았다. 최종 verifier 소유다.
- durable development DB, 실제 user/provider/DPAPI/config, public/TLS, Android real surface를 변경하거나 실행하지 않았다.
- 외부 service/dependency/config를 추가하지 않았다.
- `apply_patch` executable은 workstation에서 `command not found`였으므로 첫 시도 이후 제공된 exact write/edit API로만 파일을 변경했다.
- 작업 중 병렬 Android lane의 app build/source mtime 변화가 관찰됐으나 이 lane은 app/docs를 편집하지 않았다.
