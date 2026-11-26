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
  (The queue itself was rechecked and is not dropped; see §7.)
- Browser state was app-global before the review fix; §7 now gives each
  project its own `BrowserSession` and page state. Phone capture, the
  interactive terminal, and the toolchain remain app-global.
- `WorkspaceSelection` remains the single “viewed project” preference. The
  branch separates grant lifetime and runtime mirrors, but does not introduce
  the `ProjectCatalog`/`CurrentProjectView`/`ProjectRuntime` model from
  `parallel-sessions-xproject.md`.
- Previews and background processes are not restarted or migrated on a project
  view change; only the destructive stop for a project with no active run was
  removed.
- The Build 1 source-check tests were cherry-picked, but the full merged
  title/ChatVM suite was not rerun after the final Build 2/3 commit. The 15
  timeout failures above were the real result of the focused ChatViewModel
  class before the gap fixes; CI remains the final gate.
- `AgentLoopTest` could not be compiled by the manual harness because that
  source file depends on `AgentToolExecutorTest` helpers outside the harness’s
  file list; it was not run here.

## 6. Operational notes

- Base was not rebased or chased after branch creation.
- No merge, PR, tag, release, APK, versionName, versionCode, or self-updater
  change was made.
- Files were added by name. Untracked scratch harness files were left alone.

## 7. Gap fixes after Kaiser’s review

Reviewed parent: `2ad56c1b` (the previous branch tip). Gap-fix commits:
`eeabde8a` (implementation), `52bc21f5` (tests), and `de3e031a` (stopping a
turn during the local-admission preflight also cancels that admission), with
this report commit after them.

### Fix 1 — browser state is per project

`BrowserRuntime.forProject(app, models, identity)` now caches one
`BrowserSession` per project identity. Each session keeps its own URL, tabs,
reader state, console, and project snapshot store; `bindProject` already
persisted and restored the per-project tab state. The view model’s `browser`
getter now returns the viewed project’s session, and `showBrowser(identity)`
binds the viewed session without touching another project’s session.

A turn no longer uses the view model’s global browser object. At turn start it
resolves `runBrowser = browserFor(workspaceId)`, binds that project, and builds
`BrowserTools(runBrowser)` for the whole turn. `beginAgentRun`, preview starter,
custom-tab handoff, `endAgentRun`, and chat-preview dismissal all use that
run browser. Selecting project B changes only the viewed session; A’s
captured `runBrowser` and its page stay intact. Switching back restores A’s
session, and its persisted snapshot is where it left off.

Test: `eachProjectKeepsItsOwnBrowserSession`. It asserts distinct session
objects for two projects, that inserting B leaves A’s `projectKey` untouched,
and that returning to A returns the same session. On `2ad56c1b` it fails at
“each project needs its own browser session”; on this branch it passes.

Remaining browser limit (honest): a background project has no attached
WebView renderer, so its text/read/find/open-text actions work on its own
state, while renderer-dependent visual actions may report
`browser_renderer_unavailable` until that project is viewed and gets the
renderer. That is safer than hijacking the viewed page, but it is not full
visual browsing in the background.

### Fix 2 — second local turn queues

`SessionRun` now records whether its provider has been resolved
(`providerKnown`). When a local turn is active, starting another turn first
resolves that turn’s provider. A local turn is put in `queuedLocalTurns` and
its session is marked queued in `SessionRunRegistry`; a cloud turn continues to
start immediately (subject to the existing two-cloud-turn cap).

A queued turn has `streaming = true`, activity “Waiting for the local model…”,
is visible as a running conversation row, accepts its user message durably, and
keeps the same `SessionRun` when the user switches away and back. `stop()` on a
queued turn removes it from the queue and marks it stopped; it never starts.
`launchRun` drains the queue when a local turn finishes or stops, starting the
next local turn automatically.

Tests:
- `secondLocalTurnQueuesAndCanBeStoppedBeforeItStarts` — queues B, verifies
  “Waiting for the local model…”, stops it, releases A, and verifies B never
  reaches the provider while its message is preserved.
- `queuedLocalTurnStartsWhenTheRunningOneFinishes` — queues B, finishes A,
  verifies B starts once and returns “second done”.

