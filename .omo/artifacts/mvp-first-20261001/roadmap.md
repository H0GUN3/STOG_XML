# MVP-first 구현 로드맵 (제안)

작성일: 2026-10-01  
상태: `PROPOSED / NOT_IMPLEMENTED`. 이 문서는 정본 PLAN/TASK/API를 바꾸지 않는 구현 순서 제안이다. 최신 사용자 지시인 **product MVP FIRST**에 따라 public/TLS 선행은 재개하지 않는다.

## 기준, 범위, release 용어

v1.4의 기능 요구는 `CONFIRMED`이지만, **어느 범위가 첫 release인지는 `product-scope.md`가 명시한 PROPOSED grouping**이다. 이 로드맵은 용어를 다음처럼 구분한다.

- **첫 수직 증분(R1):** create/list/detail/empty-basket만 검증하는 구현 단위다. usable MVP나 release 완료가 아니다.
- **첫 usable planning MVP (PROPOSED, R0-R4):** 인증된 사용자가 이름·대표 이미지·기간·지역을 가진 여행을 공동으로 만들고, 자체 Place를 찾고, basket과 날짜별 일정을 안전하게 편집하는 첫 release 후보다. Instagram은 자체 검색과 대체 관계인 사용자 여정이지만 v1.4의 우선 external-link 요구로서 R5에 보존한다.
- **later full-v1.4 milestones (R5-R9):** Instagram import, map execution, record/CELL, Discover/MY, STOBEE를 순서대로 완료한다. planning MVP에 넣지 않았다고 삭제·완료·미승인으로 바꾸지 않는다.

제품 전체는 로그인/UUID 조회만이 아니라 여행 공동 계획부터 장소 발견, 실제 여행 기록, 공개 기록, 주변 탐색까지의 하나의 제품 여정이다. 아래 표는 v1.4의 모든 제품 영역을 구현 상태와 순서에 연결한다. `구현됨`은 해당 좁은 재사용 경계만 뜻하며 단계 전체 완료를 뜻하지 않는다.

| 제품 요구 묶음 | 기준 | 현재 재사용 가능 | 구현 단계 / 외부 차단 |
| --- | --- | --- | --- |
| 인증, 보호 API, Java/XML 앱 | v1.4 §5.1, §9; README/PLAN | Kakao local 인증, authenticated principal, Android token/refresh, current TripItem GET은 제한된 local 검증 완료 | **R0**에서 재사용. real provider+protected-domain/prod HTTPS E2E는 별도 미검증이며 MVP 기능 착수 차단 아님 |
| 여행 생성·목록·상세·역할·초대·삭제/나가기 | v1.4 §4.3, §5.2, §5.6 | `trip`, `trip_member`, `plan_version`과 membership 비노출 read 경계 | **R1** 생성/목록/상세/빈 바구니, **R2** 초대·역할·삭제/나가기. 초대 재발급/재사용 세부는 사용자 결정 필요 |
| 공통 Place, 자체 검색, 상세 지도 | v1.4 §4.7, §5.4, §5.8, §7 | ID-only `place`, existing TripItem basket save | **R3**. 장소 데이터 수집·정규화·좌표/이미지 품질이 외부/운영 전제; 현재 검색 기능 없음 |
| 바구니·날짜 일정·재정렬·협업 충돌 | v1.4 §4.3.1-2, §5.3, §5.7, §8.1 | 하나의 `TripItem`, version/request-id/transaction, one-item GET | **R4**. real SSE transport는 이후 read-recovery와 함께; REST 원본 규칙은 재사용 |
| Instagram 공유/붙여넣기와 확인 저장 | v1.4 §1.3, §4.3.3/5, §13.1, §14 | 공통 Place/TripItem 저장 모델만 | **R5**. 허용 URL·약관, 비용 0원, Playwright/Chrome 격리, SSRF, retention 확인 전 실제 연결 금지; Kakao/Naver는 후속 |
| 지도 중심 여행 실행·경로 | v1.4 §4.1, §5.8, §6.1, §6.7, §10 | 공통 Place/TripItem 순서 계약 | **R6**. Mapbox/TMAP의 무료 한도·과금 차단·약관 확인 전 provider 연결 금지 |
| 카메라·기록 세션·CELL·사진 동기화 | v1.4 §4.2/4.2.1, §5.5, §6.4-6.6 | server-only H3, CELL 활성화/비공개 media 불변조건 | **R7**. CameraX, local durable queue, storage 비용/권한, foreground service, 실기기 검증 필요 |
| 발견·MY·공개/비공개 media·EXP | v1.4 §4.5-6, §5.5 | 사진별 공개와 여행 권한 규칙 | **R8**. media 저장/인증 파일 API/retention이 선행; EXP 정밀 곡선은 미확정 |
| STOBEE GPS 주변 탐색/나의 지도 | v1.4 §4.4, §6.3, §9.2 | 공통 Place와 지도/CELL 경계 | **R9**. Gemini 모델·free tier·billing 및 GPS permission 확인 전 AI 연결 금지 |

