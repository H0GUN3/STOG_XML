# Pixel_8 normal-app inspection — 2026-11-02

## Scope and safety

- Built `:app:assembleDebug` with process-local Oracle JDK 17 and without `stogLocalInstrumentationFixture`.
- Installed with `adb install -r`; no app-data clear, AVD wipe, backend start, database write, provider call, credential entry, auth bypass, or product-source edit was performed.
- The Kakao action was deliberately not activated.
- The visible `Pixel_8` emulator and normal STOG app remain open.

## Device and artifact identity

- AVD/window: `Pixel_8`, serial `emulator-5554`
- Android: API 36 / Android 16
- Reported model: `sdk_gphone64_x86_64`
- Display: 1080x2400, density 420, font scale 1.0, locale `ko-KR`
- Package/activity: `com.stog.app/.MainActivity`
- Version: `0.1.0` (`versionCode` 1), minSdk 23, targetSdk 36
- Fresh built and installed APK SHA-256: `40ea7f91a6dd78e7f45b9c221539b061cb81ae56598cd8e5449b44cbfabc501e`
- Build, install, cold launch, and warm relaunch all completed successfully.
- No `adb reverse` mapping or local listener on port 8080 was present.
- Live-process crash/ANR scan: `NONE`.

## Directly observed launch surface

The settled native screen matches the user-supplied screenshot and criticism:

- black square logo with white STOG mark;
- large `STOG` title;
- English subtitle `Travel planning, ready for the next verified step.`;
- English sign-in card;
- one enabled clickable control: `Continue with Kakao`;
- large unused lower area.

The Android UI hierarchy contains exactly one clickable node:
`com.stog.app:id/auth_primary_action`, text `Continue with Kakao`, enabled.

This is the current technical authentication shell. It is not evidence of the
documented future product shell, five-tab navigation, camera FAB, home dashboard,
map, discovery, MY, STOBEE, or the broader 18-screen design specification.

## Interaction and reachable-path result

- System Back from the root auth shell returned to Nexus Launcher.
- Warm relaunch returned to the same signed-out auth shell.
- No local retry, guest mode, offline product route, trip navigation, or other
  recovery control is reachable while signed out.
- The only forward path is the external Kakao action, which was intentionally
  not invoked under this audit's authorization boundary.
- Consequently, travel list/detail/search/basket screens were not reachable and
  are not claimed as inspected in this normal signed-out run.

## Visual evidence reviewed

- `launch-signed-out.png` — settled cold-launch screen; visually reviewed.
- `warm-relaunch-settled.png` — hierarchy-synchronized settled warm relaunch; visually reviewed.
- `launch-hierarchy.xml` and `warm-relaunch-hierarchy.xml` — actual Android UI hierarchy.
- `warm-relaunch-signed-out.png` was captured during a window transition and is
  retained only as pipeline history; it is not used for the visual conclusion.
