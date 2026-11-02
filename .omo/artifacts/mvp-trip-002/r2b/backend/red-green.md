# MVP-TRIP-002 R2b backend RED/GREEN

## RED

- Test: `TripProductControllerTest.authenticatedMemberCanReachMultipartCoverContract`
- Behavior: authenticated `POST /api/trips/{trip_id}/cover` with multipart `image` and JSON `command` expected `201`.
- Before production R2b code existed, the compiled test reached MockMvc and failed its HTTP status assertion because no cover route existed (observed `404`, Gradle exit 1).
- Evidence: `logs/red-multipart-cover.log`.
- This was an executable HTTP behavior failure, not a missing test class or production scaffold compile failure. The earlier command that ran before the test edit and reported “No tests found” is not counted as RED.

## GREEN

- `TripCoverPostgresIntegrationTest`: 6/6, failure/error/skip 0.
  - multipart JPEG/PNG, canonical JPEG/thumbnail files, DTO metadata and private reads;
  - invalid MIME/decode/truncation/trailing payload/dimension/input-size bounds with no DB/file mutation;
  - replacement, upload replay, upload/remove same UUID command scopes, exact no-cover replay;
  - concurrent same-request claim and replacement/remove version serialization;
  - storage failure and late ledger failure rollback, corrupt file fail-closed;
  - STAGING/COMMITTED/GC_PENDING reconciliation, retired expiry, unmarked-file preservation, deleted-trip retention;
  - DB membership lock held through the bounded storage read and removed-principal replay denial.
- `TripCoverStorageTest`: 3/3, failure/error/skip 0. It exercises root fail-closed behavior, 2048/512 output sizing, JPEG 0.85 re-encode, white alpha, source metadata removal, no original retention, PNG CRC/animation/trailing-payload rejection, and 8192/24 MP/8 MiB bounds.
- Related focused regression: `TripProductPostgresIntegrationTest` 9/9, `TripCollaborationPostgresIntegrationTest` 11/11, `TripProductControllerTest` 7/7. Total focused GREEN: 36/36.
- Command evidence: `logs/focused-related-green.log`.
- Isolation/cleanup receipt: `test-runner-result.json` records V1-V5 in a generated non-superuser schema, DB cleanup `0|0`, pre-existing metadata unchanged, generated private media root cleanup true, and no password output.
- V1-V4 source checksums remained unchanged. V5 SHA-256: `d5f3219599b0b968cdd3e3d9d0187329b8339f71125905448fe957a8e4841785`.

## Producer boundary

Full backend suite and `bootJar` are intentionally `NOT_RUN`; the task assigns the final full related gates to the verifier. Android/device/public/provider/real-user work was not run or modified.
