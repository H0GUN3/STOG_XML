# OMO Native RTK verification

- Date: 2026-11-02
- RTK: 1.1.0
- Integration: OMO global `tool_call` extension
- Stock extension: `C:\Users\GUN\.omo\agent\extensions\rtk.ts`
- Exact-path bootstrap: `C:\Users\GUN\.omo\agent\extensions\00-rtk-path.js`
- Ownership state: `omp`

## Host-dispatched check

- A new OMO Native host received the ordinary command `git status --short`.
- The model did not prefix the command with `rtk`.
- OMO reported the RTK extension's `PreToolUse` handler completed.
- OMO then reported terminal activity as `running rtk git status --short`.
- The command preserved its normal failure semantics because the selected working directory was not a Git repository.
- Host process exit: `0`.

## Sanitized RTK aggregate

- Before: `total_commands = 1`
- After: `total_commands = 2`
- Delta: `+1`

No command history, credentials, environment secrets, or full host logs are stored here.

## Current-session activation

The already-running OMO session predates the extension files. OMO's supported in-process activation command is `/reload`; it reloads extensions without restarting or killing the session.
