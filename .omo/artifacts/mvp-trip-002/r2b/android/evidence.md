# R2b Android focused evidence

## Behavioral RED/GREEN

- RED: `com.stog.app.trip.TripApiClientTest#requiredNullableCoverMetadataIsAcceptedFromTheAuthenticatedTripDto` failed on Pixel_8 API 36 before production parsing: expected `SUCCESS`, actual `RETRYABLE_ERROR`.
- GREEN: the same test passed after required nullable `cover` parsing.
- GREEN: `com.stog.app.trip.TripApiClientTest` passed 5/5 on Pixel_8 API 36. `multipartCoverUploadUsesSharedBearerAndMapsSetResponse` uses a harmless four-byte synthetic JPEG transport fixture and verifies Bearer multipart `image` plus JSON `command` parts and SET parsing.

## Producer native/UI proof

- GREEN: `com.stog.app.auth.TripFlowUiTest#nativeCoverPickerCancellationReturnsToThePrivateDetailSurface` opened the actual system document picker, sent Back, and observed the separate picker permission/selection terminal state.
- GREEN: `com.stog.app.auth.TripFlowUiTest#generatedPickerPreviewUploadAndRemoveDialogRenderAcrossConfiguredSurface` used a generated harmless JPEG content URI through the debug-only picker seam. It observed sampled preview, fixture-authenticated multipart upload, authenticated cover file read, and the remove confirmation dialog; font scale was 1.3.
- Screenshots were captured with `UiAutomation.takeScreenshot()` and inspected:
  - `screenshots/STOG-R2b-QA/r2b-preview-portrait-light.png`
  - `screenshots/STOG-R2b-QA/r2b-content-portrait-light.png`
  - `screenshots/STOG-R2b-QA/r2b-content-portrait-dark.png`
  - `screenshots/STOG-R2b-QA/r2b-content-landscape-light.png`
  - `screenshots/STOG-R2b-QA/r2b-remove-dialog-landscape-dark.png`

## Focused gates and cleanup

- GREEN: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.
- LSP reported zero diagnostics for changed main sources and `TripFlowUiTest`.
- `cleanup.txt` records font restoration, generated-image deletion, screenshot-directory removal, absent installed app-private cache, and emulator termination. `attempts.md` preserves the corrected producer failures and exact initial settings error.

Not run: real V1-V5 backend E2E, provider/public/Tunnel/credential/real-user operations.

## 2026-10-06 fresh correction evidence

The post-review source is now covered by a fresh exact Android gate. `review-correction/evidence.md` records behavioral RED/GREEN, asymmetric mirrored EXIF pixels, temp cleanup, delayed selection invalidation for logout/view destruction/trip change, private bitmap clearing after auth/membership denial, and the latest portrait-only decision. JVM 16/16, explicit Pixel_8 instrumentation 44/44, Lint and assemble passed. The test applied device rotations 1 and 3 while STOG remained portrait and kept lifecycle recreation as a separate test. Owned settings/runtime/temp/grants were cleaned. This remains local HTTP fixture evidence, not real backend E2E.
