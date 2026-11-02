# R2b removed-member cover denial handoff

RESULT: PASS / prepared native probe rerun ready. The denial did not prove `TripCoverService.file` was blocked. The request reached the correct privacy exception, but Android's `Accept: image/jpeg` prevented the JSON error Map from being serialized. Cover errors now explicitly use `application/json`; status/code/no-store remain unchanged.

EVIDENCE: `diagnosis.json` distinguishes the control path and failure stage. `red-green.md` records direct HTTP RED and focused normal-auth GREEN 27/27. Changed Java files have zero LSP diagnostics. `verification-summary.json` records the one canonical isolated V1-V5 PostgreSQL plus bootJar gate: exit 0, 111/111, failures/errors/skips 0/0/0. `cleanup-receipt.json` records role/schema `0|0`, media-root cleanup and unchanged preexisting metadata. Original timeout evidence is untouched.

REMAINING PROBLEMS: The manager must rerun only the prepared `/probe-denied-cover` native scenario to observe the exact Python control returning 404; no emulator was started here. No backend lock or proxy forwarding defect remains indicated by the isolated direct/normal-auth evidence.
