# Flaw sweep: remove the doors that stopped work

Branch `flaw-sweep`, based on `origin/batch/surface-fix` at
`6f9f78adaf462b8daef3e539c7375084f36bd134`. Version stays 1.5.0 / 35.
Nothing merged, tagged, released, or pushed to `main`. Another session's
`~/workspace/rivet-work` tree was never edited.

Phase 1 is `flaw-sweep-inventory.md`: 55 mechanisms with file:line, trigger,
original protection, real dsh evidence, and one verdict. Counts: **34 COPY**,
**11 DELETE**, **10 PROTECTS**. The headline session-blocking tier was fixed first; the
deferred rows are listed with their verdict in that file, not half-fixed.

## 1. Loop and failure guards — commit `c902cd94`

Files: `agent/AgentLoop.kt`, `agent/AgentToolError.kt`,
`agent/InternalContext.kt`, `chat/ChatViewModel.kt`,
`chat/ChatSupervisorServices.kt`, `chat/ProviderRuntimeSource.kt`,
`subagent/WorkerRunner.kt`, and their tests.

Deleted from the stop surface:

- the deterministic-failure `NoProgress` stop and its *"couldn't get past the
  same problem"* banner;
- the 16-identical-reads `NoProgress` stop;
- `RunawayGuard` at 200 model iterations / 1000 tool calls;
- `RuntimeBlocked` promotion of tool results such as `sync_required`,
  `process_limit`, `storage`, `unsafe_entry`, and `mirror_dirty`;
- `CapabilityBlocked` for a missing approval or a blocked Android command;
- `CheckpointUnavailable` as a turn stop.

Copied from dsh: `dsh-agent-loop/src/agent.ts:334-446` ends a turn only on
completion, a pre-step reject, cancellation, max-tokens, or a thrown error; the
loop itself is unbounded. Tool failures are results in
`dsh-tools/src/pipeline.ts:204-225,440-479`, and a repeated call is only an
advisory context event in `dsh-repeat-tool-reminder/src/index.ts:2223-2260`
(thresholds 3/5/8, "never veto"). Rivet now appends a `repeat_reminder` context
event at those counts and keeps going. A refused call is a correlated tool
result; the model sees it and chooses the next action. The remaining stop
reasons are completion, empty response, workspace identity change, and the real
context/storage limits.

JiTTest: `repeatedIdenticalFailureGetsAdviceAndTurnContinues` and
`runtimeFailureResultNoLongerEndsTheTurn` fail on the parent sources
(`NoProgress` / `RuntimeBlocked`), pass on this branch.

## 2. Pending project changes and terminal failures — commit `7b044d4b`

Files: `runtime/RuntimeController.kt`, `runtime/RunCheckpointGate.kt`,
`agent/AgentToolExecutor.kt`, and their tests.

Deleted:

- the startup `commandBlocker()` pending-changes banner with its Retry/Discard
  homework;
- the `runCommand`, `openTerminal`, `spawnProcess` and `backgroundBlocker`
  refusals on `sync_required` / `sync_failed`;
- the `requireSafCurrent` gate on every file tool and command;
- the post-command sync state as a turn stop.

`runCommand` now settles what it can and runs in the current worktree. A
remaining per-file disagreement is reported in the command result through
`sync`, `sync_path`, `sync_conflict_paths`, and `sync_skipped`; it no longer
refuses the whole project. The user terminal falls back to the last complete
worktree on a pending path. The background process path does the same.

Undo is best-effort now. `RunCheckpointGate.beforeMutation` marks the checkpoint
broken and lets the mutation run when a snapshot cannot be prepared; the turn
ends with the existing "couldn't prepare Undo" notice if it is still broken.
Undo restore itself still refuses to overwrite newer external files. This is the
same distinction dsh makes: `dsh-atomic-write/src/index.ts:80-92` writes the
real file atomically, and `dsh-session-checkpoint-policy/src/index.ts:64-82`
fails closed for session durability, but Rivet's product Undo is not a
prerequisite for a file edit.

