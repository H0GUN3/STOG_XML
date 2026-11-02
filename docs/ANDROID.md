# Android 책임과 UI 계약

상태: 승인된 Android 작업의 운영 계약. 문서에 적힌 제품 기능은 구현 완료를 의미하지 않는다.

2026-11-02 monochrome amendment: shared Material colors use neutral tones and the existing STOG asset supplies both in-app and launcher branding. Bundled Pretendard 1.3.9 replaces platform sans in shared theme and Material appearances; see `NATIVE-AUTH-UI-001` for weights, license and bounded Pixel evidence. Signed-in screens were not rechecked without a session.

2026-11-02 UI repair: 기존 native shell에 light/night surface 색상, 여행 목록·상세와 장소 검색 24dp gutter, 하위 화면 뒤로 가기와 basket 우선 상세 구성을 적용했다. Pixel 8에서 기존 계정의 Home → 여행 목록 → 상세·빈 표지 → 검색·뒤로 가기를 관찰했다. 검색어 `cafe`는 결과가 없어 장소 상세와 basket 저장은 이번에 검증하지 않았다.

## 이름과 책임

- Application ID와 namespace는 위임된 개발 기본값 `com.stog.app`이다. test, manifest, resource에 두 번째 식별자를 만들지 않는다.
- Java type suffix가 책임을 나타낸다. `Activity`는 navigation host, `Fragment`는 한 화면 view, `ViewModel`은 복원 가능한 화면 상태, `Adapter`는 list row binding, `Repository`는 실제 data access가 있을 때만 그 접근을 조정한다.
- XML resource 이름은 Android lowercase snake_case를 사용한다. 화면은 `fragment_<feature>.xml`, list row는 `item_<entity>.xml`을 우선하며 공급자가 아니라 의미로 이름을 정한다.
- 공급자별 `Place`, 지도, 바구니, 저장 계층을 만들지 않는다. 공통 `Place`와 같은 `TripItem`의 바구니/일정 상태는 제품 불변조건이다.

## 화면과 class 경계

Activity는 app/navigation host를 제공한다. Fragment는 화면을 inflate·render하고 사용자 동작을 전달하며 ViewModel을 관찰한다. ViewModel은 화면 상태를 보유하고 data source를 호출한다. Repository는 실제 network, DB, cache 접근이 있을 때만 도입하며 View를 소유하지 않고 architecture 모양을 맞추려고 빈 객체로 만들지 않는다.

비동기 또는 복원 상태가 있는 화면은 ViewModel의 observable state에 LiveData를 사용한다. 서로 모순될 수 있는 여러 loading/error/content flag보다 하나의 불변 screen-state 값을 우선한다. saved state에는 작은 ID, query/selection/filter, 복원에 필요한 사용자 입력만 두며 View, bitmap, response, token, 비공개 file을 넣지 않는다.

## Fragment view lifecycle과 View Binding

1. `onCreateView`에서 binding을 inflate하고 root를 반환한다.
2. view가 생긴 뒤 listener와 view-lifecycle 관찰을 연결한다.
3. LiveData는 Fragment lifecycle이 아니라 `getViewLifecycleOwner()`로 관찰한다.
4. view가 파괴될 때 view 소유 image/list/render 작업을 취소하고 adapter/listener를 분리한다.
5. `onDestroyView`에서 binding 참조를 `null`로 만들고 이후 접근하지 않는다.

Fragment 객체는 back stack에서 살아 있어도 view는 사라질 수 있다. ViewModel, Repository, Adapter, static field, 비동기 callback에 Activity/Fragment/View/binding 참조를 보관하지 않는다.

## UI 상태와 복원

각 비동기 화면은 필요한 상태만 만들되 다음 의미를 구분한다.

- **Loading:** 최초 또는 명시적 refresh 진행 중이며 중복 제출을 막는다.
- **Content:** 사용할 수 있는 data를 표시한다.
- **Empty:** 요청은 성공했지만 item이 없으며 network 오류와 구분한다.
- **Error:** 내부 정보를 노출하지 않고 실패를 설명하며 유효한 경우만 retry를 제공한다.
- **Retry:** 멱등 read를 반복하거나 같은 mutation `request_id`를 재사용하며 중복 변경을 만들지 않는다.

