# R2b removed-member cover denial correction

## Diagnosis / RED

`provider_and_cover_loss_proxy.py` does not send `/probe-denied-cover` through `LossProxy`; the control handler opens a direct connection to backend port 8080 with `Accept: image/jpeg`. Its one catch covered request, headers and `response.read`, so the timeout stage was unknowable.

A real embedded-Tomcat regression separated header receipt from body consumption. Pre-fix, the request reached `TripCoverException`, but `ApiErrorHandler.coverError` failed with `HttpMediaTypeNotAcceptableException: No acceptable representation` for `Accept: image/jpeg`; the direct client received the secondary authentication response instead of the privacy 404. This rules out a proven membership-lock hang.

Command: isolated runner, `TripCoverMultipartHttpIntegrationTest`, exit `1`; evidence `logs/direct-http-diagnosis.log`, `direct-http-diagnosis-exit-code.txt`, and `diagnosis.json`.

## Fix

`ApiErrorHandler.coverError` now explicitly selects `application/json`. No auth, membership, lock, media read, timeout, retry or request header behavior changed.

## Focused GREEN

Command:

`powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "& 'backend/src/test/scripts/Run-PostgresIntegration.ps1' -ArtifactSubdirectory 'mvp-trip-002/r2b/backend/member-denial-correction/focused-green-final' -Tests @('com.stog.backend.AuthPostgresIntegrationTest','com.stog.backend.TripCoverMultipartHttpIntegrationTest','com.stog.backend.TripCoverPostgresIntegrationTest') -SkipBootJar"`

Exit `0`: 27/27 tests, failures/errors/skips `0/0/0`. The normal synthetic-provider exchange/security-filter test creates a current cover, removes the member, then proves headers and complete JSON body for `image/jpeg`, `*/*`, and `application/json`: exact 404, `TRIP_NOT_FOUND_OR_NOT_ACCESSIBLE`, `application/json`, and `no-store`. The existing concurrent membership/file boundary suite remains green.
