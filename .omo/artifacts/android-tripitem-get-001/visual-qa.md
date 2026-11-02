# Android TripItem GET visual QA

Result: PASS for the scoped Pixel_8 API-36 fixture surface.

Direct inspection covered six 1080x2400 PNGs in `screenshots/`: successful content, post-refresh content, missing-trip/nonmember wording, missing-item wording, recreation at 1.30 font scale, and signed-out/no-stale-content. The live Material component tree remains XML/View Binding; no screenshot or raster substitute was added. Existing theme colors, typography, spacing dimensions, rounded cards, and minimum-height buttons are reused.

Observed checks:

- Content fields and nullable schedule/order values are visible and wrap inside the card; no token/provider credential appears.
- Trip and item UUID controls have persistent visible labels. UUID values intentionally remain single-line horizontally scrollable input rather than wrapping into ambiguous identifiers.
- The non-disclosing trip error and distinct item error fit at 1.30 font scale without clipping.
- Recreation retains both IDs and content. The scroll container supports the taller large-font surface.
- Logout returns to the original auth card and the TripItem card is absent, so stale private content is not visible.
- Alpha/background, logo, card strokes, contrast, and system-bar insets appear intact in all captures.

No reference mock or pixel-identical baseline exists, so pixel-diff similarity is not applicable. Automated dual-oracle dispatch was unavailable in this child harness; direct image inspection plus the real UI assertions are the recorded visual gate. TalkBack, physical-device rendering, API 23/37 runtime, and CJK copy are `NOT_RUN`.
