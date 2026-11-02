# MVP-first execution

## Binding direction
2026-10-01 user explicitly prioritizes the whole product MVP and authorizes ordered implementation with mass-ulw. This supersedes external-access-first work. Do not resume TLS installation or public exposure by stale goal continuation.

The goal tool refused replacement because an earlier blocked goal remains unfinished. That goal is not completed; use this latest instruction and the session todo as the current execution contract.

## Ideal end state and verification
A specification-grounded MVP journey works through real Java/XML Android screens, backend APIs and PostgreSQL, with authorization, failure/retry, recreation and data integrity preserved. Planning alone, fixture-only happy paths or UUID lookup tooling do not establish product completion.

MVP scope is not invented before reading v1.4 and current product documentation. Existing verified authentication, schema and TripItem operations are reused. External dependencies still require user input when needed; public deployment is not the first milestone.

## Plan and topology
1. Discovery phase: two independent bounded report lanes: product specification/MVP acceptance and existing FE/API/DB implementation gaps. At most two workers run concurrently.
2. Dependent synthesis validates both reports against their sources, proposes a complete prioritized MVP roadmap and first vertical-slice contract. No implementation graph before this evidence.
3. Manager reviews scope; next phase records canonical PLAN/current TASK and dispatches disjoint backend and Android implementation lanes, with contract dependencies and a verification node.
4. Implement successive product increments, meaningful behavioral RED before changes, focused checks during edits and related full test/build/real-surface gates once per large session.
5. Preserve real data/secrets and clean only owned QA resources. Stop for concrete external input when required, not for routine engineering decisions.

## Routing and skills
- mass-ulw: manager runs one graph per phase.
- Product report: writing with doc-to-spec; source requirements and reader-facing acceptance.
- Implementation report: deep-low with ponytail; cross-layer reuse and gaps.
- Synthesis: unspecified-low; bounded prioritization from two source-backed reports.
- Implementation: domain-specific backend and visual-engineering Android lanes after contract.
- Workers read applicable frontend, visual-qa, tdd and design skills for their workflow.
- Preparation LIGHT; schema/auth/concurrency implementation HEAVY.

## Now
Start two discovery lanes followed by source-checking synthesis.

## Evidence
No new build, provider, DB or public-runtime action performed.

## Discovery dispatch
Run `dag_9d2c67e5-7041-44ed-beb2-e8b33a6053c9` started with product and implementation lanes in parallel, followed by synthesis. Reports: product-scope.md, implementation-gaps.md, roadmap.md, verification.md.

User additionally confirmed product-feature priority explicitly. Stale infrastructure goal must not cause TLS/tool installation to resume.

## Discovery accepted
All three nodes completed. Manager read product-scope.md, implementation-gaps.md, roadmap.md and verification.md in full. Planning evidence only, no new runtime claim.

Product sequence: R1 create/list/detail/empty basket; R2 collaboration/trip lifecycle/cover; R3 shared Place catalogue/search; R4 basket/schedule/SSE recovery; R5 Instagram; R6 map execution; R7 camera/record/CELL; R8 Discover/MY; R9 STOBEE. R1 alone is not MVP completion; planning-MVP grouping is an implementation milestone, not deletion of remaining v1.4 requirements.

## Next phase: canonical contract
One deep-low contract writer resolves bounded engineering choices against current DTO/schema patterns, then one quick verifier checks exact API shapes, bounds, cursor ordering, idempotency, additive migration and Android acceptance. Their writes are sequential. No code until this phase passes.

The contract must distinguish newly created trip empty basket from legacy nonempty basket with ID-only Place, preserving data without inventing display fields. Existing v1/v2 rows remain untouched; nullable product metadata is explicit. Cover is deferred only from R1 and tracked in R2.

## Contract accepted and R1 implementation phase
Contract DAG dag_4744a0ca-60a3-4143-ad69-f33803a1e3d9 completed. Manager read canonical MVP-TRIP-001, contract-summary and contract-verification plus PLAN's current slot. Contract QA passed; implementation remains unverified.

Next graph: backend (deep-high: transaction/idempotency/cursor invariants cross process/schema boundary) and Android (visual-engineering: actual Java/XML product flow) have disjoint code/artifact write scopes and run together. One dependent deep-low verification node owns final full gates and real Android/backend/PostgreSQL proof, updates canonical evidence after producer completion. Maximum two simultaneous workers; no separate planner or reviewer panel.

No source author may silently change canonical wire. Targeted behavioral RED/GREEN belongs to each implementation lane; final verifier runs each full suite/build once after inputs settle, repeats only on a fix. Native QA and fixture cleanup are mandatory.

