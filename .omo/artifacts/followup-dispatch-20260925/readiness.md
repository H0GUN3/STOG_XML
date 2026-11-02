# Provider-neutral authentication-path readiness and assignment brief

**Status (2026-09-25): PROPOSED / BLOCKED.** This is an execution handoff, not approval to select a provider, create a TASK, connect an external service, alter schema, or implement login. `docs/PLAN.md:68-70` requires precise user input for the authentication provider and internal account/provisioning model before either provider choice or an implementation TASK.

## Established implementation boundary

The existing protected API boundary is provider-neutral and must remain so:

- `BasketSaveController.current` and `.save` accept only Spring Security `Authentication`, require an `AuthenticatedUser`, and pass `user.userId()` to the service; neither accepts a caller-supplied user ID (`backend/src/main/java/com/stog/backend/BasketSaveController.java:28-38`, `:40-53`).
- `BasketSaveService.currentItem` joins `trip_member` using that principal ID before exposing an item. No joined trip/member row produces the non-disclosing trip 404; an authorized trip with no matching item produces the separate item 404 (`backend/src/main/java/com/stog/backend/BasketSaveService.java:29-45`).
- Mutating save similarly locks/checks the trip and current membership before replay or domain work (`BasketSaveService.java:59-72`), and its request-execution scope is `(principal_user_id, command_type, request_id)` (`:137-145`; `docs/DATABASE.md:35-37`).
- The API contract forbids `user_id` in body, path, or query and specifies `401 AUTHENTICATION_REQUIRED` when a valid principal is absent (`docs/API.md:7-10`, `:28-30`, `:82-84`). `docs/BACKEND.md:16-25` owns the same fail-closed/non-disclosure boundary.

This seam is verified only with test-only positive principals; it is not a production authentication implementation. The historical M1-003 evidence reports 33 tests, zero failures/errors/skips, PostgreSQL 18.6 verification, and `bootJar` exit 0 (`.omo/artifacts/m1-foundation-003/final-verification.json`). No fresh execution is claimed here.

## Missing work and mandatory policy decision

The missing front-door behavior is provider selection, login/callback, external-token validation and refresh, server credential form/issuance/revocation or session choice, logout, first-user provisioning, Android login integration, and the OpenAPI security scheme (`docs/BACKEND.md:18`; `docs/API.md:129-134`; `docs/PLAN.md:35,64`). These are policy decisions, not gaps to fill by adding a request `user_id`.

**Required user decision package before implementation assignment:**

1. Approved identity provider(s) and permitted Android login method/callback ownership.
2. Internal account identity and first-login provisioning/linking policy, including collision and account recovery ownership.
3. Server credential model: token versus server session, validation/refresh location, issuance/expiry/revocation, logout semantics, and the resulting OpenAPI security scheme.
4. Zero-cost eligibility, account/access prerequisites, data shared with the provider, retention/deletion obligations, and explicit no-paid-fallback behavior.

Until all four are decided, preserve the fail-closed principal seam; do not add provider SDKs, callback endpoints, credentials, User schema fields, migrations, Android network/login UI, public server exposure, or a new TASK. This is exactly the PLAN blocker, not a recommendation to bypass it (`docs/PLAN.md:68-70`; `AGENTS.md:73-79, 91-96`).

## Ordered backend assignments (all blocked pending the decision package)

