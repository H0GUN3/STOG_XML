# M1-SHARED-FOUNDATION-003: 저장된 TripItem 현재 상태 조회

## 1. 식별과 상태

| 항목 | 값 |
| --- | --- |
| 단계 / 기준 절 | M1 / 기존 TripItem 저장·복구 계약 |
| 작업 상태 | DONE |
| 승인 상태 | CONFIRMED |
| 구현 상태 | IMPLEMENTED |
| 검증 상태 | VERIFIED |
| 담당 / 검토 | 미지정 / 미지정 |
| 선행조건 | `M1-SHARED-FOUNDATION-002` 구현·검증 |

이 TASK는 완료되어 `docs/PLAN.md`의 history로 이동했다. 구현과 검증 상태는 아래 evidence에 근거한다.

## 2. 목적과 근거

저장 요청의 replay 결과는 당시 성공 snapshot이므로 현재 `TripItem`이 존재하거나 같은 상태라는 뜻이 아니다. 인증된 현재 구성원이 저장된 한 항목의 현재 상태를 REST로 다시 읽을 수 있는 최소 recovery seam을 추가한다.

정확한 operation은 다음과 같다.

`GET /api/trips/{trip_id}/trip-items/{trip_item_id}`

사용자의 `작업 진행` 위임에 따라 최소 continuation으로 선정된 범위이며, URI 선택을 사용자의 별도 명시 승인으로 표현하지 않는다. 이는 저장 replay의 historical result와 현재 item 상태를 분리하기 위한 read seam이다.

## 3. 범위

### 포함

- 기존 `AuthenticatedUser` principal 사용.
- 현재 trip membership 확인과 비노출 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE` 처리.
- 현재 member라면 ended trip도 읽기 허용.
- target trip에 속한 한 `TripItem`의 현재 상태를 하나의 coherent SQL snapshot으로 조회.
- 응답 필드: `trip_item_id`, `place_id`, 현재 `state` (`BASKET` 또는 `SCHEDULED`), `schedule_date`, `sort_order`, 현재 `plan_version`.
- item이 없거나 삭제됐거나 target trip에 속하지 않으면 `404 TRIP_ITEM_NOT_FOUND`.
- 기존 backend service/controller와 관련 테스트만 변경.

### 제외

- basket 전체 목록, pagination, search, delete, schedule mutation.
- authentication provider, token, login, provisioning 선택.
- Android, real SSE, event/request ledger, mutation, version 증가.
- schema, migration, dependency, public deployment 변경.

추가 source layer나 일반 repository abstraction은 만들지 않는다. GET 자체는 외부 운영 data, application schema, domain row를 쓰지 않고 mutation endpoint가 아니다. 검증 시에는 기존 승인된 isolated PostgreSQL runner가 task 소유의 임시 role/schema/fixture를 만들고 정리하는 범위만 허용한다. public, TASK14, attribution 불명확 resource는 변경하지 않는다.

## 4. 확정 계약

| ID | 결정 | 근거 |
| --- | --- | --- |
| M1-003-D01 | operation은 `GET /api/trips/{trip_id}/trip-items/{trip_item_id}`다. | 사용자의 `작업 진행` 위임에 따른 최소 continuation 선정 |
| M1-003-D02 | 인증은 기존 `AuthenticatedUser` principal을 사용하고 body의 user ID를 받지 않는다. | 기존 backend 인증 경계 |
| M1-003-D03 | trip 부재와 현재 비구성원은 동일한 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`로 외부에 비노출한다. | 기존 non-disclosure 계약 |
| M1-003-D04 | 현재 member는 ended trip의 item을 읽을 수 있다. creation-only ongoing/upcoming 제한을 read에 적용하지 않는다. | current-state read 범위의 명시 결정 |
| M1-003-D05 | item 부재, 삭제, target trip 불일치는 `404 TRIP_ITEM_NOT_FOUND`다. | current-state read 범위의 명시 결정 |
| M1-003-D06 | 응답은 현재 item과 현재 trip plan version을 한 coherent SQL snapshot에서 읽는다. | current-state recovery purpose |
| M1-003-D07 | 조회는 mutation, plan version 변경, request ledger, event/SSE를 만들지 않는다. | bounded scope |

### 응답 shape

```json
{
  "trip_item_id": "<uuid>",
  "place_id": "<uuid>",
  "state": "BASKET",
  "schedule_date": null,
  "sort_order": null,
  "plan_version": 13
}
```

`state`는 `schedule_date`와 `sort_order`가 모두 NULL이면 `BASKET`, 둘 다 값이 있으면 `SCHEDULED`다. 이 TASK는 상태 이동이나 값 보정을 하지 않는다.

## 5. 변경 경로와 영향

| 경로 | 완료된 변경 | 영향 |
| --- | --- | --- |
| `BasketSaveService.java` | 단일 current-state read와 authorization query | read only, transaction write 없음 |
| `BasketSaveController.java` | GET route와 response mapping | authenticated REST surface 하나 추가 |
| `BasketSaveControllerTest.java`, `BasketSavePostgresIntegrationTest.java`, `RealJdbcProbe.java` | success, authorization, not found, ended read, snapshot/read-only 검증과 isolated fixture cleanup | 6개 추가, 전체 33개 VERIFIED |

Auth provider, Android, OpenAPI 파일, dependency, application schema와 migration은 변경하지 않는다. GET은 domain data write와 mutation이 없다. 검증 실행에서만 기존 승인된 isolated PostgreSQL runner의 task-owned temporary role/schema/fixture setup과 cleanup을 사용할 수 있으며, public, TASK14, attribution 불명확 resource는 변경하지 않는다. machine-readable contract는 기존 controller/test surface에만 둔다.

