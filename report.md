# Parallel sessions — Builds 2 and 3

Branch `parallel-builds-23`, based on `batch/surface-fix` at `eb6f4f78`.
Implementation HEAD `45ef8a69`. Nothing was merged, tagged, released, or
version-bumped; no APK was built.

## 1. The brief’s Build 1 premise did not hold in this tree

`parallel-build-1` is not an ancestor of `batch/surface-fix`. Its base is
`0729dabc`; `batch/surface-fix` was 44 commits ahead at `eb6f4f78`, and
`parallel-build-1` was 11 commits ahead of its own base. Build 1 had therefore
not been merged when this branch was made.

I did not merge or rebase. I cherry-picked the Build 1 code and test commits
onto the branch and resolved the overlap with the flaw-family code already in
the batch:

- `ed57f32c` explicit conversation storage target and `SessionRun` extraction
- `1f8a21cc` switching conversations while a turn keeps running
- `91b5f10e` application-scoped runs, approvals, and registry
- `cc8d8a88` immediate stop and cancellable checkpoint cleanup
- Build 1 source-check and stopped-send test commits
- `2171d0ae` adaptation commit: kept the current batch’s live secret redaction,
  run journal/tool receipts, spend guard, content-hash approvals, checkpoint
  error handling, and the merged command/native surface. The Build 1
  `NotificationApprovals` direct-notification path was not restored because the
  flaw-family batch deliberately removed it; the registry updates the
  foreground-service approval state and the notification still opens the app.

This is the main deviation from the brief. Without it Build 2 would have had no
per-session run, storage, or stop foundation to build on.

## 2. Build 2 — two turns in one project

Commit `ac20b46f` (implementation), `45d6025f` (tests).

- `SessionRunRegistry` now keeps a map of active runs instead of a single
  `active` slot. `begin`, `finish`, `isActive`, `hasActiveRuns`, activity rows,
  and the selected/last-run pointer are all per session. Starting B no longer
  replaces or finishes A.
- `ChatViewModel.canStartRun` allows a second cloud turn, caps the project at
  two active turns, and refuses a second local-model turn with a plain
  “Waiting for the local model” notice. The local-model admission remains
  conservative rather than a full queue.
- `liveSession` and `changeSession` resolve the cached `SessionRun` by the
  selected session id, not only `lastRun`. This is what lets a switched-away
  running conversation come back with its stream, transcript, stop control, and
  undo row intact.
- `launchRun` always completes its own registry entry and foreground-service
  reservation in `finally`; it no longer cleans up only when it is the one
  `active` run.
- `RunCheckpointGate` accepts a shared mutation lease. The view model passes one
  lease to every run in the project, so two same-project mutating batches
  serialize instead of each gate carrying its own mutex.
- `PersistentProcessService` reports “N turns running” and offers “Stop all”
  when more than one turn is active, while a single turn keeps the existing
  “Stop” action. In-app `stop()` still stops only the selected run, and
  `stopForApproval` finds the run by the approval’s session id.
- `openProject` and `changeSession` create a fresh `SessionRun` for the new
  conversation instead of mutating the currently attached run, so opening B
  cannot overwrite A’s in-memory state.

## 3. Build 3 — parallel sessions across projects

Commit `ac20b46f` (implementation), `45d6025f`/`45ef8a69` (tests).

- `WorkspaceSelection.select` no longer releases the previous project’s
  persisted URI grant. Grants are per tree and must outlive the view change;
  the old release was the direct SAF failure for a background project.
- `WorkspaceSelection.view(uri)` was added for showing an already-granted
  session project without taking a new grant or releasing another one.
  `changeSession` uses it, and now refreshes goal/memory from the selected
  session’s project rather than the global pointer. The project name and
  identity follow the selected session.
- `ChatViewModel.startTurn` captures the workspace from
  `currentSessionWorkspaceId`, not from `workspaceSelection.restore()` at
  coroutine start. A’s turn can no longer pick up B because B was opened while
  A was waiting to start.
- `TurnRunContext` carries the turn’s workspace id. `RuntimeController` keeps a
  mirror per workspace id and `activeMirror()` selects the turn’s mirror;
  checkpoint begin/finish/post/manifest, run checkpoints, pending-change
  retry/discard, and command execution use the requested workspace instead of
  the currently viewed one. `WorkspaceMirror` instances allow their own
  project’s operations; viewing another project is no longer treated as an
  access loss.
- `openProject` is allowed while another project’s turn is running. It does not
  stop the previous project’s processes when that project still has an active
  run, and it only rebinds the browser when no run is active.
