# LOCAL-BACKEND-001: isolated local PostgreSQL와 backend startup

## 1. 식별과 상태

| 항목 | 값 |
| --- | --- |
| 작업 상태 | COMPLETE |
| 승인 상태 | 사용자의 local development DB/startup 실행 승인 |
| 구현 상태 | IMPLEMENTED |
| 검증 상태 | DB/login/migration/loopback runtime/test/package VERIFIED |
| 외부 상태 | public listener/router/firewall/provider integration DEFERRED / NOT_RUN |

## 2. 목적과 범위

기존 test/shared PostgreSQL 자원을 보존하면서 local application 전용 `stog_backend_dev` database, `stog_backend_app` login, `stog_app` schema를 만들고, 같은 Windows 사용자만 복호화할 수 있는 DPAPI credential로 backend를 반복 시작한다.

포함 경로는 `backend/scripts/{Initialize,Start}-LocalBackend.ps1`, 이 TASK, `docs/{PLAN,QUALITY_OPERATIONS}.md`, `.omo/artifacts/local-backend-001/`이다. domain Java, migration, dependency, Android, provider, test fixture runner, PostgreSQL service, firewall/router/TLS/public binding은 변경하지 않는다.

## 3. 계약과 구현

- 생성 전 catalog에서 database/role collision `0|0`을 확인했다. 이름이 충돌하면 initializer는 기존 자원을 재사용·변경·삭제하지 않고 실패한다.
- database owner는 `postgres`; application login은 `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION`, `CONNECT`만 받고 database `CREATE`와 `TEMPORARY`는 받지 않는다. `PUBLIC`의 새 database `CONNECT`/`TEMPORARY`와 `public` schema 권한을 제거했다.
- `stog_app`만 application role이 소유하고 `USAGE/CREATE`한다. Flyway V1은 이 schema에만 적용한다.
- random credential은 initializer process 안에서 만들고 `%LOCALAPPDATA%\STOG\local-backend\stog_backend_app.password.clixml`에 Windows DPAPI `SecureString`으로 저장한다. source, command argument, log, artifact에는 값이 없다. 같은 Windows 사용자·machine context에서만 재사용할 수 있다.
- starter는 protected credential을 읽고 datasource와 `SPRING_FLYWAY_DEFAULT_SCHEMA`, `SPRING_FLYWAY_SCHEMAS`, `SPRING_FLYWAY_CREATE_SCHEMAS=false`를 포함한 설정을 child process에만 공급한다. 이 dedicated launcher는 inherited `SERVER_ADDRESS`/`SERVER_PORT`를 process 안에서 보존한 뒤 `127.0.0.1`/`8080`으로 강제하고 종료 시 원래 값을 복원한다. 일반 application의 Spring override 계약은 바꾸지 않는다.
- 기존 `stog_backend_test`, test role/schema/data와 `STOG_TEST_DB_*`는 application startup에 사용하지 않는다.

## 4. 재현 명령

최초 1회, repository root의 Windows PowerShell에서:

```powershell
.\backend\scripts\Initialize-LocalBackend.ps1
```

충돌 또는 credential file 선존재 시 중단한다. 정상 초기화 뒤 매번:

```powershell
.\backend\scripts\Start-LocalBackend.ps1
```

`Ctrl+C`는 backend/Gradle QA process만 종료한다. PostgreSQL service와 retained database/role/schema/DPAPI credential은 중지·삭제하지 않는다.

## 5. 수용 결과와 증거