뒤로 가기는 이전의 의미 있는 상태로 돌아간다. 필요한 query, result, 선택한 stable ID, scroll, 미확정 사용자 선택을 보존한다. process recreation에서는 원본 data를 다시 읽고 저장한 ID를 대조한다. 오래된 비공개 payload를 복원하거나 SSE를 저장소로 취급하지 않는다.

## XML, list, image, 접근성

- XML은 hierarchy, constraint, semantic, focus order, 접근성 label을 정의한다. Material widget/theme과 resource reference를 사용한다.
- string, plurals, color, dimension, drawable, style은 resource로 둔다. configuration change를 지원하고 pixel 상수와 사용자 문구 이어붙이기를 피한다.
- Recycler 계열 list는 제한된 item model과 필요한 stable identity를 사용한다. diff update가 순서나 사용자 선택을 조용히 지우면 안 된다.
- row는 크기에 맞는 thumbnail을 요청하고 loading/missing/error를 표시하며 오래된 load를 취소한다. server file path나 권한 없는 비공개 media URL을 노출하지 않는다.
- 장식 이미지는 읽을 label을 두지 않고 정보·동작 이미지는 의미 있는 content description을 둔다. error/loading은 색만으로 전달하지 않는다.

## Threading, network, cancellation, resource

- main thread에서 disk, network, image decoding, 대량 parsing을 하지 않는다. 최종 observable UI state는 main thread에 전달한다.
- 향후 HTTP client는 화면마다 만들지 않고 재사용한다. 대체된 검색과 view-scoped request는 취소하거나 request identity로 오래된 결과를 무시한다.
- 가장 좁은 owner에 작업을 묶는다. rendering은 view lifecycle, ViewModel 작업은 clear 시점에 끝난다. 영속 upload는 승인된 WorkManager 작업에서만 추가한다.
- page, list, image, retry, request 시간, memory, log에 승인된 계약의 상한을 적용한다. bootstrap 문서에서 운영값을 발명하지 않는다.
- authorization header, token, 개인 GPS, 비공개 media, raw caption, server secret을 log에 남기지 않는다.

## 제품 상태 경계

- 계획 변경은 `expected_plan_version`과 stable `request_id`를 보내고 transaction을 유지하며 승인된 server 결과로만 local state를 갱신한다. commit 후 SSE와 재연결은 REST 재조회를 유도한다.
- Place import/search 결과만으로 TripItem을 만들지 않는다. 명시적 저장 성공 후 생성하며 TripItem은 복사하지 않고 바구니와 일정 사이를 이동한다.
- 링크/검색/바구니/gallery/view 동작은 CELL을 활성화하지 않는다. 실제 STOG camera 기록 또는 사용자가 시작한 활성 기록 session 방문만 가능하다.
- 비공개 여행 media는 metadata, thumbnail, file, 대표 이미지 모두 작성자/여행 구성원 authorization 후 표시한다.

## Android native credential 인증

Android는 Java/XML native auth gate에서 `POST /api/auth/signup`과 `POST /api/auth/login`만 호출한다. signup은 아이디, 비밀번호, 표시 이름을 보내며 login은 아이디와 비밀번호만 보낸다. 아이디는 trim + `Locale.ROOT` lowercase 뒤 ASCII 4-32자 canonical pattern을 검사하고, 표시 이름은 1-40 NFC code point/ISO control 금지, 비밀번호는 trim/lowercase 없이 8-128 code point 및 1024 UTF-8 byte 상한을 client-side에서 먼저 확인한다. 회원가입 화면의 비밀번호 확인은 local-only이며 request, saved state, log, screenshot에 포함하지 않는다. provider SDK, callback activity, provider key, provider UI는 현재 Android flow에 존재하지 않는다. 보존된 Kakao backend identity/session/domain data는 Android가 호출하거나 link하지 않는다.

