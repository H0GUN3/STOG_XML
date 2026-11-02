# M1-SHARED-FOUNDATION-002: basket save foundation, retrospective record

## 상태

`RETROSPECTIVE / IMPLEMENTED / VERIFIED, historical evidence`

이 TASK는 당시 TASK 문서가 저장소에 남아 있지 않은 M1-002의 범위와 증거를 현재 artifact와 backend source에서 복원해 연결한다. 원래 승인 문서나 당시 승인 주체를 새로 만들지 않는다. 이 기록은 현재 작업 승인이 아니다.

## 복원된 범위

- 검증된 내부 principal과 여행 membership 및 여행 상태 확인.
- 기존 Place를 TripItem basket으로 저장하고, 동일 Place 중복, `request_id` replay, 입력 충돌을 처리.
- `plan_version`, atomic transaction, rollback, lock, 성공 결과 snapshot과 24시간 만료를 처리.
- commit 뒤 callback만 시도하고 실패 callback이 committed mutation을 되돌리지 않음.
- authentication boundary와 production default user 차단.

Android, real provider, public network, real SSE transport, travel CRUD, schedule mutation은 이 historical scope에 없다.

## Acceptance와 보존된 evidence

| 항목 | 상태 | 근거 |
| --- | --- | --- |
| backend implementation, migration, tests와 runner | `VERIFIED, historical` | [final-verification.json](../../.omo/artifacts/m1-foundation-002/final-verification.json) |
| PostgreSQL integration | `VERIFIED, historical` | fresh PostgreSQL 18.6, authenticated non-superuser, generated fixture cleanup `0|0` |
| test result | `27 PASS, 0 failure, 0 error, 0 skipped` | `AuthenticationBoundaryTest` 3, `BasketSaveControllerTest` 5, `BasketSavePostgresIntegrationTest` 19 |
| packaging and safety | `VERIFIED, historical` | `bootJar PASS`, credential scan and protected-file evidence in the artifact |
| resource caveat | `PRESERVED, unresolved` | artifact records four `stog_m1_002` role/schema pairs with uncertain origin; they were not deleted |

원래 실행 명령은 artifact에 기록된 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File backend/src/test/scripts/Run-PostgresIntegration.ps1`이며, 이 문서 갱신에서 재실행하지 않았다. 현재 auth suite 52개와 M1-003 suite 33개는 이 historical 27개와 다른 시점의 evidence다.

## 인계

현재 구현·검증 상태는 [PLAN.md](../PLAN.md)의 단일 원장을 따른다. 다음 슬롯은 기존 M1-003 current TripItem GET의 Android local integration이며, 이 retrospective record는 그 구현을 포함하지 않는다.
