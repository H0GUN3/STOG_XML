# Current R1 real-local screenshot evidence

The final generated-fixture run passed. All current PNGs were visually inspected:

- `01-member-empty-light.png`: signed-in member empty My Trips, light portrait.
- `02-create-keyboard-focus.png`: populated create form with title focus. The test awaited exact IME inset visibility before capture; the headless-emulator PNG does not show the software keyboard surface.
- `02-response-loss-retry.png`: retained normalized create form after the committed response was deliberately dropped.
- `03-detail-empty-basket-light.png`: replayed trip detail and explicit empty basket.
- `04-detail-landscape-font-1_3.png`: landscape recreation at font scale 1.30.
- `05-detail-dark.png`: dark-mode recreation.
- `06-detail-id-only-basket.png`: generated ID-only basket fallback and plan version 1.
- `07-member-list-exactly-one.png`: system Back restored exactly one generated trip row.
- `08-nonmember-empty-nondisclosure.png`: normal nonmember session with empty My Trips; no member trip is shown.
- `09-logout-no-private-content.png`: final signed-out surface with no private trip content.

No token, provider credential, or private user identifier is visible. Korean metadata and the UUID shown in the ID-only fallback are generated task fixture data. `contact-sheet.png` is the inspected composite of the ten current captures. Historical failed-run images remain under `failed-run/` and are not current success evidence.
