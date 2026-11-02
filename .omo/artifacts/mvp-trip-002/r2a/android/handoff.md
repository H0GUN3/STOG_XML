# R2a Android handoff

## Delivered scope

- Existing Java/XML `AuthApiClient` now calls R2a metadata, member, role/remove/leave/delete, invitation issue/revoke, and join routes through its established Bearer + one-refresh transport.
- `TripViewModel` retains unsaved metadata drafts and request IDs for the same ambiguous edit/member intent, reloads detail/member first page after version conflict, and clears only affected trip/list anchor/private invitation state after confirmed leave/delete/self-removal.
- Native Fragment navigation now exposes edit for both roles; member management/share/stop/delete for ADMIN; leave for MEMBER; confirmation dialogs for destructive or role actions.
- `stog://trip-invitations/{token}` ACTION_VIEW and paste paths use one strict parser. The capability stays only in memory, opens an explicit join confirmation after authentication, and is never rendered. Share uses an `ACTION_SEND` `text/plain` chooser with the returned URI only.
- No R2b cover UI, dependency, provider, public link, backend, or schema files were changed.

## Changed app paths

- `app/src/main/java/com/stog/app/auth/AuthApiClient.java`
- `app/src/main/java/com/stog/app/{MainActivity,MainFragment}.java`
- `app/src/main/java/com/stog/app/trip/{InvitationInput,TripViewModel,TripDetailFragment,TripEditFragment,TripMembersFragment,TripJoinFragment,TripListFragment}.java`
- `app/src/main/res/{layout,values}/...`, `app/src/main/AndroidManifest.xml`
- Tests: `com.stog.app.trip.InvitationInputTest` (JVM) and `com.stog.app.trip.CollaborationApiClientTest` (instrumentation local HTTP fixture).

## Verification

- Behavioral RED: `InvitationInputTest` failed before URI parser support, then passed after the strict parser implementation.
- Final `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestJavaWithJavac` passed on 2026-10-01.
- The exact new instrumentation package is `com.stog.app.trip.CollaborationApiClientTest`; it compiled but was not executed because `adb devices` had no owned emulator.

## R2a member-row correction (2026-10-01)

- Root cause confirmed from the preserved real-backend failure: `MembersState.CONTENT` held two members while `trip_members_list` had no `RecyclerView.LayoutManager`, so it attached zero rows. The original failure remains at `../logs/e2e-instrumentation-failure.log` and `../screenshots/failed-run/02-two-member-management-largefont.png`.
- Production correction is intentionally one lifecycle-local line in `TripMembersFragment.onViewCreated`: install AndroidX `LinearLayoutManager` before the existing adapter. Row binding, stable IDs, confirmation actions, and teardown are unchanged. No XML redesign, adapter framework, dependency, backend, wire, provider, or R2b scope was added.
- Exact focused instrumentation class: `com.stog.app.auth.TripMembersRenderingUiTest`.
  - `rendersTwoActionableMemberRowsAtDefaultFontScale`
  - `rendersTwoActionableMemberRowsAtLargeFontScale`
- Deterministic RED: both tests reached the real UI `MembersState.CONTENT` fixture with two entries and failed `member list requires a LayoutManager`; evidence: `../logs/member-row-focused-red-layout.log`.
- GREEN on owned `Pixel_8` API 36: both tests passed in one run. They assert the RecyclerView adapter row count is 2, then render each native row and assert labels `나`/`민지`, roles `ADMIN`/`MEMBER`, enabled+clickable role/remove controls, measured row bounds, and absence of either raw UUID from text/content descriptions. Evidence: `../logs/member-row-focused-green.log`.
- Render evidence was pulled and visually inspected:
  - `../screenshots/member-row-regression/member-rows-font-1.00.png` (`d862fd40d7014052e2d148ef2049a61dee7eedd2d0f7b769c8cd11d38fd37c3f`)
  - `../screenshots/member-row-regression/member-rows-font-1.30.png` (`4e79f03943830783f172830b84abd789a6d569f40cb4f847ea5cdfc3396b2609`)
  - The default render shows both member cards; the 1.30 render shows `민지`, `역할: MEMBER`, `역할 변경`, and `구성원 제거` without clipping or UUID text.
- Existing actual-backend assertion now checks LayoutManager presence, two adapter-backed UI rows, and at least one attached usable native row; the focused class performs the full per-row scroll/render assertions so RecyclerView recycling at 1.30 font is not mistaken for missing data.
- Diagnostics reported no findings for the changed production and instrumentation Java files. `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest :app:compileDebugAndroidTestJavaWithJavac` passed; evidence: `../logs/member-row-build-gate.log`.
- Cleanup completed: app/test APKs uninstalled, on-device screenshots removed after pull, reverse removed, font scale restored to `1.0`, animation scales restored to `1/1/1`, owned emulator/process absent, and crash scan empty. Receipt: `member-row-cleanup.json`.

## Member-control reachability final (2026-10-04)

R2a is locally verified. The clean-start `AUTH_ENDED; requests=[]` failure was a test fixture lifecycle race caused by font-triggered Activity recreation before login. The focused test now waits for the exact application font configuration event before launching the Activity; no fixed sleep or polling was added.

`TripMembersRenderingUiTest` passes 4/4 with unchanged real 64dp swipes, 90% global visibility, Espresso taps and exact dialog assertions for both rows' role/removal actions at font 1.30 portrait and landscape. Sixteen screenshots, inspected contact sheet and hashes are in `../screenshots/member-control-proof/`. The changed final gate passes JVM 16/16, explicit instrumentation 33/33 including `CollaborationApiClientTest`, Lint and assemble. Cleanup and crash scan pass. Exact evidence and preserved failures are in `member-control-resume-2026-10-04.md` and `../final-verification.json`.

Backend 91/91+bootJar and strict two-user protocol E2E remain unchanged PASS and were not repeated for the Android layout/test-only delta. R2b private cover/V5 is next and was not started.