STOG access token은 memory-only, refresh token은 Android Keystore AES/GCM ciphertext만 private preferences에 둔다. backup/device transfer에는 포함하지 않는다. process recreation은 refresh로 복원하고 decrypt/key failure, refresh failure와 replay는 local credential을 지우고 signed-out으로 간다. logout은 usable refresh가 없거나 server revoke가 실패하면 local material을 지우되 revoke-unconfirmed를 표시한다. native submit과 refresh/logout은 generation으로 이전 completion을 무효화한다. password는 submit 직후, mode change, logout, view recreation에서 지운다.

Release base URL은 HTTPS만 허용한다. Debug는 `adb reverse`용 literal `127.0.0.1` HTTP만 앱 validation과 API 24+ network-security config에서 허용한다. API 23은 network-security config를 지원하지 않지만 앱 validation이 다른 cleartext/host를 거부한다. signed-in Android shell은 actual Home과 내 여행 두 root만 제공한다. Home은 existing `/api/trips` state를 그대로 표시하며 fabricated count/card를 만들지 않고, 내 여행은 existing `TripListFragment`와 create/detail/member/place flow를 유지한다.

## Android current TripItem read

Signed-in normal product shell에는 UUID diagnostic card를 보이지 않는다. `AuthApiClient` current-item transport는 focused test/debug seam으로만 남으며 memory access Bearer로 기존 endpoint를 호출하고 첫 `401`에서 serialized refresh 한 번과 GET retry 한 번만 허용한다. refresh 실패나 retry `401`은 local credential을 지우고 auth-ended/signed-out으로 전환한다. newer read, logout, cancellation, close는 generation으로 이전 callback을 무효화해 stale content나 credential revival을 막는다.

하나의 observable state가 loading, content, invalid input, non-disclosing trip 404, item 404, retryable error, auth-ended를 렌더링한다. Activity-scoped owner와 Android view state가 recreation에서 result와 UUID 입력을 유지하되 token이나 response payload를 saved state에 넣지 않는다. 성공 content는 서버가 반환한 item/place/state/date/order/version만 표시하고 mutation, list, schedule 또는 SSE를 만들지 않는다.

## 승인된 R1 My Trips Android 계약

