# R2b recovery correction RED/GREEN

## RED

Command (JDK 17, no database required):

`./gradlew.bat -p backend --no-daemon test --tests com.stog.backend.TripCoverStorageTest --tests com.stog.backend.TripCoverRecoveryTest --rerun-tasks`

Final RED exit: `1`. Six tests ran; these three behavioral assertions failed against the pre-fix production code:

- `TripCoverRecoveryTest.overlappingStartupAndScheduledPassesNeverEnterStorageTogether`
- `TripCoverStorageTest.cleanupVisitsAtMostOneHundredEntriesIncludingUnownedAndInvalidNames`
- `TripCoverStorageTest.finalTraversalContinuesPastAnUnchangedFirstBatch`

Evidence: `focused-red-final-exit-code.txt`, `logs/focused-red-final.log`.

## Correction

- `TripCoverService` now admits only one reconciliation pass at a time; an overlapping startup/scheduled trigger does not enter storage concurrently.
- `TripCoverStorage` exposes one reconciliation batch capped at 100 total visited directory entries. Invalid and unowned entries consume the cap rather than being skipped outside it.
- Temp and final directory cursors retain their traversal position between passes and close at exhaustion/application teardown, so an unchanged retained first batch cannot starve later entries.
- Existing age, ownership-marker, DB reference/current pointer, unexpired execution and soft-delete checks are unchanged.

## GREEN

Focused isolated V1-V5 command:

`powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& 'backend/src/test/scripts/Run-PostgresIntegration.ps1' -ArtifactSubdirectory 'mvp-trip-002/r2b/backend/oct6-recovery-correction/focused-green-final-serialized' -Tests @('com.stog.backend.TripCoverPostgresIntegrationTest','com.stog.backend.TripCoverStorageTest','com.stog.backend.TripCoverRecoveryTest') -SkipBootJar"`

Exit `0`: 13/13 tests, failures/errors/skips `0/0/0` (cover PostgreSQL 7, storage 5, recovery 1). Cleanup: role/schema `0|0`, generated media root removed, preexisting metadata unchanged.

Two command/change-integration failures are preserved and were corrected before final GREEN: one invocation passed the three class names as one comma-joined filter and exited `1` with `No tests found`; the first queued-lock implementation run exited `1` because Spring needed the production constructor explicitly annotated after a second package-private test constructor was added. See `logs/focused-green.log`, `logs/focused-green-serialized.log`, and their exit receipts. Neither is represented as a passing run or a preexisting product failure.
