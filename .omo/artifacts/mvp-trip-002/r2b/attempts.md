# Final real-E2E attempt history

- Attempt 1 reached generated users/trip/membership but the verifier observer subscribed after the initial signed-out state; no cover request occurred. Harness-only correction: current-state check before waiting.
- Attempt 2 reached the same setup but reused a provider credential already exchanged by the direct API client; no cover request occurred. Harness-only correction: distinct UI credential mapped to the same generated owner identity.
- Attempts 3-5 reached the real native document picker and rendered the generated green preview. The first actual cover POST was deliberately response-dropped by the owned proxy and the UI retried the same intent. Both current Android multipart requests reached real Spring and returned `400 INVALID_REQUEST`; proxy facts show statuses `[400,400]` and codes `[INVALID_REQUEST,INVALID_REQUEST]`. No `trip_cover` row committed.
- Backend corrections subsequently closed multipart negotiation and image-error JSON negotiation. Harness-only sequencing fixes moved member removal after replacement, forced fresh test APK compilation, and selected the deleted trip by state rather than Unicode shell text.
- Final run passed the complete native picker/replacement-loss/remove/privacy/cardinality flow and wrote `real-local-e2e-result.json` plus `final-verification.json`.
- Every attempt used a new isolated V1-V5 schema/role/private media root and completed aggregate cleanup. No production source was changed by the verifier.