## 6. 실패 동작과 일관성

- authentication principal이 없거나 유효하지 않으면 기존 `401 AUTHENTICATION_REQUIRED` envelope을 사용한다.
- trip이 없거나 principal이 현재 member가 아니면 item 존재 여부를 확인하지 않고 `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`를 반환한다.
- member 확인 뒤 item이 없거나 삭제됐거나 다른 trip의 item이면 `404 TRIP_ITEM_NOT_FOUND`다.
- ended trip의 member read는 허용한다.
- SELECT snapshot 외에 item, trip, request execution, event를 쓰지 않는다.
- API 응답의 item fields와 `plan_version`은 같은 read consistency boundary에서 취한다. 구현이 별도 query를 사용한다면 coherent snapshot을 증명하는 결정적 test가 필요하다.

## 7. 결정적 수용 기준과 검증 결과

| ID | 초기 상태 / 행동 | 기대 결과 | 상태 |
| --- | --- | --- | --- |
| AC-01 | 인증된 current member가 존재하는 basket item을 조회 | 200, item id/place id, `BASKET`, NULL date/order, 현재 plan version | VERIFIED |
| AC-02 | 인증된 current member가 schedule item을 조회 | 200, `SCHEDULED`, date/order, 현재 plan version | VERIFIED |
| AC-03 | 인증된 member가 ended trip의 item을 조회 | creation restriction과 무관하게 200 | VERIFIED |
| AC-04 | anonymous 또는 invalid principal이 조회 | 401 `AUTHENTICATION_REQUIRED` | VERIFIED |
| AC-05 | 없는 trip 또는 nonmember가 조회 | 동일한 404 `TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`, item 정보 미노출 | VERIFIED |
| AC-06 | member가 없는 item, 삭제된 item, 또는 다른 trip의 item을 조회 | 404 `TRIP_ITEM_NOT_FOUND` | VERIFIED |
| AC-07 | 정상 조회 전후 DB를 비교 | item, plan version, request ledger, event 변화 0 | VERIFIED |
| AC-08 | item과 plan version이 같은 transaction snapshot에서 관찰되는 경합 조건을 검증 | 서로 다른 시점의 item/version 조합을 반환하지 않음 | VERIFIED |

테스트는 기존 backend controller boundary와 real PostgreSQL integration surface를 사용했다. fixed sleep이나 polling으로 동시성을 입증하지 않았다. RED는 28 tests에서 GET expected 200이 404였고, GREEN은 33 tests, 0 failure/error/skip이었다.

## 8. 실제 증거

| 검사 | 결과 | 증거 |
| --- | --- | --- |
| 구현 | VERIFIED | [source-scope.json](../../.omo/artifacts/m1-foundation-003/source-scope.json) |
| backend tests | VERIFIED | [final-verification.json](../../.omo/artifacts/m1-foundation-003/final-verification.json), 33 tests, 0 failure/error/skip |
| build / bootJar | VERIFIED | final verification, bootJar exit 0 |
| PostgreSQL verification | VERIFIED | PostgreSQL 18.6 (`180006`), fresh non-superuser role/owned schema, cleanup `0|0`, unrelated prefix `4|4` preserved |
| TDD first failure | VERIFIED | [tdd-first-failure.json](../../.omo/artifacts/m1-foundation-003/tdd-first-failure.json), [log](../../.omo/artifacts/m1-foundation-003/logs/tdd-first-failure.log). RED 28 tests, one GET expected 200 but got 404 |
| Full runner | VERIFIED | `powershell.exe -NoProfile -ExecutionPolicy Bypass -File backend/src/test/scripts/Run-PostgresIntegration.ps1`, GREEN 33 tests, 0 failure/error/skip, auth 3, controller 6, real PostgreSQL 24, bootJar exit 0 |
| Diagnostics | VERIFIED | Java LSP errors 0 |
| Auth boundary | VERIFIED | test-only principal for positive real PostgreSQL tests; actual loopback HTTP checks 401. Production provider remains absent |
| Authored scope | VERIFIED | `BasketSaveController.java`, `BasketSaveService.java`, `BasketSaveControllerTest.java`, `BasketSavePostgresIntegrationTest.java`, `RealJdbcProbe.java`; no authored schema, migration, dependency, runner, or Android changes |

## 9. Blocker와 인계

인증 provider/login 선택은 이 read seam의 blocker가 아니었다. production authentication provider는 여전히 없다. positive tests는 test-only principal과 real PostgreSQL을 사용했고, 실제 loopback HTTP checks는 401 경계를 확인했다. provider, Android, full basket list, pagination을 이 TASK에 추가하지 않는다.

실제 명령은 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File backend/src/test/scripts/Run-PostgresIntegration.ps1`이다. authored 변경은 controller, service, 관련 tests이며 schema/migration/dependency/runner/Android 변경은 없다. 검증 runner의 task-owned temporary role/schema/fixture setup과 cleanup만 사용됐고 public, TASK14, attribution 불명확 resource는 변경하지 않았다. m1-002 artifact의 latest runner pointer와 `backend/build`는 generated side effects이며 이 TASK의 authored scope가 아니다.

## 10. 복구와 후속

코드·테스트·문서 변경이 완료됐다. GET은 domain write가 없고, isolated test fixture는 정리됐다. 복구가 필요하면 scoped code recovery와 기존 POST 보존만 수행하며 DB data rollback을 가정하지 않는다. 후속 경쟁 작업은 만들지 않고 [PLAN.md](../PLAN.md)의 단일 슬롯을 유지한다.
