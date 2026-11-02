# SCREEN-FLOW-001: 제품 화면 흐름·레이아웃 요구사항

## 1. 식별과 상태

| 항목 | 값 |
| --- | --- |
| 승인 범위 | 문서·디자인 요구사항만 |
| 구현 변경 | 없음 |
| 런타임 주장 | 없음 |
| 상태 | COMPLETE / QA-BY-READ |
| 현재 제품 슬롯 | 변경 없음: `MVP-TRIP-005 R3c` |

## 2. 산출물

[화면 흐름·레이아웃 요구사항](../SCREEN_FLOW_REQUIREMENTS.md)은 v1.4의 홈·내 여행·STOBEE·발견·MY·카메라·CELL·공통 Place 흐름을 저충실도 화면, 전이, 상태, 권한/개인정보, acceptance 기준으로 정리한다.

이 문서는 다음을 분리한다.

- `EXISTING`: 현재 Java/XML authenticated trip/search foundation의 실제 evidence 범위.
- `PLANNED`: v1.4에 있으나 현재 구현/승인이 없는 화면.
- `PROPOSED VISUAL`: 기능을 더하지 않는 디자인 방향.
- `UNDECIDED`: 별도 결정을 필요로 하는 제품 선택.

## 3. 근거와 보존 경계

- 제품 기준: [v1.4 원문](../../reference/STOG_Development_Starter_v0.1/docs/reference/STOG_Android_App_Project_Specification_v1.4.docx)
- 현재 상태/단일 슬롯: [PLAN](../PLAN.md)
- Android 상태·보안 UI 계약: [ANDROID](../ANDROID.md)
- 구현 evidence: [MVP-TRIP-001](MVP-TRIP-001.md), [MVP-TRIP-002](MVP-TRIP-002.md), [MVP-TRIP-003](MVP-TRIP-003.md), [MVP-TRIP-004](MVP-TRIP-004.md), [MVP-TRIP-005](MVP-TRIP-005.md)
- 최신 결정: [EXTERNAL-LOGIN-SEQUENCING-001](EXTERNAL-LOGIN-SEQUENCING-001.md)

외부 로그인 확장은 최종 제품 단계까지 보류한다. 이 문서는 기존 Kakao local auth implementation/evidence를 제거·비활성화하지 않으며, protected screen을 public access로 전환하지 않는다.

## 4. QA-by-read acceptance

1. 모든 주요 v1.4 화면군에 inventory, status, low-fi layout, action/transition, state, privacy 또는 permission 기준이 있다.
2. 바구니와 일정의 동일 TripItem 상태 이동, CELL의 좁은 활성화 조건, private travel media audience를 정확히 구분한다.
3. 현재 구현 foundation과 미래 화면을 동일한 완료 상태로 표시하지 않는다.
4. `MVP-TRIP-005` 단일 구현 슬롯을 유지하며 Android/backend source, DB, DOCX 원본, history evidence를 수정하지 않는다.
5. markdown links와 status statement를 내부 원본과 대조한다.
