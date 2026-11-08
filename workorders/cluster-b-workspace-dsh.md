# Work order — Cluster B: workspace / sync / settings (worker: dsh headless, DeepSeek V4.1 Flash, medium)

Repo: ~/workspace/rivet-work, branch `stability-v1.1.0`. Full evidence: `AUDIT-2026-11-01-eni.md` (repo root). User reports: `BUGS-next-update.md`.

## Phase B0 — DESIGN ONLY, no edits yet (another worker is in the tree right now)
For each fix below, produce a short design note in `workorders/cluster-b-design.md`: the exact functions you will change, the change in plain words, the risk, and the test you will add. Do not modify any source file in this pass. Hermes reviews the design, then gives you the go for edits.

## Rules (apply to the edit pass later)
- No JDK on this machine: no local gradle/test runs. Small careful diffs; unit tests added per fix; CI is the test gate.
- Local commits only, one per fix, plain messages. NEVER push, NEVER touch main.
- Files you own: `workspace/*` (WorkspaceMirror, WorkspaceText, SafWorkspace, TurnCheckpoint), `runtime/RuntimeController.kt`, `agent/AgentToolError.kt`, `provider/ProviderStore.kt`, `provider/SecretStore.kt`, `ui/provider/ProvidersViewModel.kt`, and the system-prompt text in `chat/ChatViewModel.kt` (F21 only — that one function). Provider clients and Http.kt belong to another worker; keep out.
- User-facing strings: plain simple words, name the real limit/cause, never blame the user or the provider for a Rivet limit. Banned words: leverage, seamless, unlock, empower, robust, cutting-edge, game-changer, supercharge, elevate, "AI-powered".

## Fixes to design
1. **F08+F09 — give the model the error vocabulary that already exists.** `AgentToolError.actions` is missing `patch`, `conflict`, `too_large`, `missing`, `binary`, `limit`, `duplicate`, `not_file`, `not_directory`, `root`, `permission`; the same codes are missing from the `deterministic` set, so one uneditable file can burn 200 turns. Carry `WorkspaceFailure.Reason.message` through (or complete both maps).
2. **F11 — one symlink must not brick the sync.** `WorkspaceMirror.localSnapshot` throws `unsafe_entry` on the first symlink/special file and aborts everything. Skip-and-report per entry (the SAF side already does this) and let the rest of the tree sync.
3. **F12 — one conflicted file must not block the whole sync.** Sync everything unconflicted; report the blocking path in the tool result instead of dropping it into a bare "conflict".
4. **F15 — failed post-Undo sync.** `RuntimeController.undoLastCheckpoint` (~:142-160): if `sync()` throws after `installCheckpoint`, there is no catch — mirror and project diverge silently and Undo repeats the half-applied op. Design the recovery: mark the checkpoint unusable + one explicit user-facing state.
5. **F18 — checkpoint scan on big projects.** `TurnCheckpoint.scan` only skips `.git`; >50k entries or >1 GiB = every edit refused with a storage message. Honour an ignore list (.gitignore-based) in the scan and make the error name the actual limit.
6. **F10 — files over 1 MiB.** `WorkspaceText.MAX_BYTES` blocks read/write/patch entirely. Design line-range/streaming edits above the limit; minimum viable: patch support by region without loading the whole file, plus an honest error where a path stays blocked.
7. **F28 — keystore loss must not dead-end Settings.** `ProviderStore.decodeForUse` throws on undecryptable headers and `save()` throws the same way via migration, so Settings can never be repaired in-app. On decrypt failure: drop the unreadable header/config, rewrite the store, let the list render and be edited.
8. **F29+F30+F31 — store safety trio.** `decode()` returning `emptyList()` on failure must never be written back over real configs (throw instead); `SecretStore` catches too narrowly (add `RuntimeException` at the entry points); guard the delete/select launches in `ProvidersViewModel` like their sibling writes.
9. **F21 — tell the model what the sandbox is.** One paragraph in the system prompt (`ChatViewModel.kt` ~:1104): the actual PATH/toolchain present, no package manager, downloaded binaries cannot be executed, the output/size ceilings that remain after Cluster A's changes. Coordinate the ceiling numbers with Hermes (Cluster A sets them).
