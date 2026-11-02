# R2b multipart boundary correction RED/GREEN

## Behavioral RED

Command:

`powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& 'backend/src/test/scripts/Run-PostgresIntegration.ps1' -ArtifactSubdirectory 'mvp-trip-002/r2b/backend/multipart-boundary-correction/behavioral-red' -Tests @('com.stog.backend.TripCoverMultipartHttpIntegrationTest') -SkipBootJar"`

Exit `1`: four real embedded-Tomcat HTTP tests ran. Only `androidShapedImageAndFilenameLessJsonCommandReachControllerService` failed: expected `201`, observed `400 INVALID_REQUEST`. Real URL query, duplicate image/command, and unknown file/text field rejection tests passed against pre-fix production.

Two earlier test-authoring attempts are preserved: the first lacked isolated datasource properties; the second had one mock startup interaction not cleared. Neither is claimed as behavioral RED.

## Minimal correction

- `TripCoverController.requireNoQuery` now checks the actual URL query string rather than `getParameterMap()`, which contains valid multipart form fields.
- `requireExactParts` now enumerates servlet `Part` values and requires exactly two parts: one filename-bearing `image` and one filename-less `command`. Duplicate image/command and every unknown file/text part are rejected.
- Existing MockMvc tests now represent multipart parts through `MockPart`, matching the servlet `Part` contract instead of treating JSON command as a file.
- Authentication, media type, version/request parsing, service transaction, privacy and response behavior are unchanged.

## Focused GREEN

Command:

`powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& 'backend/src/test/scripts/Run-PostgresIntegration.ps1' -ArtifactSubdirectory 'mvp-trip-002/r2b/backend/multipart-boundary-correction/focused-green-final' -Tests @('com.stog.backend.TripCoverMultipartHttpIntegrationTest','com.stog.backend.TripCoverPostgresIntegrationTest','com.stog.backend.TripProductControllerTest') -SkipBootJar"`

Exit `0`: 18/18 tests, failures/errors/skips `0/0/0` (real servlet HTTP 4, cover PostgreSQL 7, controller 7). Generated role/schema and media root were cleaned and preexisting metadata remained unchanged.

The first post-fix focused run exposed only the old MockMvc file-map representation mismatch; its exit `1` is preserved in `logs/focused-green.log`. Production real-Tomcat boundary tests were already green in that run.
