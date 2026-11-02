# GRAPHIFY-SETUP-001: Local codebase exploration

## Identity and status

| Item | Value |
| --- | --- |
| Goal | Make the current STOG workspace explorable through Graphify |
| Status | IMPLEMENTED / LOCAL VERIFIED |
| Product changes | None |

## Scope and decisions

- Build the project's local Graphify map and add graph-first guidance for cross-file architecture questions.
- Use the no-LLM Graphify update path. The generated report records `0` input and output tokens.
- Keep `graphify-out/` project-local. The graph is navigation evidence; source and canonical product documents remain authoritative.
- Do not initialize the LLM Wiki's `raw/` and `wiki/` directories without a source ingest.
- Do not add optional Graphify parsers, enable global multi-agent mode, or change Android/backend dependencies or runtime behavior.
- Preserve `MVP-TRIP-005` as the single product work slot.

## Changed paths

- `AGENTS.md`
- `graphify-out/`
- `docs/tasks/GRAPHIFY-SETUP-001.md`
- `docs/PLAN.md`

## Acceptance and evidence

- `graphify update .` completed after the project guidance was added.
- Final report: 148 files, approximately 129,276 words, 3,285 nodes, 9,412 edges, 167 communities, and `0` input / `0` output tokens.
- `graphify query "TripViewModel AuthApiClient TripProductController" --context call --budget 1800` returned all three expected source nodes.
- `AGENTS.md` directs Graphify queries first for architecture, call-path, dependency, and cross-file relationship questions when the graph exists, then requires source verification.
- RTK remains registered in the global Codex `PreToolUse` Bash hook. This Codex session predates its installation and must be restarted before the hook is loaded.

## Limitations

- The report marks 35 files unclassified, including 24 XML files. Six SQL files contributed no symbols because `tree_sitter_sql` is not installed.
- `app/build.gradle` and `backend/build.gradle` had partial syntax extraction warnings. Six other files yielded no symbols.
- The graph is not a build/test result and does not replace source review. Android/backend tests were not run because application code was unchanged.
- Graphify refresh after code changes is manual with `graphify update .`; no file watcher or automatic background rebuild was enabled.
