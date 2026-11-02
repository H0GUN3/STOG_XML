# Configured fixture UI verification

Pixel_8 AVD API 36 rendered the production `MainActivity`/`MainFragment` with debug-only injected deterministic Kakao/session gateways. No BuildConfig dummy fallback, backend principal, real key, provider, console, or public network was used.

Exact callback-driven sequence:

1. restore pending -> signed out
2. login click -> launching -> exchange -> provider cleanup -> signed in
3. logout click -> revoke -> provider cleanup -> signed out
4. fresh login -> exchange failure -> provider cleanup -> retryable error

Espresso asserted each state and complete title visibility. Captures were taken after a frame-commit signal, not a sleep:

- `screenshots/configured-success.png`
- `screenshots/configured-logout.png`
- `screenshots/configured-error.png`
- `screenshots/configured-contact-sheet.png`

Visual QA: PASS. Existing logo, typography, card, colors, spacing, and button treatment are preserved; all state copy/actions are visible without clipping. The finite transitions are also covered by `ConfiguredAuthUiTest` in the final 9-test instrumentation result.