공통 제약: Java/XML, Fragment, View Binding을 유지하고 공통 `Place`와 같은 `TripItem` 상태를 재사용한다. 계획 mutation은 principal authorization, `plan_version`, stable `request_id`, atomic transaction, commit 뒤 알림과 REST 재조회를 유지한다. 검색/링크/바구니/열람은 CELL/EXP를 만들지 않고, private media 권한은 API·thumbnail·원본에 동일하게 적용한다. [v1.4 §2, §5.3-5.5, §6.5, §9]

## 순서화된 단계와 수용 기준

### R0. 기존 기반을 제품 경계로 고정
- **범위:** 현 local auth, PostgreSQL, protected GET, Java/XML host를 재사용 경계로 확인한다.
- **제외:** public endpoint, TLS, 새 provider, real-user mutation, SSE transport.
- **의존:** 이미 기록된 local evidence만 사용한다.
- **관찰 수용:** sign-in 뒤 보호 화면, logout 뒤 보호 요청 거부라는 기존 범위가 회귀 없이 유지된다. 새 여행 기능의 완료로 표시하지 않는다.

### R1. 첫 수직 슬라이스 - 내 여행을 만들고 빈 바구니를 연다
- **범위:** 아래 “첫 수직 슬라이스 계약” 전체.
- **제외:** 장소 검색/추가, 초대, edit/delete, non-empty basket, schedule, provider/public 접근.
- **의존:** R0; backend JSON 계약이 Android 착수의 선행이다.
- **관찰 수용:** 인증 fixture 사용자가 여행을 생성하고 목록에서 한 번 보며 상세와 빈 바구니를 연다. replay는 같은 여행을 반환하고 nonmember는 존재를 알 수 없다.

### R2. 공동 여행 수명주기와 대표 이미지
- **범위:** 여행 수정, 진행중/예정/지난 분류, 10분 신규참여 초대 링크, ADMIN/MEMBER 권한, delete/leave, 대표 이미지 변경과 R1 목록/detail contract의 확장. 대표 이미지는 R1에서만 defer하며 R2에서 authenticated media identity/authorization 및 display를 완료한다; R7의 기록 사진은 이 contract를 재사용한다.
- **제외:** 기간 단축 때 schedule 처리, 검색/지도/import, remote URL/외부 provider를 cover source로 직접 신뢰하는 경로.
- **의존:** R1 trip identity·role·version; 초대 token expiry와 membership mutation transaction; 비용 0원 authenticated media storage/file API contract.
- **관찰 수용:** 유효 링크 두 사용자가 한 여행을 편집하고 만료 링크 신규 참여는 거부한다. ADMIN에게 leave, MEMBER에게 delete가 UI/API 모두 허용되지 않는다. authorized creator/member만 cover를 변경·열람하며 nonmember는 thumbnail/original을 얻지 못한다. 동시 edit은 조용한 overwrite 대신 최신 version recovery로 끝난다.

### R3. 공통 Place 카탈로그와 자체 검색
- **범위:** additive Place display/location/category/source 모델, 자체 DB ingest/normalization, name/region/category search, 공통 지도/상세, basket 저장 진입.
- **제외:** Instagram 자동 수집, AI 추천, provider별 Place 저장소, 좌표 추측.
- **의존:** R1 선택 여행; 공공 관광/상권 데이터의 허용·품질·정규화와 PostGIS/pg_trgm 검증.
- **관찰 수용:** 검색 결과·marker·상세·저장이 같은 Place를 가리킨다. back은 query/result/scroll을 복원하고 없음·network·missing coordinate/image를 구분한다.