dsh has no workspace mirror or pending state: `dsh-fs-local/src/index.ts:235,273`
writes the real file and `dsh-bash-local/src/index.ts:122-133` runs a command in
the real working directory. The conflict data is therefore a warning, not a
gate.

JiTTest: `runtimeDiscardChecksExactSelectionAndUnblocksCommandsAndSafTools`
asserts `commandBlocker()` is null while both sides changed the same path; it
fails on the parent (`sync_required`). `undoUnavailableDoesNotBlockMutation`
and `checkpointFailureRunsApprovedMutationWithoutUndo` fail on the parent
(`CheckpointUnavailable`) and pass here.

## 3. Test fixtures — commit `ef31d29e`

The title lifecycle fixtures now fail a turn explicitly instead of treating a
blocked tool call as a turn failure. The checkpoint-post chat fixture asserts
that the saved mutation is visible, which is the behaviour that changed.

## Tests actually run

All commands ran in this worktree with the local JDK 17 and cached Gradle /
Robolectric dependencies. Locale was `C.UTF-8` (the first run exposed a
pre-existing non-ASCII filename failure caused by the POSIX locale, not by this
change).

- `:app:testDebugUnitTest --tests AgentLoopTest` — pass, 68 tests.
- `:app:testDebugUnitTest --tests AgentToolErrorTest --tests InternalContextTest
  --tests CommandToolTest --tests WorkspaceMirrorTest --tests
  RunCheckpointGateTest --tests PhoneCaptureToolTest` — pass, 80 tests.
- `:app:testDebugUnitTest --tests ChatViewModelTest` — pass.
- `:app:testDebugUnitTest --tests AgentToolExecutorTest --tests
  ProjectDownloaderTest` — pass.
- `:app:testDebugUnitTest --tests ConversationTitleLifecycleTest --tests
  ChatViewModelTest.laterCheckpointPostFailureDoesNotHideSavedMutation` — pass.
- Parent check for the two new loop tests: run with the parent versions of the
  changed production files and the new test file — both fail
  (`repeatedIdenticalFailureGetsAdviceAndTurnContinues` at its completion
  assertion, `runtimeFailureResultNoLongerEndsTheTurn` likewise), then pass on
  the branch.
- Parent check for the pending-changes gate: with the parent
  `RuntimeController`, `runtimeDiscardChecksExactSelectionAndUnblocksCommandsAndSafTools`
  fails (`commandBlocker()` returns `sync_required`); it passes on the branch.
- Full `:app:testDebugUnitTest` — **pass**, 1887 tests in 323 classes, 0
  failures / 0 errors / 0 skipped (`BUILD SUCCESSFUL in 3m 22s`).

## What is fixed now, in the acceptance terms

- A tool failure is a message in the transcript. The run continues; the model
  can retry with different arguments or finish.
- A repeated identical call gets advice, not a banner.
- A pending project file no longer stops `run_command` or file tools. The command
  result names the pending path, and work continues.
- Undo that cannot be prepared costs the undo row and produces a notice; it no
  longer refuses a write.
- Process, thermal, foreground-service and policy errors are tool-local results.

## Honest gaps

- No Android device or emulator was available. The real `echo hello` path through
  `CommandProcess` (native `librivet_termux.so`) was not executed end to end; it
  is covered at the seams: `commandBlocker` with a real SAF mirror conflict,
  `AgentToolExecutor` with a sync-conflict command result, and `AgentLoop`
  continuing after that result.
- Deferred session-blocking work is listed in `flaw-sweep-inventory.md` with its
  verdict: the global operations mutex, the mirror `previous_dirty` recovery
  wedge, the 512 KiB session cap, context preflight refusals, provider retry
  policy, subagent wait/approval bounds, run-journal latest-only recovery, and
  TaskCheckpoint silent eviction. These were not half-edited.
- `share_out` still has no approval object in Ask/BasicYOLO; it is now a
  tool-local refusal instead of a turn end, but the product path is still
  incomplete and is recorded as such.
- The full suite is the final local gate; CI remains the integration gate.
  Nothing was merged, tagged, or released.
