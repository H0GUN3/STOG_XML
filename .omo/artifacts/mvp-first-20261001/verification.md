# MVP-first roadmap source-check

작성일: 2026-10-01  
검증 종류: read-only source reconciliation. Build/test/DB/provider/device/public action은 실행하지 않았다.

## 결과: PASS (계획 근거), NOT_RUN (runtime)

| 점검 | 결과 | 근거 |
| --- | --- | --- |
| 최신 방향이 product MVP FIRST이며 external-access-first를 대체 | PASS | `.omo/artifacts/mvp-first-20261001/notepad.md`의 “This supersedes external-access-first work.” |
| MVP를 tiny UUID slice로 축소하지 않음 | PASS | `product-scope.md` §1, §3, §5가 trip→Place→plan→Instagram→map→record/CELL→discover/MY→STOBEE 전체 여정을 열거; `roadmap.md`는 R1 first increment, R0-R4 PROPOSED usable planning MVP, R5-R9 later full-v1.4 milestones로 전부 매핑 |
| 현재 구현 재사용 경계 | PASS | `README.md`, `docs/PLAN.md` M1 checklist, `docs/{BACKEND,DATABASE,API,ANDROID}.md`: local auth, current TripItem GET, V1/V2, Java/XML만 구현·검증 경계임 |
| 첫 slice와 later planning MVP가 실제 repository gap에 맞음 | PASS | `implementation-gaps.md` “Trip product fields are absent”, “Place is ID-only”, first vertical slice; `docs/DATABASE.md` current Trip은 id/date/version만, Place는 id만 정의. v1.4 §4.3은 name/cover/dates/region을 요구하므로 R1은 cover만 defer하고 R2가 이를 추적 |
| common Place/TripItem, version/idempotency, CELL/media privacy 보존 | PASS | v1.4 §2, §5.3-5.5, §5.7, §6.5; `docs/{BACKEND,DATABASE,API,ANDROID}.md`의 구현된 경계 |
| stage별 scope/exclusion/dependency/observable acceptance 및 release nuance | PASS | `roadmap.md`의 R0-R9 각 항목, R1 contract/ownership table, first increment/PROPOSED planning MVP/later full-v1.4 구분 |
| R1 contract-first, disjoint lane, meaningful RED, whole-session gate | PASS | `roadmap.md` “첫 수직 슬라이스 계약”; test discipline is consistent with `implementation-gaps.md` existing deterministic test neighbors |
| public/infrastructure-first defer | PASS | latest direction plus `product-scope.md` §6 and `docs/PLAN.md` public item 2 `NOT_APPROVED / NOT_RUN` |
| invented completion/approval 없음 | PASS | roadmap status is `PROPOSED / NOT_IMPLEMENTED`; existing evidence is reuse-only and all new runtime work is gate-bound |

## v1.4 citation cross-check

| roadmap coverage | direct source section | checked source fact |
| --- | --- | --- |
| product purpose and full journey | §1.1, §3.1, §10, §11, §13 | product purpose, primary flows, screen connections, dependency order, completion conditions |
| travel/collaboration | §4.3, §5.2, §5.6, §5.7 | §4.3의 name, cover, dates, region 및 detail header; roles/invites and version/request/transaction rules |
| own Place/search/detail | §4.7, §5.4, §5.8, §7.1-7.3 | integrated Place DB, shared save/detail, normalization/search boundary |
| basket/schedule | §4.3.1-4.3.2, §5.7.1-2, §8.1 | one TripItem state, conflict/replay and atomic move rules |
| Instagram | §1.3, §4.3.3/5, §13.1, §14.1-14.13 | Instagram before Kakao/Naver; async backend extraction, user confirmation, exclusion/security/cost/verification conditions |
| map/routing | §4.1, §5.8, §6.1, §6.7 | map-centered execution/common sheet; Mapbox/TMAP conditions and no initial transit commitment |
| camera/CELL | §4.2-4.2.1, §5.5, §6.4-6.6 | device-first record, active session, server H3, no activation from search/import/view, privacy |
| discover/MY | §4.5-4.6 | per-photo public selection, selected-place save, MY metrics; EXP formula remains undecided |
| STOBEE | §4.4, §6.3, §9.2 | current GPS basis, map layer toggle, external AI free-tier/privacy boundary |
| zero cost and stack | §1.4, §9.2-9.4 | no paid fallback; Java/XML + Spring MVC/PostgreSQL retained; provider conditions must be checked |

## missing evidence / unresolved facts (verbatim)

These are not filled with guesses and prevent only the dependent scope, not R1 local implementation. Name/region bounds, cursor bounds, nullable-row presentation, and an authenticated file-media cover source are deliberately recorded in `roadmap.md` as minimal engineering decisions because v1.4 does not set them.

1. `implementation-gaps.md`: “Trip product fields are absent.”
2. `implementation-gaps.md`: “Place is ID-only.”
3. `implementation-gaps.md`: “No suitable trip-create idempotency ledger.”
4. `implementation-gaps.md`: “Pagination is an explicit product-contract gap.”
5. `product-scope.md`: “실제 데이터 수집, 중복 매칭, 결과 상한은 별도 검증한다.”
6. `product-scope.md`: “실제 Instagram 대상 형식, 자동 수집 허용, Playwright와 Chrome 환경, 서버 재검증, URL와 SSRF 방어, 작업 만료와 자원 회수를 확인해야 한다.”
7. `product-scope.md`: “Mapbox 실제 연결, 3D 지도, TMAP 길찾기... 무료 범위, 과금 차단, 혼용 허용, 보관 조건을 확인한 뒤 단계적으로 연결한다.”
8. `product-scope.md`: “Gemini 모델 ID, 무료 한도, 계정 과금 상태가 확인되지 않으면 AI를 MVP 연결 항목으로 실행하지 않는다.”
9. `product-scope.md`: “기간 단축 때 일정 항목을 바구니로 돌릴지, 초대 링크 재발급과 재사용, 의도적인 동일 장소 반복 방문, 원시 위치와 캡션 보관 기간은 별도 결정 전 확정하지 않는다.”
10. `product-scope.md`: “레벨 1에서 100과 단계 수식어의 틀은 요구되지만 정확한 EXP 양과 곡선, 삭제와 재등록 정책은 아직 미확정이다.”

## R1 implementation evidence required later

No later implementation may mark R1 verified without all of the following: (1) behavioral RED evidence before create/list/detail/basket implementation, (2) additive migration plus PostgreSQL authorization/replay/atomicity/pagination/cleanup tests, (3) backend PostgreSQL runner and `bootJar`, (4) Android unit+instrumentation+lint+assemble gate, and (5) a bounded generated-fixture Android emulator→local backend→PostgreSQL run plus device recreation/Back/font/visual/accessibility checks. Fixed sleep/polling is not acceptable; tests subscribe to the exact state before triggering and await it with a bound.

No secret, `local.properties` value, real user row, public listener, TLS/provider/account action, build, test, DB, or device operation was performed while producing these artifacts.