| Order / owner | Scope | Prerequisites | Acceptance |
| --- | --- | --- | --- |
| B0 / product-security owner | Record the four policy decisions above in an approved implementation TASK; name the security scheme without coupling domain APIs to a provider. | User decision package. | TASK states provider-neutral principal mapping, data/cost/retention policy, callback and recovery ownership, logout/revocation, exclusions, and executable verification; no implementation occurs in B0. |
| B1 / backend | Adapt the approved credential flow to produce only the existing internal `AuthenticatedUser`/`principal_user_id` boundary. Keep domain controller/service signatures and body/path/query free of `user_id`. | B0; approved provider access/credentials and local test configuration. | Missing/invalid credentials stop before trip lookup with the existing 401 envelope; valid credentials map deterministically to one internal principal; no domain authorization regression. Verify controller/security integration, including no principal leakage in logs. |
| B2 / backend | Implement the approved first-login provisioning/linking, renewal/expiry/revocation, and logout/session semantics at the authentication edge only. | B1; B0's account identity, lifecycle, retention, recovery, and schema decision. | Repeated approved login resolves the defined internal account; expired/revoked/logout credentials fail closed; provisioning conflicts follow the approved policy; no caller-selected principal and no unauthorized User/data disclosure. Any schema migration requires its explicit approved TASK scope. |
| B3 / backend | Publish the approved security scheme and login/logout callback contract, then run a real-surface integration verification using the chosen approved test environment. Do not add unrelated travel, basket-list, schedule, or SSE operations. | B1-B2; approved callback/public-exposure decision and test tenant/environment. | Machine-consumed contract matches runtime responses; protected existing POST and GET retain current 401 and membership/non-disclosure semantics; successful credential flow reaches the existing principal seam; credentials/tokens are absent from logs/evidence. |

## Ordered Android assignments (all blocked pending B0 and backend contract availability)

| Order / owner | Scope | Prerequisites | Acceptance |
| --- | --- | --- | --- |
| A1 / Android | Add only the approved login entry, redirect/callback handling, and screen-state flow in existing Java/XML/Fragment/View Binding architecture; no provider-specific domain models or empty Repository layer. | B0; Android provider configuration/callback ownership; B3's finalized machine-consumed security contract. | Login, loading, recoverable error, cancellation, and signed-out states are distinct; lifecycle-safe rendering uses `getViewLifecycleOwner()` and binding is released in `onDestroyView`; no token/private data is put in saved state or logs (`docs/ANDROID.md:18-32, 48-54`). |
| A2 / Android | Implement approved credential storage/refresh/logout behavior and authenticated HTTP handoff to the existing backend routes. | A1; B1-B3; approved Android storage/retention policy and base URL configuration. | Requests carry the approved credential mechanism, never `user_id`; 401 transitions to the approved signed-out/re-auth path; retry preserves the same mutation `request_id`, and cancellation/obsolete requests cannot render stale state (`docs/ANDROID.md:48-60`). |
| A3 / Android + backend | Execute end-to-end login/logout and protected current-item/save recovery on the approved non-production surface. | A2; B3; approved test identity/account and environment. | Valid user reaches authorized existing API behavior; invalid/expired/logged-out user receives existing 401 behavior; non-member remains indistinguishable from missing trip (404); no credential, private GPS, or media leaks to logs/evidence. |

## Shared acceptance and blockers

- Preserve the current backend scope: the verified routes are only basket POST and one current-item GET; full basket read/delete, travel CRUD/read, schedule mutation, real SSE transport, and Android product networking remain unimplemented (`docs/PLAN.md:36-38`; `docs/BACKEND.md:60-64`).
- Android must not access PostgreSQL directly (`docs/BACKEND.md:7`) and must retain common `Place`/`TripItem` semantics rather than provider-specific stores (`docs/ANDROID.md:8-12`).
- Any provider integration must prove zero-cost controls before connection; no paid fallback is permitted (`AGENTS.md:73-79`). Provider credentials, tokens, private data, and callback secrets must not enter source, ordinary logs, fixtures, screenshots, or evidence (`AGENTS.md:82-89`).
- The current Android baseline is not login-ready: it has no product network/data connection (`docs/PLAN.md:37`) and Android technology additions require the conditions in `docs/ANDROID.md:64-73`.
- **Blocking condition:** no B1/A1 implementation assignment may start until the user supplies the decision package and an approved TASK records it. B0 is a decision-recording assignment only after that input; it does not authorize provider selection by an implementer.

## Evidence consulted

- Current-state TASK and verified source scope: `docs/tasks/M1-SHARED-FOUNDATION-003.md:29-43, 95-125`; `.omo/artifacts/m1-foundation-003/source-scope.json`.
- Principal, authorization, wire, storage, and Android boundaries: `docs/BACKEND.md:14-25, 46-50`; `docs/API.md:5-10, 129-134`; `docs/DATABASE.md:18-37`; `docs/ANDROID.md:8-16, 48-73`.
- Canonical status and mandatory gate: `docs/PLAN.md:31-38, 59-70`.
