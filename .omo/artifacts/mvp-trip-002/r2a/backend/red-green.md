# R2a backend focused RED/GREEN

## RED

- Test: `TripProductControllerTest.authenticatedMemberCanReachMetadataEditHttpContract`
- Contract surface: authenticated `PUT /api/trips/{trip_id}` with the exact metadata body.
- Before production implementation, the test compiled and executed against the existing HTTP application, then failed at the status assertion (`1 test completed, 1 failed`, Gradle exit 1).
- Evidence: `logs/red-http-metadata-edit.log`.
- This was a behavioral route failure, not a scaffold or compilation failure.

## GREEN

- The same controller test is included in the six passing `TripProductControllerTest` cases in `logs/focused-related-green.log`.
- `TripCollaborationPostgresIntegrationTest`: 11 tests, 0 failures/errors/skips. It covers member/admin metadata edits, scheduled-date conflict/no mutation, no-op/replay, member cursor frozen version, role/remove/leave/delete permissions, last-admin guards, issue/reissue/stop/replay, hash-only invitation storage, join validation precedence/non-disclosure, rate limits, rollback/post-commit callback, removed-user replay denial, deleted create replay conflict, My Trips deleted-anchor continuation, and concurrent last-admin plus join/delete serialization.
- Related R1/auth regression command: 62 tests across collaboration, trip product, basket save, controller, cursor, and authentication boundary classes; 0 failures/errors/skips. Evidence: `logs/focused-related-green.log`.
- Normal auth command: all 14 `AuthPostgresIntegrationTest` tests passed, including two distinct synthetic-provider users obtaining opaque Bearer credentials through the security filter and completing create -> invite -> join -> MEMBER metadata edit. Evidence: `logs/auth-postgres-green.log`.
- Every PostgreSQL run used a generated non-superuser role/private schema, applied V1-V4, and reported cleanup `0|0` with pre-existing metadata unchanged. Latest receipt: `test-runner-result.json`.
- Full backend suite and `bootJar` were intentionally not run by this producer; the verifier owns that single final gate.