R1 implementation run started: `dag_cd428442-f83e-4dda-8f6b-b9a4407398c0`, backend and android parallel, verify depends on both. Session todo now enumerates R2-R9 to prevent dropping full-product requirements after R1.

## R1 midpoint review
Manager read producer handoffs and actual TripViewModel.java. Backend targeted tests/migration handoff exists. Android first delivery omitted required pagination, persisted small state/process recreation, differentiated create failure/pending-intent handling and stale selection guards. Sent targeted correction to android node and informed verifier; R1 not accepted. Canonical UI test example package differs from actual auth.TripFlowUiTest; verifier must use actual class.

## R1 correction round
First integrated result was BLOCKED, not accepted. Backend full PostgreSQL/bootJar passed. Verifier's Android run used wrong UI test package and predates completed state corrections. Read actual latest AuthApiClient: legacy detail still incorrectly requires non-null metadata. Amended same DAG: Android fixes legacy parsing and required basket keys with regression RED; dependent verifier uses actual auth.TripFlowUiTest, authors its assigned real-backend harness/test, reruns changed Android gate and real surface. Backend node cached; no duplicate backend full gate.

Original Android API/UI pre-implementation behavioral RED was not captured; preserve that historical limitation, do not relabel scaffold/late tests as original proof.

## R1 runtime recovery round
Corrected Android full gate hung in existing ConfiguredAuthUiTest after 14/21 tests; bounded 1200s run was terminated and cleaned. Integrated test/harness now exists and compiles but did not run. Actual TripListFragment still lacked anchor save/restore wiring; full page/process restoration not implemented.

Same DAG amended: android routed to deep-low for root-cause debugging and state restoration, with focused formerly-hung-test proof before any full rerun. Verifier reuses unchanged backend gate and now owns execution of compiled integrated harness after Android passes. Do not weaken old tests or label the partial stage complete.

## Integrated auth recovery
Full corrected Android gate now PASS (23 instrumentation tests), backend unchanged full gate PASS reused. R1 still BLOCKED: isolated V3 real backend exchanged synthetic token and committed one identity/session/exchange, but app test timed out awaiting SIGNED_IN. No trip create request reached backend. Cleanup receipt reports isolated roles/schema and owned processes removed.

Manager read current final report and verifier test/proxy/AuthViewModel. Amended only verifier for focused response/state timeline diagnosis; reuse green full gates, fix owned harness faults or return precise production root cause. Do not declare product success from backend session creation alone.

## Integrated Back navigation fix
Proxy framing root cause fixed with Python stdlib and bounded keep-alive RED/GREEN. Real isolated Android/backend/PostgreSQL now passes authentication, empty list, create invalid input, committed response loss then same-request replay, exact detail/empty basket and landscape/font/dark recreation. DB shows one trip/member/create ledger for two create requests.

Actual system Back from nested detail exits Activity; MainActivity had no primary-navigation/back dispatch setup. Manager inspected source and real generated-fixture screenshot. Same DAG amended for minimal native Android Back fix and dependent full changed Android gate plus remaining real E2E; backend green reused.

## Logout teardown recovery and next-stage default
Real R1 passed Back and refresh after fixes, then failed logout: cleared adapter list still had layout position 0 during captureAnchor in onDestroyView. Manager read actual guard and amended Android+dependent verification for bounds/auth-ended/private-anchor safety only. Other source/gates reused where unchanged.

User timed out on R2 invite reissue and delegated best judgment: new link invalidates old link; existing members retained. This is a delegated development default, not a individually selected answer. Preserve v1.4 10-minute invitation expiry.

## R1 accepted; R2 next
Manager read final-verification.json in bounded chunks, cleanup-receipt.json, screenshot README and actual contact-sheet. Final real flow passes create loss/replay with one trip, detail, empty and ID-only basket, Back, refresh, member/nonmember nondisclosure and logout; final Android 24/24 instrumentation plus JVM/lint/assemble passed. Isolated role/schema and owned runtime removed; real data preserved.

Actual captures are in screenshots/, not a post-fix subdirectory. IME visibility asserted but software keyboard absent in headless PNG. Original Android API/UI behavioral-first RED remains historically NOT_VERIFIED. R1 local product increment complete, full MVP not complete.

R2 proceeds via new contract phase then new implementation phase. Contract must cover collaboration, permissions, lifecycle and cover without assuming public hosting/provider integration; may sequence cover after membership/lifecycle as R2b without dropping it. Routine engineering choices documented, no needless user questionnaire.