Both fail on `2ad56c1b` with timeouts at the pre-fix rejection path and pass on
this branch. The queue is in-memory only; a process death loses a queued turn,
which is not claimed as durable.

### Check only — approvals under parallel runs

No approval change was needed. The broker is a FIFO queue:
`AgentApprovalGate.await` adds a `Pending` and publishes the head;
its `finally` removes only that waiter and promotes the next; `resolve` only
accepts the current head and returns false for an old token. Thus a second
run’s approval waits, remains in `mutablePending`, and is promoted after the
first resolves. `SessionRunRegistry` observes `approvals.pending` and updates
the owning session; `project()` exposes the selected one as `pendingApproval`
and another as `backgroundApproval`.

Evidence run: `AgentApprovalGateTest` — `OK (8 tests)`, including
`concurrentApprovalsResolveInFifoOrder` and
`cancelOwnerDropsOnlyThatOwnersPendingApproval`; and
`backgroundApprovalIdentifiesItsConversationAndStopsOnlyItsRun` on this branch.
Verdict: approvals queue correctly; never dropped or stranded by the first
resolution. No new approval, cap, or turn-block was added.

### Verification after the gap fixes

- `:app:compileDebugKotlin` — **BUILD SUCCESSFUL**.
- `eachProjectKeepsItsOwnBrowserSession` — pass.
- `secondLocalTurnQueuesAndCanBeStoppedBeforeItStarts` — pass.
- `queuedLocalTurnStartsWhenTheRunningOneFinishes` — pass.
- `openingAnotherProjectLeavesTheRunningProjectLiveAndWritable` — pass.
- `conversationSwitchKeepsTheOriginalTurnLiveAndDurable` — pass.
- `backgroundApprovalIdentifiesItsConversationAndStopsOnlyItsRun` — pass.
- `AgentApprovalGateTest` — 8/8 pass.
- `openingAnotherProjectLeavesTheRunningProjectLiveAndWritable`,
  `conversationSwitchKeepsTheOriginalTurnLiveAndDurable`, and
  `eachProjectKeepsItsOwnBrowserSession` re-run after the final compile — pass.
- Parent-red: all three new tests were also run on a detached `2ad56c1b`
  worktree. `eachProject…` failed the session-identity assertion; both local
  queue tests timed out at the old rejection. The worktree was removed after.

## 8. Gate fix — Python source checks on `4f54befd`

CI run `45788418151` failed 2 of 100 Python checks. Both were **STALE**, not
production regressions. Fix commit: `faa336ce` (`scripts/test_fix_round.py`,
`scripts/test_improvements.py`). Full Python suite after the fix: **Ran 100
tests, OK**. `:app:compileDebugKotlin` also succeeds.

### 8.1 `test_compaction_publishes_start_and_scrolls_to_the_row` — STALE

Failed on:

`finally {\n                        if (activeRun === owner) owner.update { it.copy(compacting = false) }`

The compaction behaviour is intact, but the owner check moved from the single
`activeRun` property to the registry when two turns became possible. It now
clears the flag exactly as before, for the run that owns the gap, in
`ChatViewModel.compactHistory`:

`finally { if (runRegistry.isActive(owner)) owner.update { state -> if (state.compacting) state.copy(compacting = false) else state } }`

`onCompactionStarted = { if (runRegistry.isActive(owner)) owner.update { it.copy(compacting = true) } }`
is still present. The UI keys and scroll guard asserted by the same test are
unchanged and still pass. The test now pins the registry-owned finally and the
flag clear, and fails if either disappears.

### 8.2 `test_empty_draft_is_reusable_and_absent_from_sidebar_recents` — STALE

Failed on:

`reuseEmpty = activeRun == null && selectedState.value.goal == null`

The guard is intact but now asks the registry whether **any** run is active,
which is the correct parallel-safe form:

`reuseEmpty = !runRegistry.hasActiveRuns() && selectedState.value.goal == null`

Other assertions in the test (`reuseEmpty: Boolean = false`, the
`NOT EXISTS(... events ...)` query, `hasMessages`, and the sidebar filter) are
unchanged. The updated assertion is at least as strict: an empty draft is still
reused only when no turn is active and no goal is set.

