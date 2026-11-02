# MVP-TRIP-002 R2b Android handoff

## Changed production paths

- `app/build.gradle`: adds `androidx.exifinterface:exifinterface:1.3.7`, necessary for API-23 source-orientation correction before sampled private preview; no media framework was added.
- `app/src/main/java/com/stog/app/auth/AuthApiClient.java`: required nullable cover DTO, authenticated bounded multipart SET/REMOVE and bounded no-redirect image reads over the shared Bearer/refresh-once transport.
- `app/src/main/java/com/stog/app/trip/CoverInput.java`: 8 MiB bounded private temp copy, SHA-256, off-main sampled preview, EXIF orientation, cleanup.
- `app/src/main/java/com/stog/app/trip/TripViewModel.java`: cover intent/retry/version-conflict/terminal states, same-request ambiguous retry, temp/bitmap/auth cleanup.
- `app/src/main/java/com/stog/app/trip/TripDetailFragment.java`: native `ACTION_OPEN_DOCUMENT` JPEG/PNG flow, persisted grant only while copying then release, preview/upload/remove UI, stale view protection, debug-only injected picker URI seam guarded by `BuildConfig.DEBUG`.
- `app/src/main/java/com/stog/app/trip/TripListFragment.java`, `app/src/main/res/layout/{fragment_trip_detail,item_trip}.xml`, `app/src/main/res/values/{dimens,strings}.xml`: detail display/list thumbnail UI and token-based sizing.

## Changed focused test paths

- `app/src/androidTest/java/com/stog/app/trip/TripApiClientTest.java`: behavioral RED/GREEN cover parsing and multipart mapping fixture.
- `app/src/androidTest/java/com/stog/app/auth/TripFlowUiTest.java`: native picker cancellation plus generated-image preview/upload/authenticated-file/remove-dialog UI proof and token-free capture.

## Evidence

See `evidence.md`, `attempts.md`, `cleanup.txt`, and `screenshots/STOG-R2b-QA/`. The local HTTP fixture is faithful only to the Android cover endpoints: it requires the Bearer header, multipart `image` and JSON `command`, returns the exact cover DTO, and serves authenticated JPEG bytes. It is not real V1-V5 backend E2E.

## Boundaries

No canonical documents, backend source, public network/Tunnel, provider, credentials, real user, camera, gallery upload, WorkManager, persistent image/URI/token state, or external media service changed. Full backend gate and real V1-V5 E2E remain verifier work.

## 2026-10-06 review correction and portrait-only decision

- Latest user decision locks only STOG-owned `.MainActivity` to portrait in `app/src/main/AndroidManifest.xml`; Kakao callback and system picker ownership are unchanged.
- `TripDetailFragment` clears fragment-owned downloaded private bitmaps and invalidates late callbacks across auth end, membership denial, missing/loading, replacement and view destruction.
- `CoverInputTest` now validates asymmetric pixels for all four mirrored EXIF orientations plus EXIF-failure temp cleanup.
- `TripFlowUiTest` covers delayed selection after logout/view destruction/trip change and visible-image clearing after auth/membership denial. `TripMembersRenderingUiTest` applies both device landscape rotations and asserts STOG remains portrait; recreation remains separately tested.
- Fresh evidence: `review-correction/evidence.md`. Final gate: JVM 16/16, explicit instrumentation 44/44, Lint and assemble PASS; cleanup complete.
- Android 16 can ignore fixed orientation on large-screen/multi-window configurations. Pixel_8 phone portrait behavior is verified; no unsupported universal large-screen guarantee is claimed.