### R4. 계획 편집과 협업 회복
- **범위:** full basket read/delete, basket↔scheduled move, 날짜별 sort/reorder, numbered map projection, conflict/retry UI, authenticated SSE transport와 REST recovery.
- **제외:** 자동 일정/동선 최적화, 기간 단축 정책, CELL 생성.
- **의존:** R3 displayable Place; existing version/request-id invariants and R2 membership.
- **관찰 수용:** 성공한 이동 뒤에만 basket에서 사라지고 실패면 원상태다. reorder는 종료 시 한 번 저장하며 번호/지도/동선이 일치한다. 동일 request ID 재전송은 중복 mutation을 만들지 않고 SSE 유실은 REST read로 회복한다.

### R5. Instagram 우선 import
- **범위:** Android share/paste, URL validation, backend async job/status/evidence/candidate matching, explicit user choice, selected Place만 basket save.
- **제외:** Kakao/Naver links, cookies/login/CAPTCHA bypass, comments/OCR/audio, media repost, automatic LLM fallback/automatic plan or CELL change.
- **의존:** R3 Place matching, R4 save/version; Instagram allowed-use, free-cost, browser isolation, SSRF, timeout/concurrency/expiry and raw-content retention decision.
- **관찰 수용:** accepted URL은 job state를 단계별로 보이고, address 없음·후보 없음·access 제한·caption mismatch·unresolved Place를 구분한다. 확인 전 domain data는 바뀌지 않으며 선택 후보만 idempotently 저장된다.

### R6. 여행 실행 지도
- **범위:** 홈 여행 카드→지도/bottom sheet 실행, 날짜 선택, scheduled index markers/line, common Place detail, viewport/zoom bounded read.
- **제외:** 3D, car/transit routing, unrestricted downloads.
- **의존:** R4 schedule projection; Mapbox and optional TMAP zero-cost/terms validation.
- **관찰 수용:** 진행중 여행의 선택 날짜 marker·번호·순서가 일치하고, sheet가 열려도 map 조작과 Back/date transition이 일관된다.

### R7. 실제 기록과 CELL
- **범위:** CameraX capture, text/public choice, device-first photo/location persistence, authenticated sync/retry, start/stop/end travel record session, foreground tracking, server H3/CELL, cell detail/latest representative photo.
- **제외:** Android H3, search/import-driven activation, unverified background behavior.
- **의존:** R6 map; media storage/access contract, foreground permission/service design, queue bounds and real-device scenarios.
- **관찰 수용:** pending upload와 server-confirmed CELL이 구분되고, lock/unlock recording continues only while active; stop 후 새 sample은 CELL을 만들지 않는다. duplicate upload does not duplicate CELL/first EXP.

### R8. 발견과 MY
- **범위:** per-photo visibility, public feed/post/detail, likes/comments/profile, selected Place basket save, MY metrics/grid/map toggle, level shell.
- **제외:** final EXP curve, private media public exposure, all-place bulk save.
- **의존:** R7 authorized media/CELL; media file/thumbnail/original authorization and retention.
- **관찰 수용:** private travel media cannot be retrieved by a nonmember through any media surface. Only user-selected photo/place is public/saved; server metrics match MY.

### R9. STOBEE 주변 탐색
- **범위:** current-GPS query, at most five explanation-bearing cards, matching markers, Place save, route presentation, my-map ON/OFF.
- **제외:** GPS fallback fabrication, automatic AI on Place selection, sending raw GPS/private media/identity to AI.
- **의존:** R3 Place, R6 map, R7 user location rules; Gemini account/model/free-tier/billing and privacy validation.
- **관찰 수용:** GPS available gives matching cards/markers; no GPS or AI quota shows an explicit limited state while normal search/planning remains usable. Toggle changes only layer visibility.

## 첫 수직 슬라이스 계약 (R1)

**목적:** authenticated user가 local backend를 통해 여행을 생성하고 My Trips 목록에서 상세/빈 basket까지 연다. 이는 full MVP를 대체하지 않는 가장 작은 FE+API+DB increment다.

### API/DB contract-first