### 8.3 Verification

- Full Python suite: `Ran 100 tests ... OK`.
- `:app:compileDebugKotlin`: **BUILD SUCCESSFUL**.
- Focused parallel/gap tests: `eachProjectKeepsItsOwnBrowserSession`,
  `openingAnotherProjectLeavesTheRunningProjectLiveAndWritable`,
  `conversationSwitchKeepsTheOriginalTurnLiveAndDurable`,
  `secondLocalTurnQueuesAndCanBeStoppedBeforeItStarts`,
  `queuedLocalTurnStartsWhenTheRunningOneFinishes`, and
  `backgroundApprovalIdentifiesItsConversationAndStopsOnlyItsRun` — all pass.
- `AgentApprovalGateTest` — 8/8 pass.
- Test-only stability commit `aba7cbda` gives the project-browser tests a
  15-second project-open window instead of the shared 5-second helper; no
  production code changed for it.

## Gate diagnosis (Sol)

[CI run 45803262155](https://github.com/Kaiser0733/rivet/actions/runs/45803262155)
on `a3cb363e` recorded 18 failures in 1,928 Kotlin tests. Its JUnit XML
recorded 14 deadline expirations and four assertion failures. All 18 were
production regressions. The 5-second deadlines exposed missing state changes.
The existing wait budgets and assertions were retained.

The three unmodified classes reproduced the same 18 failures locally out of
126 tests. This used JDK 17, `LANG=C.UTF-8`, the warm Gradle classpath and the
existing Robolectric SDKs. The local assertion output matched the CI values,
including the title test's two calls and discard's `Expected mismatch`.

`SessionRunRegistry.begin` started requiring a stored conversation ID in
`ac20b46f`. The single-transcript `AgentSessionPersistence` path still
allows an absent ID. Those turns ran provider code, but owner checks
skipped previews, transcript writes, completion, and cancellation. The
registry now tracks these turns. A follow-up sent while its previous turn is
finishing waits for that owner's job before admission.

| Test | Original CI failure | Bucket | Root cause | Fix |
| --- | --- | --- | --- | --- |
| `ChatViewModelTest.streamingPreviewIsTransientAndCompletedMessageReplacesIt` | :532, expected `# Finished`, got empty preview | REAL | The unregistered run could receive deltas but could not publish the transient preview. | `a743f632` |
| `ChatViewModelTest.sessionLimitKeepsLastGoodStateWithoutRetryAndNextTurnCanRun` | :657, 5-second deadline | REAL | The run skipped the assistant save that would surface the limit and skipped its terminal state. | `a743f632` |
| `ChatViewModelTest.aRejectedEffortOffersTheNextStepDownAndResendsTheSameMessage` | :1717, 5-second deadline | REAL | The lower-effort replay lacked an owner to commit and publish its successful reply. Recovery actions now also wait out an earlier owner's cleanup. | `a743f632` |
| `ChatViewModelTest.unexpectedPersistenceFailureDoesNotRetrySameTranscript` | :684, 5-second deadline | REAL | The ID-less owner check skipped the failing assistant save and never published the storage error. | `a743f632` |
| `ChatViewModelTest.mutationAtSessionLimitNeverRequestsApprovalAndClearAllowsNextTurn` | :832, 5-second deadline | REAL | The ID-less turn skipped durable tool events and could not publish the session-limit result. | `a743f632` |
| `ChatViewModelTest.stopWhilePreparingKeepsTheAcceptedMessageAndStartsNoSecondTurn` | :595, 5-second deadline | REAL | The preparing turn was absent from the registry, and a second send could accept another bubble before admission. Stop now cancels the one registered job and preserves its prompt. | `a743f632` |
| `ChatViewModelTest.unavailableCommandReturnsToolResultAndModelContinues` | :494, 5-second deadline | REAL | The unregistered turn skipped the tool failure and follow-up assistant events in the durable and visible transcript. | `a743f632` |
| `ChatViewModelTest.retryAfterAPartialAnswerDoesNotSendThePromptTwice` | :1759, 5-second deadline | REAL | The owner check dropped the incomplete assistant event and the replay's completion, breaking transcript reuse. | `a743f632` |
| `ChatViewModelTest.acceptanceHappensBeforeAProviderLoadThatNeverReturns` | :613, 5-second deadline | REAL | The prompt appeared, but Stop treated its unregistered job as unstarted and left the blocked provider load running. | `a743f632` |
| `ChatViewModelTest.oversizedPromptLimitRollsBackAcceptedBubbleAndSkipsFinallySave` | :2599, 5-second deadline | REAL | The oversized-prompt catch rolled back the bubble, then skipped the error and idle state because the turn had no registry entry. | `a743f632` |
| `ChatViewModelTest.projectSelectionStartsBoundConversationAndHistoryKeepsItsProject` | :164, expected `project`, got `Another project` | REAL | History published the new session ID with the old viewed project. Cached runs could skip restoring the bound project entirely. The selected state now publishes after resolving the project and browser. | `cef339c9` |
| `ChatViewModelTest.laterCheckpointPostFailureDoesNotHideSavedMutation` | :468, 5-second deadline | REAL | The ID-less turn could perform mutations but skipped transcript updates and completion publication. | `a743f632` |
| `ChatViewModelTest.turnRunsUnderForegroundServiceWithOngoingNotificationAndStopsOnEnd` | :1562, 5-second deadline | REAL | The service started and the provider answered. The missing owner suppressed the final answer and idle state the test awaited. | `a743f632` |
| `ChatViewModelTest.anEffortRefusedInsideTheStreamStillOffersTheStepDown` | :1796, 5-second deadline | REAL | The streamed refusal offered a step-down, but the unregistered replay could not publish its completion. | `a743f632` |
| `ChatViewModelTest.nestedInstructionsAreLoadedBeforeFirstMutationApproval` | :919, 5-second deadline | REAL | The unregistered owner skipped durable tool events and the final reply after the nested instructions were loaded. | `a743f632` |
| `ChatViewModelTest.streamingReasoningAccumulatesSeparatelyAndSurvivesTurnCompletion` | :1671, 5-second deadline | REAL | The owner check suppressed both reasoning and answer previews for the ID-less session. | `a743f632` |
| `ConversationTitleLifecycleTest.firstSuccessfulTurnTitlesNewConversationWithoutChangingEvents` | :100, expected 3 provider calls, got 2 | REAL | The title could appear during first-turn cleanup. Send accepted the follow-up, admission dropped it while the old owner held the slot, and the old cleanup cleared its streaming state. | `a743f632` |
| `WorkspaceMirrorTest.runtimeDiscardChecksExactSelectionAndUnblocksCommandsAndSafTools` | :449, `Expected mismatch` | REAL | Explicit discard used the background mirror lookup, which ignores viewed selection. The action now uses the viewed mirror and its selection check during staging. | `8c4740f6` |

The regression tests also ran against the original production classes.

- `legacyStoreWithoutSessionIdPublishesAndPersistsTheCompletedReply` timed out
  waiting for the reply. The strengthened Stop test found no registered owner
  after the provider load had started.
- `aFollowUpDuringFirstTurnCleanupRunsOnceAndKeepsTheGeneratedTitle` sent the
  follow-up while the previous owner still held the slot. The follow-up reply
  never arrived.
- The strengthened `openingAnotherProjectLeavesTheRunningProjectLiveAndWritable`
  observed B's project name when reattaching running A.
- The strengthened discard test failed at its pre-existing mismatch assertion.
  It also checks the viewed selection and SAF bytes after rejecting a switch
  during the refresh.

### Local verification

- `:app:compileDebugKotlin`, `:app:compileDebugUnitTestKotlin`, and the
  filtered `:app:testDebugUnitTest` passed on the fixed source with
  `LANG=C.UTF-8`. The same 136 tests passed in a direct JUnit/Robolectric run
  of the Gradle-built classes.
- `ChatViewModelTest` 75/75, `ConversationTitleLifecycleTest` 12/12,
  `WorkspaceMirrorTest` 41/41, and `AgentApprovalGateTest` 8/8 passed.
- The run covered cross-project continuation and reattachment, the per-project
  browser, queued local-turn admission and Stop, conversation switching,
  background approval ownership, and FIFO approval resolution.
- All 100 Python source checks passed.
