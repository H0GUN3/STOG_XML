# Multipart correction provenance

The canonical TASK remains unchanged and internally clear:

- `docs/tasks/MVP-TRIP-002.md:298-304` specifies a raw image body, `X-STOG-Expected-Plan-Version`, `X-STOG-Request-ID`, immediate foreground upload, and no persistable URI permission.

A later authoritative implementation direction exists in the manager/orchestrator DAG record:

- `.omo/senpi-task/dag/events/dag_5c760b8a-1c61-4166-a39d-4cca5a5bd8e3.jsonl`, October 4 run definition/amendment, backend node: `Exact contract multipart image/command`.
- The same DAG definition's Android node says to persist a grant when needed and release owned grants after the operation.
- The resulting producer handoff at `.omo/artifacts/mvp-trip-002/r2b/backend/handoff.md` describes this as the latest task correction and reports multipart `image` plus JSON `command`.

No later direct user decision or canonical TASK edit stating multipart was found in the searched project/artifact records. Therefore the authoritative later source found is a manager-issued execution directive, not canonical product prose or a recorded direct-user amendment. This correction does not rewrite either record; backend recovery work is independent of that unresolved wire/provenance inconsistency.
