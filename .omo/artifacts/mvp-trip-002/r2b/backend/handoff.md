# MVP-TRIP-002 R2b backend handoff

## Delivered scope

Backend-only R2b private cover and additive V5. No Android, canonical docs, provider/account, public listener/TLS, CDN/object store, camera/recording, existing development DB, real-user row/file, or dependency was changed.

The latest task correction is implemented: upload is exact multipart with one `image` part and one JSON `command` part (`expected_plan_version`, `request_id`), rather than the earlier raw-body/header proposal.

## HTTP and DTO

- `POST /api/trips/{trip_id}/cover`, `multipart/form-data`: JPEG/PNG upload/replace for current ADMIN or MEMBER. First committed cover is `201`; same successful command replay is `200` without a new final file, version, or callback.
- `DELETE /api/trips/{trip_id}/cover`, JSON command: exact `REMOVED|UNCHANGED` body and stored no-op replay.
- `GET /api/trips/{trip_id}/covers/{cover_id}/file`
- `GET /api/trips/{trip_id}/covers/{cover_id}/thumbnail`
- R1 create/list/detail and R2 metadata-edit trip DTOs now always serialize required nullable `cover`; existing/new no-cover responses emit `cover:null`. Join remains unchanged.
- Cover metadata/file/thumbnail responses use `Cache-Control: private, no-store` and `X-Content-Type-Options: nosniff`. Paths are authenticated API paths only; no storage key, filesystem path, redirect, public URL, or original filename is returned.

## Image and private storage boundary

- `STOG_MEDIA_ROOT`/`stog.media.root` is mandatory. Startup rejects missing, non-directory, resource-tree, symlink-resolved, or unwritable roots. There is no static/repository fallback.
- Multipart parser limits are 8 MiB per file and 9 MiB total, with its temporary location inside the private root. The service independently bounded-streams at 8 MiB + 1.
- JDK ImageIO performs header-first format/frame/dimension checks before decode: exact JPEG/PNG match, one frame, 1-8192 dimensions, at most 24 MP. PNG CRC, APNG chunks, truncation, and trailing payload are rejected; JPEG must have exact SOI/EOI boundaries.
- Pixels are flattened on white into sRGB `TYPE_INT_RGB`, resized without enlargement, and freshly encoded as metadata-free JPEG quality 0.85. Display is at most 2048/4 MiB and thumbnail at most 512/512 KiB. The uploaded source file is removed after re-encode.
- Paths are generated UUIDs only. Direct-child normalization, no-follow checks, owned marker checks, and no-follow recursive deletion prevent untrusted names, traversal, symlink following, and deletion of unmarked files.

## DB/file bridge and authorization

- V5 adds exact `trip_cover` bounds/identity/retirement fields, nullable `trip.cover_id`, deferred same-trip pointer validation, and the two cover command types on the existing R2 execution PK. No request-based blob uniqueness was added.
- Both output files are forced and same-volume atomically moved before the transaction can expose `trip.cover_id`. Filesystem markers model `STAGING`, `COMMITTED`, and `GC_PENDING`.
- Explicit DB rollback deletes only the random owned marked directory. Unknown commit outcome preserves STAGING for reconciliation instead of risking deletion of a committed file.
- Startup/daily bounded reconciliation deletes only marked DB-orphan finals older than one hour or retired files older than 24 hours with no unexpired upload snapshot. Referenced files and all soft-deleted-trip media are preserved; unmarked directories are never deleted.
- Upload/remove use `(principal,trip,command_type,request_id)` as the sole execution claim. Same UUID across SET/REMOVE is valid. Current membership is rechecked before replay. No-cover REMOVE stores/replays exact `200` while preserving version/callback/file state.
- File reads lock Trip and caller membership, resolve current or unexpired retired snapshot metadata, and read the entire bounded byte file before transaction release. Concurrent R2a membership removal therefore cannot cross the authorization/storage boundary.

## Changed backend paths

- Production: `TripCoverController`, `TripCoverService`, `TripCoverStorage`, `TripCoverException`; R1/R2 DTO/query extensions; error/config/scheduling updates; `V5__trip_private_cover.sql`.
- Tests: `TripCoverPostgresIntegrationTest`, `TripCoverStorageTest`, focused controller/fixture/probe updates.
- Runner: generated private media root creation, injection, cleanup, and receipt fields in `Run-PostgresIntegration.ps1`.

## Verification

- RED/GREEN narrative: `red-green.md`.
- Focused GREEN log: `logs/focused-related-green.log`.
- Result/cleanup receipt: `test-runner-result.json`.
- Actual synthetic file assertions: `file-evidence.json`.
- 36/36 focused tests passed: cover PostgreSQL 6, storage 3, R1 product PostgreSQL 9, R2a collaboration PostgreSQL 11, controller 7. Failure/error/skip are all zero.
- Every changed Java source/test file reports zero LSP diagnostics.
- Generated V1-V5 non-superuser run cleaned its role/schema to `0|0`, removed its generated media root, preserved pre-existing DB metadata, and emitted no password.

## Decision and rejected alternative

The bridge uses local filesystem ownership markers plus the existing PostgreSQL command ledger, not a second blob claim table or generic media platform. Deleting all unreferenced UUID directories immediately was rejected because it can race an in-flight post-move/pre-commit upload; marked unreferenced finals wait one hour, while referenced STAGING is promoted during reconciliation. On unknown commit outcome the file is preserved for DB-aware recovery rather than deleted optimistically.

## Assumptions and remaining verifier work

- The explicit private root is on one filesystem, so directory `ATOMIC_MOVE` is available; failure is `503 MEDIA_STORAGE_FAILED` with no visible DB pointer.
- Existing servlet/container multipart cleanup removes its private-root parser temporary after request completion; the application-owned source is independently deleted after processing.
- Full backend suite and `bootJar` are intentionally `NOT_RUN` here; the final verifier owns them. Android/system-picker/device and real local UI flow remain the next lane. Public/provider/account/real-user operations remain out of scope and not run.
