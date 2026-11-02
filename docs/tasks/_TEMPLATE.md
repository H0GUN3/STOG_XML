# TASK-ID: 관찰 가능한 결과

> 승인된 작업 하나에 복사해 사용한다. 안내 문구를 실제 사실로 바꾸며 예시 명령·결과를 증거로 사용하지 않는다.

## 1. 식별과 상태

| 항목 | 값 |
| --- | --- |
| 단계 / 기준 절 | M... / v1.4 ...절 |
| 작업 상태 | PROPOSED |
| 승인 상태 | UNDECIDED |
| 구현 상태 | NOT_STARTED |
| 검증 상태 | NOT_RUN |
| 담당 / 검토 | 미지정 |
| 선행조건 | 결정/TASK ID |

**CONFIRMED**는 확정 요구·결정, **PROPOSED**는 미승인 제안, **UNDECIDED**는 미결정, **IMPLEMENTED**는 존재하는 변경, **VERIFIED**는 관찰 증거 있음, **NOT_RUN**은 실행 없음이다.

## 2. 목적과 현재 근거

사용자가 확인할 수 있는 동작 또는 운영 결과 한 문장:

확인한 경로와 현재 동작:

변경 이유:

## 3. 범위

| 포함 | 제외 |
| --- | --- |
| 목적에 필요한 최소 동작과 검사 | 다른 기능, 보류 정책, 추측성 infrastructure, 무관한 redesign |

명시적 비목표:

허용된 외부 호출/data 쓰기/파괴 작업과 환경(보통 없음):

0원 조건과 검증 상태:

## 4. 결정

### 확정

| ID | 결정 | 근거 / 승인 |
| --- | --- | --- |
| D-... | ... | 사용자 결정 또는 규범 절 |

### 제안 또는 미결정

| ID | 상태 | 필요한 선택 / 권장안 | 막히는 범위 |
| --- | --- | --- | --- |
| O-... | UNDECIDED | ... | 의존 작업만 |

TASK 작성자의 제안을 승인 없이 CONFIRMED로 바꾸지 않는다.

## 5. 변경 경로와 영향

| 경로 | 예정/실제 변경 | Data/API/권한/보안/비용 영향 |
| --- | --- | --- |
| 조사 후 기재 | ... | ... |

새 dependency, process, provider, schema, public surface, service: 없거나 명시적 승인과 제거/복구 계획을 연결한다.

## 6. 계약과 실패 동작

해당되는 항목만 기록한다.

- 입력/출력, validation, authentication, resource/media authorization.
- loading, content, empty, error, retry, back navigation, recreation/restoration.
- stable identity, `plan_version`, `request_id`, transaction, commit 후 SSE/re-read.
- threading, cancellation, 상한, timeout, cleanup.
- 유효/무효 저장 상태, 삭제/보관, private data/logging.
- offline/provider 실패와 no-paid-fallback.

실제로 존재하는 machine-readable 계약 경로:

## 7. 수용 기준과 검증 계획

| ID | 관찰 조건 | 검증 surface | 기대 증거 |
| --- | --- | --- | --- |
| AC-01 | 정상 결과 | 실제 public UI/API/명령 | report/artifact/path |
| AC-02 | 해당 empty/error/retry/back/restoration | 같은 surface | 결정적 결과 |
| AC-03 | 권한 없음/잘못된 입력/private data | boundary surface | 거부 및 미노출 |
| AC-04 | retry/conflict/cancellation/failure | boundary surface | 중복·오래된 덮어쓰기·부분 상태 없음 |

Test는 결정적이어야 한다. 비동기 작업은 exact signal/state를 먼저 관찰한 뒤 동작을 시작하고 제한 시간으로 기다린다. fixed sleep은 사용하지 않는다. Fixture/mock은 표시하고 검증 대상 동작을 보존한다.

## 8. 실제 증거

| 명령/동작 | 환경/시각 | 결과 | 증거 경로 / 수용 ID |
| --- | --- | --- | --- |
| 미실행 | 해당 없음 | NOT_RUN | 없음 |

통과 기준:

실패 또는 NOT_RUN과 이유:

Fixture/mock과 live 동작 구분:

Build / Lint / test / install / launch / external / deployment 상태를 각각 기록:

## 9. 검토, blocker, 복구

- [ ] Diff가 포함 범위와 변경 경로에 대응한다.
- [ ] 미승인 기능, dependency, provider, schema, process, public endpoint, 비용이 없다.
- [ ] Authorization, privacy, validation, idempotency, transaction, cancellation, 상한, log를 생략하지 않았다.
- [ ] 관련 diagnostics/test/실제 surface 검사를 실행했거나 명시적으로 NOT_RUN이다.
- [ ] 운영·도메인 문서를 권한 중복 없이 갱신했다.
- [ ] 복구가 구체적이며 code rollback이 data도 되돌린다고 가정하지 않는다.

Blocker, 근거, 필요한 정확한 입력:

Blocker와 무관하게 완료한 안전한 작업:

복구 / data·media 영향:

검토자, 결과, 날짜:

## 10. 완료와 인계

실제 변경 파일:

보안/data/비용 영향:

실제 완료 수준: 문서 / 구현 / local 검증 / external 검증 / 배포.

남은 결정:

후속: 경쟁 제안을 만들지 말고 `docs/PLAN.md`의 단일 제안을 연결한다.

승인, 수용 증거, 검토, 중요한 blocker가 모두 일치할 때만 DONE으로 변경한다.
