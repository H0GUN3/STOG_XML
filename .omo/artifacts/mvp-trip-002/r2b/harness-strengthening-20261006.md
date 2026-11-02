# R2b final E2E harness strengthening - ready, not executed

## Changed verifier-owned paths

- `app/src/androidTest/java/com/stog/app/auth/RealBackendTripCoverUiTest.java`
- `.omo/artifacts/mvp-trip-002/r2b/provider_and_cover_loss_proxy.py`
- `.omo/artifacts/mvp-trip-002/r2b/Run-Real-Cover-E2E.ps1`
- this receipt and `logs/real-e2e-harness-strengthened-compile.log`

No production source, backend source, portrait behavior, dependency, account, DB, server or emulator was changed/started.

## Strengthened obligations

- `RemoteImageProbe` subscribes before upload/retry, captures the exact successful `cover_id`, waits for detail metadata to reference that same ID, and only completes on a committed frame whose bitmap has the expected green/blue center pixel. It cannot accept the prior preview or another cover. Observer and pre-draw listener are removed on success, timeout and scope end.
- All LiveData wait helpers now remove observers in `finally`. Generated MediaStore documents and private files are owned lists deleted from test `@After`, including failure paths.
- Initial native-picker green SET must succeed normally. A fixture control explicitly arms loss only for the next successful cover SET response. The blue native-picker replacement receives backend `201`, that committed response alone is dropped, and the same UI intent retries to `200` replay. Proxy facts record all statuses and `dropped_cover_statuses`.
- UI remove opens the actual confirmation dialog, captures/cancels it, proves the cover remains, reopens and confirms removal through the native control.
- Raw authenticated no-cover remove asserts exact `200`, `result=UNCHANGED`, unchanged version and byte-equivalent parsed replay values. A supplemental SET and REMOVE reuse the same UUID across distinct command types.
- Removed-member metadata, file and thumbnail are all denied. Soft-deleted-trip metadata, file and thumbnail are all denied.
- Runner DB receipt requires exactly 4 cover rows, 4 SET ledgers, 3 REMOVE ledgers, exactly one UNCHANGED ledger despite replay, 7 total cover ledgers, expected main version 9, invalid-input trip version 0 and one soft-deleted retained pointer.
- Runner filesystem receipt requires exactly four owned cover directories with four display/thumbnail pairs. The fresh backend 105-test gate remains the callback proof that UNCHANGED/replay emits no callback; real transport has no callback/SSE observer.

## Static verification

- Java LSP diagnostics: zero.
- Android test Java compilation: PASS (`logs/real-e2e-harness-strengthened-compile.log`).
- Python fixture syntax: PASS.
- PowerShell runner parse: PASS.
- Runtime intentionally `NOT_RUN`, awaiting manager backend-ready signal. No device attached and no port 8080 listener after preparation.

## Preserved historical failure

The prior real run remains evidence that the native picker and expected green preview worked while the old backend boundary returned `400 INVALID_REQUEST`. It is not relabeled as a committed-response replay. Evidence remains in `logs/e2e-instrumentation-failure.log`, `logs/e2e-runtime-failure-facts.json`, and `screenshots/failed-run/`.
