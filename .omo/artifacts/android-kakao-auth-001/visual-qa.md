# Android Kakao auth visual QA

Revision basis: final source after `android:forceDarkAllowed=false`. The secure-store recovery-only edit after capture cannot execute in the missing-configuration path, so the four captures are retained as unaffected evidence.

## Coverage

| Cell | Evidence | Result |
| --- | --- | --- |
| Missing config, portrait, light, font 1.0 | `screenshots/missing-config-portrait-light.png` | PASS |
| Missing config, landscape, light, font 1.0 | `screenshots/missing-config-landscape-light.png` | PASS |
| Missing config, portrait, light, font 1.3 | `screenshots/missing-config-large-font-light.png` | PASS |
| Missing config, portrait, dark, font 1.0 | `screenshots/missing-config-portrait-dark.png` | PASS after force-dark fix |

Device: Pixel_8 AVD, API 36, 1080x2400 natural display. UI assertions also ran through `MissingConfigurationUiTest`, including Activity recreation and disabled action.

## Pass A - design-system and functional integrity

PASS. The screen remains a live Material/XML hierarchy using the existing theme, logo, dimensions, Fragment/View Binding lifecycle, and one finite auth state. The disabled missing-config action and actionable copy are real controls/resources, not raster substitutes. Portrait, landscape, large-font reflow, recreation, and dark mode remain usable.

## Pass B - visual fidelity, CJK, accessibility

PASS for the scoped English baseline. All text remains visible without clipping or overlap; large text reflows rather than shrinking; the disabled action remains distinguishable; loading has a content description and changing status text is a polite live region. No Korean copy is shipped in this screen, so CJK glyph review is not applicable. Direct TalkBack operation was NOT_RUN.

No exact external visual reference existed; the existing bootstrap screen was the conceptual baseline. A first dark capture exposed automatic bitmap darkening; disabling force-dark restored the supplied logo without redesign.

## Review-fix configured states

The review fix rendered production UI with debug-only deterministic gateways on Pixel_8 API 36. Success, logout/signed-out, and cleaned exchange-error states passed complete-visibility assertions and direct inspection without changing the baseline visual system. Evidence: `review-fix/configured-ui.md` and `review-fix/screenshots/configured-contact-sheet.png`. Real Kakao/provider UI remains NOT_RUN.