[MVP-TRIP-001](tasks/MVP-TRIP-001.md#9-legacy-presentation과-android-계약)은 fixed backend wire 뒤 Java/XML `TripList -> TripCreate -> TripDetail`을 기존 FragmentManager back stack과 shared auth transport에 구현하도록 승인한다. 한 trip feature state owner가 pages, stable trip ID/scroll anchor, selected ID, create draft와 unresolved normalized intent/request ID를 소유한다. token, View와 full response는 saved state에 넣지 않고 detail/basket은 ID로 다시 읽는다.

불명확한 create 결과는 같은 normalized tuple/request ID로 retry하고 입력 변경·확정 종료·discard 뒤 새 intent만 새 ID를 쓴다. loading/content/empty/retryable/auth-ended를 분리하며 newer request/logout/auth-ended는 stale callback과 private trip state를 지운다. Back은 create/detail에서 loaded list/anchor로 복귀하고 root list에서는 system 동작을 따른다.

Legacy nullable trip metadata는 화면에서만 `제목 없음`/`지역 미정`으로 표시하고 DB/API 값을 꾸미지 않는다. empty basket을 명시하며 기존 ID-only Place row는 `장소 정보 없음` fallback만 렌더링한다. name/address/image/search/non-empty product card 완료를 가장하지 않는다. exact state/retry/Back/scroll/RED/real-device matrix는 TASK §9-11이 소유한다.

현재는 `IMPLEMENTED / ANDROID EXACT GATE + INTEGRATED REAL-LOCAL VERIFIED`다. correction으로 detail legacy nullable parsing, 작은 draft/pending/selected/anchor saved state, bounded page-depth/cursor recovery, nested Back와 logout teardown guard가 구현됐다. 실제 package `com.stog.app.auth.TripFlowUiTest`를 사용한 최종 Android exact gate는 owned Pixel_8 API 36.1에서 JVM, 지정 instrumentation 24/24, Lint와 assemble을 통과했다.

Verifier-owned `RealBackendTripProductUiTest`와 runner는 isolated V1-V3 PostgreSQL과 실제 backend에서 provider exchange, create response-loss/replay exactly-one, detail/empty·ID-only basket, light/dark, landscape 1.3 font, focus/IME inset, Back, access refresh, member/nonmember 비노출과 최종 logout/no-private-content 및 crash scan을 통과했다. PNG 10개를 육안 검사했고 ID-only UUID는 generated fixture ID이며 token/credential/private user ID는 없다. final cleanup은 role/schema `0|0`, metadata 불변과 모든 owned runtime/reverse/settings 복원을 확인했다. 원래 API/UI preimplementation behavioral RED 부재와 이전 실패는 소급 복구하지 않는다. [검증 artifact](../.omo/artifacts/mvp-trip-001/final-verification.json).

## 승인된 R2 Android 계약

2026-10-06 최신 사용자 결정으로 STOG가 소유한 단일 host `MainActivity`는 manifest `portrait`로 고정한다. device가 양쪽 landscape로 회전해도 STOG 화면은 portrait를 유지하며 lifecycle recreation 검증은 방향 전환과 분리한다. system document picker의 방향은 STOG가 강제하지 않는다.

[MVP-TRIP-002](tasks/MVP-TRIP-002.md)은 기존 TripViewModel, FragmentManager와 shared auth transport 위에 R2a를 먼저 구현한다. 두 role에 trip edit을 제공하고, ADMIN에게만 member 관리/invitation 공유·중지·재발급/delete, MEMBER에게만 leave를 표시한다. exact custom URI `stog://trip-invitations/{43-char-token}`은 strict ACTION_VIEW와 raw/full-URI paste로 join하고 ACTION_SEND `text/plain`으로 공유한다. token은 memory에만 두고 saved state/log/evidence에 남기지 않으며 public App Link는 추가하지 않는다. Issue 응답 유실은 process가 같은 raw token/request ID를 가진 동안만 replay하고 process death 뒤 server token recovery를 기대하지 않는다.

Member UUID는 transport target으로만 사용하고 화면 label/content description에는 표시하지 않는다. 현재 global profile field가 없어 join 때 1-40 code point trip-scoped member label을 받고, `나` 또는 label+role로 렌더링하며 legacy null/duplicate만 stable ordinal을 덧붙인다. edit/member/join/delete는 stable request ID와 version conflict REST reload, recreation/Back/stale callback/auth-ended/private-state cleanup을 TASK §9대로 지킨다. 자신이 관찰한 leave/remove/delete 뒤 local list cursor를 버리고 fresh first page를 요청하되, backend의 다른 R1 cursor는 사라진 anchor의 signed raw tuple로 계속될 수 있음을 오류로 처리하지 않는다.

R2a Android는 `IMPLEMENTED / LOCAL VERIFIED`다. RecyclerView LayoutManager 누락으로 CONTENT(2)가 rows0가 되던 blocker와 nested scroll에서 행 컨트롤에 도달하지 못하던 문제를 고쳤다. 최종 member list는 bounded native RecyclerView를 사용하며, test는 programmatic position 이동이나 listener 직접 호출 없이 64dp 실제 swipe, 전역 90% visibility와 Espresso tap을 유지한다. font 1.30 portrait/landscape에서 `나`와 `민지` 각각의 역할·제거 버튼과 정확한 확인 dialog를 열고 취소하는 표적 4/4 및 16개 화면 검수를 통과했다. 변경 Android final gate는 JVM 16개, Pixel_8 instrumentation 33/33(`CollaborationApiClientTest` 포함), Lint/assemble을 통과했고 crash/settings/app/emulator cleanup도 확인했다. 기존 rows0, 세 차례 reachability 실패와 clean-start fixture 수명 실패는 역사 증거로 보존한다.

R2b는 `ACTION_OPEN_DOCUMENT` native picker로 JPEG/PNG 하나를 고르고 bounded private copy/preview 뒤 명시적 저장 확인으로 foreground multipart upload한다. URI grant는 copy 동안에만 확보하고 즉시 release하며 persistent/background grant/session, camera/custom gallery/WorkManager는 없다. API 23 orientation을 pixels에 반영하는 최소 `androidx.exifinterface` 외 새 image/network framework를 도입하지 않는다. list는 authenticated thumbnail, detail은 authenticated display file만 off-main/bounded decode하며 memory cache는 logout/member removal/delete/auth-ended 때 지운다. R2b는 Android JVM16/instrumentation44/Lint/assemble과 native picker -> real corrected backend/V1-V5 actual E2E를 통과해 `IMPLEMENTED / LOCAL VERIFIED`다. Expected green/blue pixels, replacement response-loss replay, private denial, native remove/no-op와 logout cleanup 증거는 `.omo/artifacts/mvp-trip-002/r2b/final-verification.json`에 있다.

## 승인된 R3a Android 계약

[MVP-TRIP-003](tasks/MVP-TRIP-003.md)은 portrait `MainActivity`, existing `AuthApiClient`, Activity-scoped `TripViewModel`과 FragmentManager 위에 `TripDetail -> PlaceSearch -> PlaceDetail`을 추가한다. Search/detail/save exact states, normalized draft/filter, stable result IDs, bounded page depth/cursor, selected Place/trip/version/request intent와 Back/recreation/process recovery는 TASK §9가 정본이다. SavedState에 token/full response/coordinates/source data를 넣지 않고 newer query/trip change/logout은 stale callback을 무효화한다.

R3a는 local missing-image and missing-coordinate states만 렌더링하며 remote image/map/network provider를 호출하지 않는다. 이는 v1.4 §4.7의 representative image fallback과 shared map marker/detail을 완료했다는 뜻이 아니며 full R3 debt로 유지한다. `바구니에 저장`은 ADMIN/MEMBER 모두 existing basket endpoint를 stable request ID로 호출하고 conflict 뒤 current detail/basket을 reload한 다음 명시적 재확인을 요구한다. Search/detail selection만으로 TripItem/CELL/AI가 생기지 않는다.

현재 Android 구현은 JVM16/instrumentation49/Lint/assemble과 light/dark/font-1.30 local fixture 및 actual V1-V6 flow에서 `LOCAL VERIFIED`다. 17-symbol compiler 실패는 compile/scaffold RED이며 behavioral RED가 아니다; preimplementation behavioral RED는 `NOT_VERIFIED`다. 이후 actual first-page run은 outer `auth_scroll` 안 `wrap_content` result list가 continuation을 받지 못하는 valid behavioral RED를 만들었다. R2와 같은 bounded native RecyclerView viewport로 수정한 뒤 center-bounded gestures가 exactly-one continuation을 발생시켜 21개를 렌더링했고, common detail ID, dropped-201 stable retry, basket reload, Back/recreation와 logout까지 통과했다. Full R3 shared map/detail과 production catalog UX는 계속 별도 dependency다.

## 기술 도입 조건과 보류 상세

| 기술 / 동작 | 도입 조건 | 보류 결정 |
| --- | --- | --- |
| CameraX | 승인된 M3 camera 작업에 촬영·local file·권한·metadata·upload·privacy 기준이 있을 때 | camera/session 상세, media 보관 |
| Room | saved UI state로 해결할 수 없는 영속 local record/offline queue가 승인됐을 때 | schema, encryption, 용량, 보관, 제거 |
| WorkManager | 영속적이고 지연 가능한 upload/retry가 승인됐을 때 | constraint, backoff, cancellation, uniqueness. 지속 GPS에는 사용 금지 |
| Mapbox | 계정·무료 조건·강제 무과금 통제가 VERIFIED이고 지도 작업이 승인됐을 때 | token, style, 상한, offline/data 조건 |
| Location foreground service | 기록 작업이 사용자 start/stop, 버전별 권한, notification, cancellation, 실기기 검사를 정했을 때 | 권한 문구, service lifecycle, queue/sample 상한, 중첩/재부팅/종료 |

H3는 server-only다. routing, Gemini/STOBEE, Instagram/social 연동, public endpoint, 위치 권한/service/queue 상세값은 보류하며 bootstrap stub로 존재하는 것처럼 만들지 않는다.