## R2 contract correction
R2 contract DAG returned NEEDS_CORRECTION, not implementation approval. Manager read verification and cited canonical sections: command-scoped cover ledger vs blob uniqueness conflict, R1 cursor continuation vs R2 deletion invalidation conflict, invitation plaintext response replay vs hash-only claim, malformed token validation/status precedence. Amended same contract graph for precise correction plus recheck. No product/runtime actions occurred in this phase.

## R2a implementation approved from corrected contract
Manager read final contract verification PASS, contract summary and exact membership/invitation sections. Preserve R1 raw cursor continuation with current visibility filtering, no new visibility generation. Invitation caller generates a canonical random token, backend stores lookup hash and nonsecret replay recipe, reconstructs only from same caller token; no plaintext/ciphertext token store. Cover command ledger is command-scoped with exact no-op replay.

R2a collaboration/lifecycle V4 runs before R2b private cover V5. New phase graph: disjoint backend deep-high and Android visual-engineering producers, dependent deep-low real integration verifier. Backend owns focused new invariants tests; final verifier owns full gates and two-user actual UI/backend tests, no redo of unchanged standalone investigations.

## R2a first verification
Implementation run dag_a3dc2010-c6f6-4842-9d82-d8ba4c4eca34 returned production blocker, not completion. Backend91/91 and Android29/29 passed; real flow reached issue/join/shared editing/promote/demote/reissue/stop. Member management actual UI showed 0 rows for CONTENT2. Manager read Java and XML: RecyclerView LayoutManager absent. Amended Android for native minimal fix with real row assertion and dependent verifier for remaining strict UI scenarios. Backend gate reused unchanged; R2b not started.

## R2a final usability proof
Corrected protocol/real-E2E PASS and Android31 tests plus JVM16. Manager read bounded final JSON, actual member portrait/landscape screenshots and focused test: rows measured with clickable flags but actual offscreen controls had no user-tap proof. Amended verifier only for real scrolling/tapping both members' role/removal dialogs at 1.30 font portrait/landscape, dismissing without destructive action. No unchanged backend/full protocol rerun. If reachable, close evidence gap; if not, return exact UI root cause.

## R2a native scrolling blocker confirmed
Real gesture proof failed owner removal click: less than 90% globally visible, outer ScrollView plus nested wrap-content RecyclerView. No test threshold reduction accepted. Same graph amended for minimal native containment/measurement correction by Android and final targeted real gesture proof plus changed Android gate by verifier. Prior backend/protocol successes retained; final UI usability remains blocked until fixed.

## 2026-10-04 resume
User explicitly requested resume with mass-ulw. Snapshot confirms Android failed on 2026-10-01 with `Codex error: The usage limit has been reached`; verifier skipped, backend completed. Checked current member layout; did not assume any partial changes passed.

Retried only android on existing run dag_a3dc2010-c6f6-4842-9d82-d8ba4c4eca34; dependent verifier restored. Preserve backend cached result and previous RED/evidence. No provider/model cost escalation or external/public action.

## 2026-10-04 R2a accepted
Manager reviewed complete bounded final-verification.json and actual member-control contact sheet: real swipes/taps open both members' role and removal dialogs at font1.30 portrait/landscape, no direct click bypass. JVM16/instrumentation33/lint/assemble PASS; backend91 and two-user protocol E2E reused unchanged. Owned emulator/settings/apps/reverse cleaned, real data preserved. R2a done, R2 overall remains open.

## R2b next phase
Implement private trip cover per MVP-TRIP-002 sections6-10: backend ImageIO validation/sanitization, private authenticated media/storage and V5; Android native picker, bounded thumbnail/memory loading and versioned image mutation state. Disjoint code lanes plus dependent actual upload/read/replace/delete/revoked-access verifier. No CDN, object store, camera recorder, new provider or public host. Generated images and temporary storage only for QA.

## R2b worker authentication failure
Run dag_5c760b8a-1c61-4166-a39d-4cca5a5bd8e3 snapshot: backend running; android failed before report with `Your authentication token has been invalidated. Please try signing in again.` Verifier pending. This is coding-worker authentication, not STOG/Kakao product failure. Asked user to reauthenticate coding tool, nonblocking while backend continues. No paid/model escalation, no duplicate run or retry while run active.

## R2b same-route resumption after question timeout
Backend completed with focused 36-test evidence; full suite/bootJar and actual Android integration remain unverified. Manager read backend handoff and test-runner receipt (exit0, generated role/schema and media-root cleanup, existing metadata preserved).

User did not answer coding-tool reauthentication question after 30 minutes and delegated best judgment. Same settled DAG retried only failed android once, preserving backend; verifier unblocked by dependency when successful. No account credential inspection, paid routing change, package install or public action. Do not ask same question again this turn.
