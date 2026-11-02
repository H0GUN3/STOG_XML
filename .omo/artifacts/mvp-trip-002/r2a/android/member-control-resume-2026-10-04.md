# R2a member-control final result (2026-10-04)

## Result

PASS. The strict font-1.30 portrait and landscape gesture regressions pass, all 16 control/dialog screenshots were captured and visually inspected, the changed Android final gate passes, and cleanup is complete.

## Root cause and bounded correction

The production containment is the 280dp native RecyclerView in `fragment_trip_members.xml`, which removes the unbounded nested `wrap_content` measurement that prevented a real gesture from making every action 90% visible. The focused test keeps actual 64dp swipe gestures, `isDisplayingAtLeast(90)`, Espresso click and exact dialog assertions. It does not use `scrollToPosition`, direct listener calls, `performClick`, relaxed visibility, fixed sleeps or polling.

The remaining clean-start `AUTH_ENDED; requests=[]` failure was a test fixture lifecycle race: changing font after Activity launch could recreate the Activity, `MainFragment.detach()` removed the fixture Kakao gateway, and the subsequent login click had no exchange request. The final test-only correction subscribes to the application configuration event before changing font scale, waits with a bound, and launches the Activity only after the exact 1.30 configuration arrives. A first attempt that issued the setting without awaiting propagation failed the second baseline test and is preserved as `../logs/member-control-targeted-font-propagation-failure.log`.

## Reachability proof

`TripMembersRenderingUiTest` passed 4/4 on an owned Pixel_8 API 36 emulator. In portrait and landscape it reached `나 / ADMIN` and `민지 / MEMBER`, made each role/removal action at least 90% globally visible through real swipes, tapped it, observed the exact role/removal confirmation dialog, and canceled.

`../screenshots/member-control-proof/` contains 16 stable PNGs, `contact-sheet.png`, and `SHA256SUMS.txt`. Visual inspection confirms the matching labels, roles, actions and dialogs with no UUID, invitation capability, account/provider token, credential or real-user data.

## Final Android delta gate

- Java diagnostics: zero findings on the changed/focused Java files; XML LSP is unavailable.
- JVM: 16/16, failures/errors/skips 0.
- Explicit Pixel_8 instrumentation: 33/33, failures/errors/skips 0, including `TripMembersRenderingUiTest` and `CollaborationApiClientTest`.
- `lintDebug` and `assembleDebug`: PASS.
- Evidence: `../logs/member-control-targeted.log`, `../logs/android-full-gate.log`.

Backend 91/91+bootJar and strict two-user protocol E2E were not repeated because the final delta is Android layout/test synchronization only; their unchanged PASS evidence is reused.

## Cleanup

`member-control-proof-cleanup.json` and `android-gate-cleanup.json` confirm app/test uninstall, reverse removal, font/rotation/animation restoration, clean crash scan, and owned emulator/device/process absence. The earlier real E2E receipt still confirms generated schema/role `0|0`, listeners/processes absent, durable database and DPAPI settings preserved. The unauthorized physical device was untouched.