| 조건 | 결과 / 증거 |
| --- | --- |
| 선행 collision과 test metadata | database/role `0|0`; role/database/schema fingerprint 기록, [preflight](../../.omo/artifacts/local-backend-001/preflight.txt) |
| faithful RED | 초기 startup script 부재 assertion exit 1, [result](../../.omo/artifacts/local-backend-001/tdd-first-failure.txt) |
| launcher-boundary review RED | 변경 전 safe inherited `127.0.0.2:18080`가 실제 listener를 이동시켰고 `127.0.0.1:8080`은 없었음; wildcard/public address 미사용, [RED](../../.omo/artifacts/local-backend-001/launcher-boundary-red.txt), [cleanup](../../.omo/artifacts/local-backend-001/launcher-boundary-red-cleanup.txt) |
| isolated privilege/login | database owner `postgres`; role elevated flag 전부 false; public connect/temp와 public schema usage/create false; app database create false; TCP login 성공, [validation](../../.omo/artifacts/local-backend-001/privilege-validation.txt), [TCP identity](../../.omo/artifacts/local-backend-001/tcp-app-identity.txt) |
| credential | same-user DPAPI `SecureString` file 존재·복호화 type 확인, 값 출력 없음; [storage](../../.omo/artifacts/local-backend-001/credential-storage.txt) |
| startup/Flyway/HTTP/listener | inherited safe-hostile `127.0.0.2:18080`에도 exact `127.0.0.1:8080`, inherited 두 값 복원, ready, V1 success, 6 tables, protected GET `401 AUTHENTICATION_REQUIRED`, [runtime](../../.omo/artifacts/local-backend-001/runtime-verification.txt) |
| QA cleanup | launcher 종료, candidate port 8080/18080 listener 0; PostgreSQL와 durable local resources 유지, [receipt](../../.omo/artifacts/local-backend-001/cleanup-receipt.txt) |
| regression/package | focused auth/controller tests와 `bootJar` 최종 결과는 [final verification](../../.omo/artifacts/local-backend-001/final-verification.txt) |
| preservation | 선행/후행 test metadata fingerprint 동일, [preservation](../../.omo/artifacts/local-backend-001/preservation.txt) |

최초 initializer는 Windows PowerShell의 구형 .NET에 없는 `Convert.ToHexString` 때문에 resource 생성 전에 exit 1이었다. 기존 runner와 같은 byte formatting으로 수정한 뒤 성공했다. 첫 runtime verifier는 실제 migration/startup 뒤 boolean 표기 `true`를 `t`로 잘못 기대해 실패했으며 QA process는 정리됐다. 관찰값에 맞춘 verifier 재실행은 통과했다.

Collision refusal 검증에서 초기 catch가 이 invocation이 만들지 않은 credential file까지 제거하는 결함을 발견했다. database/role/schema와 test 자원은 영향받지 않았다. Catch를 invocation-owned credential attempt에만 제한하고, 새 random password를 transcript/argument에 노출하지 않고 role과 DPAPI file에 함께 회전했다. 재검증은 collision exit 1 뒤 credential retained, startup/Flyway/401와 cleanup 통과였다. [collision result](../../.omo/artifacts/local-backend-001/collision-refusal.txt), [rotation result](../../.omo/artifacts/local-backend-001/credential-rotation.txt).

## 6. 보안, 복구, 인계

credential file이 손상·유실되면 starter는 시작 전에 실패한다. 임의 재초기화나 collided 자원 삭제는 금지한다. 의도적 credential rotation 또는 durable resource teardown은 별도 local operations 작업으로 database ownership/data를 확인한 뒤 수행한다.

Review에서 dedicated starter가 caller의 `SERVER_ADDRESS`/`SERVER_PORT`를 상속해 committed default를 우회할 수 있음을 확인했다. Public wildcard를 사용하지 않고 alternate loopback `127.0.0.2:18080`으로 RED를 관찰한 뒤, starter process-local override와 기존 restoration map에 두 이름을 추가했다. hostile inherited 값으로 재실행해 exact listener와 복원을 확인했다.

이 TASK 자체의 local success는 migration과 anonymous protected `401`까지이며 인증 성공을 의미하지 않는다. 후속 local Kakao auth 구현과 실제 loopback login/logout/relogin은 별도 TASK와 artifact에 기록됐다. public/LAN binding, router/firewall, TLS/DNS, tunnel과 외부 request는 계속 미승인·미실행이다.
