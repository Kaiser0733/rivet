# Development history

This is a historical summary. Current behavior is described in
[ARCHITECTURE.md](ARCHITECTURE.md); durable choices are in
[DECISIONS.md](DECISIONS.md).

| Phase | Work completed |
|---|---|
| 1 — Foundation | Android shell, build toolchain, CI, versioning, and signing gates. |
| 2 — Providers | Native provider protocols, streaming, cancellation, encrypted credentials, and model selection. |
| 3 — Workspace | Android folder selection, bounded UTF-8 operations, hash conflicts, native mutations, search, and exact patches. |
| 4 — Agent loop | Provider-neutral tools, one-shot approvals, durable outcomes, resource bounds, and destructive-action context. |
| 5 — Runtime | On-device commands and a conflict-safe private project copy; an interactive terminal was prototyped. |
| 6 — Coding workflow | Read-only Git inspection, checkpoints and Undo, SQLite conversations, usage, project instructions, and context reduction. |
| 7 — Chat-first product | Project selection, approvals, progress, results, and Undo became contextual Chat actions. |
| 8 — Release engineering | Source/CI hardening and physical acceptance; unreachable Files, Changes, and Terminal presentation was retired. |
| 9 — Context | Request-aware capacity planning, tool-output pruning, task-state reconstruction, compaction diagnostics, and no-progress guards. |
| 10 — Presentation | Rose/Dark appearance, full-screen History, pins, model search, local Markdown, responsive layouts, and project-access recovery. |
| 11 — Runtime workflow | App-owned autonomy, activity, Processes, loopback previews, and bounded HTTPS project downloads. |

The 0.11.0 baseline, token/cache hardening, and final conversation-title/update
work passed their focused physical gates and were merged without changing the
tested trees. Production signing and the first public release remain separate
steps. Generic persistent shell processes remain deferred; current ownership
and recovery constraints are documented in Architecture.
