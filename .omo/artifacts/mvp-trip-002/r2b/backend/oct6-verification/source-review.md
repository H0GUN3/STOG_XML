# R2b bounded source/test review

## Decision

The full execution gate passes, but R2b cannot be accepted as fully verified from this review. `TripCoverService.reconcileSafely()` has no serialization guard, and `TripCoverStorage.cleanupOldTemp()` scans every temp child without a count/time bound. This conflicts with MVP-TRIP-002 section 10's `bounded serialized` recovery requirement (and section 6.4's bounded reconciliation requirement). The current six cover integration tests and three storage tests do not exercise overlapping reconciliation or a temp population above a bound.

There is also a wire-source discrepancy to resolve in canonical records: MVP-TRIP-002 section 6.1 still specifies raw image body plus two headers, while the producer handoff and implementation use multipart `image` + JSON `command` following a later correction. This run verifies the multipart implementation, not the stale raw-body prose.

## Evidence and exact review gaps

- Image bounds/format: production enforces 8 MiB streaming, JPEG/PNG match, one frame, 8192 px/24 MP, 2048/512 resizing and 4 MiB/512 KiB output bounds. Tests cover MIME mismatch, PNG corruption/truncation/trailing data/APNG, 8193 px, 30 MP, declared/request input overflow and output dimensions. Gaps: no understated-size stream that crosses 8 MiB, no JPEG corruption/trailing/polyglot fixture, and no generated output that reaches either encoded-byte rejection boundary.
- Metadata: fresh JPEG re-encoding, original deletion and PNG `tEXt` sentinel stripping are tested. Gap: no JPEG EXIF/GPS/ICC/orientation metadata fixture is asserted stripped.
- Privacy: member detail/list/file reads, outsider detail/file denial, private/no-store and nosniff are exercised. Gaps: outsider thumbnail denial is not separately asserted; authorized unknown or expired-retired cover `COVER_NOT_FOUND` is not asserted; duplicate/unknown multipart non-file parts are not covered.
- Membership: file read versus concurrent member removal is lock-tested, and removed-principal upload replay is denied. Gaps: equivalent thumbnail race and removal between upload precheck/staging and transactional recheck are not directly synchronized in tests.
- File/DB bridge: atomic final move before pointer, deterministic DB rollback cleanup, corrupt-current-file fail closed, retired replay readability, and soft-delete retention are exercised. Gap: unknown transaction outcome preservation/recovery is reasoned from synchronization state but has no fault-injection test.
- Ledger: SET replay, concurrent same-key winner, same UUID across SET/REMOVE, no-cover REMOVE replay, version serialization, current authorization and callback count are exercised. Gap: changed input under the same SET request ID is not directly asserted as `REQUEST_ID_REUSED`.
- Recovery: old owned orphan deletion, expired retired deletion, current/deleted-trip preservation and unmarked-directory preservation are exercised. Gaps: old temp deletion, startup-listener execution, 100-directory batch continuation, DB-query failure no-delete behavior, and overlapping pass serialization are not tested. More importantly, temp scanning is unbounded and passes are not explicitly serialized in production.
- Root safety: missing/nonexistent/resource-tree roots are tested. Gaps: symlink/reparse and unwritable-root rejection have no direct test on this workstation.

No production or dependency changes were made during verification.