1. **Additive V3:** existing applied V1/V2는 수정하지 않는다. `trip`에 nullable `name`, `region_label`을 더하고 기존 real rows를 backfill/rename하지 않는다. creation request에서는 nonblank를 요구한다. cover는 media identity/authorization이 없으므로 제외한다.
2. **Create:** `POST /api/trips` uses authenticated principal only, creates `Trip(plan_version=0)` and creator `ADMIN` atomically. Stable `request_id` replays same normalized input/result; changed input returns `409 REQUEST_ID_REUSED`. Creation has no expected version and creates no SSE.
3. **Reads:** member-only `GET /api/trips`, `GET /api/trips/{id}`, `GET /api/trips/{id}/trip-items?state=BASKET`. Absent/nonmember must retain `404 TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`; basket is a single coherent read with top-level plan version, never N one-item GETs.
4. **공학 기본값 (사용자 결정 아님):** 새 create의 `name`은 NFC-normalized 1-100 code points, `region_label`은 NFC-normalized 1-100 code points의 nonblank text로 제한한다. list/basket `limit`은 1-50, default 20; cursor는 opaque keyset이며 trip cursor는 `as_of_date`/status bucket/sort date/trip ID를 묶고 basket은 `created_at DESC,id DESC`를 쓴다. legacy nullable row는 DB에서 보존하고 목록·상세에는 `제목 없음`/`지역 미정` fallback만 표시하며 edit 전 실값으로 바꾸지 않는다. cover source는 authenticated file-media ID만 허용하고 remote URL은 허용하지 않는다. DTO/error field names are set in the canonical implementation TASK before coding.

### Android contract and acceptance

- Existing signed-in shell routes to `TripList -> TripCreate -> TripDetail` via the existing FragmentManager back stack; create one actual state owner/client extension, not a new navigation/DI stack.
- Preserve create draft, selected trip ID, list stable identity/scroll anchor across recreation; reread detail by ID. Back restores the prior list state.
- Render loading/content/empty/retryable/auth-ended distinctly. Retry an ambiguous create with the same request ID; logout/new request rejects stale callbacks.
- **Observable acceptance:** generated isolated principal creates a trip; repeated same request displays one trip; detail renders name/dates/region/role/member count/version and empty basket; rotation, 1.3 font, Back and retry preserve the stated state; nonmember list/detail/basket reveal nothing; cleanup leaves pre-existing DB aggregates unchanged.

### Disjoint ownership and RED-to-gate plan

| 순서 | 소유 lane | 산출/의존 | 의미 있는 RED와 GREEN |
| --- | --- | --- | --- |
| 1 | Manager contract | V3 fields, DTO/error/cursor/limits, legacy-row policy in one canonical TASK/API/DATABASE update | Contract test expects create/list/detail/basket semantics before implementation; no Android coding on an unstated DTO |
| 2 | Backend+DB | V3, controller/service, PostgreSQL fixtures/tests; depends on 1 | Existing tests first fail: create 404/absent fields, replay creates second trip, nonmember disclosure, basket list/read inconsistency; GREEN proves atomicity, authorization, pagination and owned-fixture cleanup |
| 3 | Android | Java/XML fragments, transport/state/navigation; depends on stable backend response shapes from 2 | UI/state tests first fail for create-to-detail, same-request retry, auth-ended, recreation/Back; GREEN uses exact state signal before user action, bounded await, no sleep/polling |
| 4 | Verification | depends on 2+3 | Run backend PostgreSQL+bootJar once, Android unit/instrumentation/lint/assemble once, then a new bounded generated local Android→backend→PostgreSQL real-surface runner and native device visual/recreation matrix |

The whole-session gate is required before any `VERIFIED` claim: backend runner, Android gate, and the new first-slice real-local runner must pass; device QA covers portrait/landscape recreation, light/dark, 1.3 font, empty/content/error, keyboard/insets, Back, and accessibility focus. It creates no public listener, TLS, provider, account, or real-user data action.

## User-only decisions and deferred infrastructure

User confirmation is needed only for: invite regeneration/reuse and non-admin issuance; trip duration-shortening treatment; intentional same-Place repeat policy; raw location/caption and media retention; final EXP curve; and approval/account terms for each external provider/storage/public release. Name/region limits, cursor bounds, legacy nullable presentation, and cover source are the minimal engineering defaults above, not a further user questionnaire. Existing v1.4 explicitly leaves several of the remaining policies undecided.

Infrastructure-first/public access is deferred through R1-R9: no stage needs public exposure to establish its local product contract. Provider/TLS/public work becomes a feature dependency only where an approved external provider or release surface actually requires it.