- `workspaceIsCurrent` is no longer a global-selection equality check in the
  coordinator or supervisor. The captured SAF handle is the authority; a
  project that loses its grant still fails naturally at the workspace call.
- The turn-level `awaitIdentityChange` cancellation race was removed. Looking
  at another project no longer cancels the turn.

## 4. Tests

### Ran

- `:app:compileDebugKotlin` — **BUILD SUCCESSFUL**.
- `RunCheckpointGateTest` — `OK (5 tests)`.
- `ChatViewModelTest` — 71 run, **15 failures**; all failures are in the
  existing 5-second-timeout group, not assertion failures:
  `streamingPreviewIsTransient…`, `sessionLimitKeepsLastGoodState…`,
  `aRejectedEffortOffers…`, `unexpectedPersistenceFailure…`,
  `mutationAtSessionLimit…`, `stopWhilePreparing…`,
  `unavailableCommandReturns…`, `retryAfterAPartialAnswer…`,
  `acceptanceHappensBeforeAProviderLoad…`, `oversizedPromptLimit…`,
  `laterCheckpointPostFailure…`, `turnRunsUnderForegroundService…`,
  `anEffortRefusedInsideTheStream…`, `nestedInstructions…`, and
  `streamingReasoningAccumulates…`.
- Individually re-run and **passing** after the final implementation compile:
  `openingAnotherProjectLeavesTheRunningProjectLiveAndWritable`,
  `conversationSwitchKeepsTheOriginalTurnLiveAndDurable`,
  `theUndoBarBelongsToTheSessionThatMadeTheChange`,
  `backgroundApprovalIdentifiesItsConversationAndStopsOnlyItsRun`,
  `dismissingTheUndoBarHidesItAcrossSwitchAndRestartWithoutUndoing`, and
  `cancellingSummaryPreservesPriorActiveContextAndCanonicalHistory`.
- `projectSelectionStartsBoundConversationAndHistoryKeepsItsProject` passes
  after adjusting its old expectation that resuming a session kept the other
  project’s name; the row now reports the session’s own project.

### JiTTests

- `openingAnotherProjectLeavesTheRunningProjectLiveAndWritable` starts A,
  opens B, starts B, returns to A, finishes A, then finishes B. On the
  pre-Build-3 parent, opening B is blocked by `openProject` while a run is
  active and the turn is bound to the global project; the test cannot reach the
  two-live-turn state.
- `conversationSwitchKeepsTheOriginalTurnLiveAndDurable` now sends the second
  turn after switching. On the pre-Build-2 parent that send is refused by
  `canStartRun`’s one-turn-at-a-time guard.

I ran both tests on a detached worktree at `2171d0ae` (the branch tip before
the Build 2/3 implementation, i.e. the Build 1-adapted parent). Both failed
with the expected five-second await timeouts. On this branch both pass.
The worktree was removed afterwards; no base ref was changed.

## 5. Honest gaps

- Build 2 is not a complete two-turn product. Approvals are queued in one
  broker but the in-app dialog shows one selected approval; the other is
  represented as a background approval and is not presented side by side.
  The local-model second turn is rejected with a notice, not queued, and the
  admission check races until each run has loaded its provider snapshot.
- Browser state, phone capture, the interactive terminal, and the toolchain
  remain app-global. This branch does not make them per-project. A background
  project’s browser action can still move the single page, and the terminal
  still belongs to the viewed project.
- `WorkspaceSelection` remains the single “viewed project” preference. The
  branch separates grant lifetime and runtime mirrors, but does not introduce
  the `ProjectCatalog`/`CurrentProjectView`/`ProjectRuntime` model from
  `parallel-sessions-xproject.md`.
- Previews and background processes are not restarted or migrated on a project
  view change; only the destructive stop for a project with no active run was
  removed.
- The Build 1 source-check tests were cherry-picked, but the full merged
  title/ChatVM suite was not rerun after the final Build 2/3 commit. The 15
  timeout failures above are the real result of the focused ChatViewModel
  class; CI remains the final gate.
- `AgentLoopTest` could not be compiled by the manual harness because that
  source file depends on `AgentToolExecutorTest` helpers outside the harness’s
  file list; it was not run here.

## 6. Operational notes

- Base was not rebased or chased after branch creation.
- No merge, PR, tag, release, APK, versionName, versionCode, or self-updater
  change was made.
- Files were added by name. Untracked scratch harness files were left alone.
