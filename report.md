# Rivet flaw sweep, remainder

Branch `flaw-sweep-2` started from `origin/batch/surface-fix` at
`eb6f4f7840b0ecc37c2ef7266e83a0a13583db46`. The first-sweep merge omitted
`flaw-sweep-inventory.md` from the branch tree; the untracked
`flaw-sweep-inventory.md` in this worktree is the 55-row inventory from
`4a71b97b`.

The inherited branch did not compile. `7a3d5aca` added a `fingerprint` local
to a `WorkspaceMirror` block that already had one. `c2b5a077` renames the
publish-side local. The other changes after the inherited implementation
commits are the test repairs and the one production regression fix listed
below. `:app:compileDebugKotlin` fails on `05e86973` and succeeds on
`c2b5a077`.

The dsh references below name packages and shipped source files under
`~/workspace/dsh-cli/node_modules/@deepseek-ai/`. Existing rows cite the
commit that established the behavior on the starting branch. New rows name
the implementation commit and its immediate parent in the verification
section.

| # | Verdict | Behavior and source evidence | Commit | JiTTest or baseline evidence |
|---|---|---|---|---|
| 1 | COPY | Existing `AgentLoop` advises at 3/5/8 repeated calls and continues after deterministic failures. Matches `dsh-repeat-tool-reminder/src/index.ts:2223–2260`. | `c902cd94` | First-sweep `repeatedIdenticalFailureGetsAdviceAndTurnContinues`. |
| 2 | COPY | Existing loop uses the same advisory path for repeated reads. The unchanged-read turn stop is absent. | `c902cd94` | Repeat advice path and removed unchanged-read counter. |
| 3 | DELETE | Existing loop has no iteration or tool-count ceiling. | `c902cd94` | Base code has no `RunawayGuard` or runaway stop reason. |
| 4 | COPY | Existing tool failures stay correlated results. dsh commits materialized errors in `dsh-tools/src/pipeline.ts:204–225,494–508`. | `c902cd94` | First-sweep `runtimeFailureResultNoLongerEndsTheTurn`. |
| 5 | COPY | Existing refusal policy remains. Fixed one remaining batch defect where a refusal appended placeholder results for siblings that still ran. Each sibling now gets its own result. dsh handles each approval outcome in `dsh-tools/src/pipeline.ts:431–479`. | `b533c2b2` | `policyRefusalDoesNotConsumeTheNextToolResult`. |
| 6 | COPY | Existing Undo failure records a broken checkpoint and lets the mutation run. Session durability remains a separate prerequisite, as in `dsh-session-checkpoint-policy/src/index.ts:64–82`. | `7b044d4b` | First-sweep `checkpointFailureRunsApprovedMutationWithoutUndo`. |
| 7 | PROTECTS | Broken Undo state stays recorded. Undo verification still refuses to overwrite external changes. | `7b044d4b` | Baseline `RunCheckpointGate` and restore checks. |
| 8 | COPY | Empty model steps use the same cancellable retry policy as transient failures. The requests replay the same history and tool receipts. See `dsh-llm/src/retry-policy.ts` and `dsh-llm-retry/src/index.ts:212–237`. | `b533c2b2` | `PostToolCompletionTest`, including six empty attempts and a recovery after a tool receipt. |
| 9 | COPY | Remaining failures finish the run and release the composer. Context-stop results now offer Retry too. Provider preflight failures can continue to the request. dsh appends `turn/end` in `dsh-agent-loop/src/agent.ts:418–428`. | `9ba3f1fb` | `automaticCompactionFailureLeavesTheRequestUsable`, plus cloud preflight integration. |
| 10 | COPY | The coordinator waits for worker results after the model loop goes idle. Completion can resume the model with worker events. Teardown cancels workers before a bounded join. See `dsh-subagent/src/manager.ts:251–282` and `dsh-acp/src/session.ts:482–506`. | `8634ccfa` | `idleWaitDeliversWorkerResultsOnce`, `silentWorkerEndsWithAReasonAndReleasesTheCoordinator`. |
| 11 | COPY | Await uses elapsed time while a worker is waiting for a person. Timeout returns the existing pending-worker result. See `dsh-tool-subagent-control/src/wait-agent.ts:67–119`. | `8634ccfa` | `approvalWaitCountsAgainstTheAwaitBudget`. |
| 12 | COPY | F3 already added 120-second expiry, caller cancellation, queue removal and exact-token grant validation. `AgentLoop` turns expiry and unavailable approval into tool results. See `dsh-user-approval/src/index.ts:242–261,293–333`. | `13f79473` | `AgentApprovalGateTest` expiry and FIFO coverage. |
| 13 | COPY | The UI already disabled submission during recovery. A rejected send now reports why and releases the pending composer submission while retaining the draft. dsh normally queues into `dsh-agent-loop/src/agent.ts:171–178` and `src/inbox.ts:108–112`. | `96a74ba8` | `stopWhilePreparingKeepsTheAcceptedMessageAndStartsNoSecondTurn` checks the refused-send notice. |
| 14 | COPY | Blank or duplicate IDs and over-nested arguments get individual errors. IDs remain unique and a valid sibling still runs. See `dsh-tools/src/pipeline.ts:154–182`. | `b533c2b2` | `malformedCallsGetCorrelatedErrorsWhileTheValidSiblingRuns`. |
| 15 | PROTECTS | Exact workspace identity still cancels the current work before it can dispatch against another project. | `8bbc8e15` | Existing workspace identity checks and cancellation. |
| 16 | DELETE | Startup project restore no longer installs a persisted pending-changes gate. | `7b044d4b` | Startup no longer queries the removed sync blocker. |
| 17 | COPY | Pending paths are reported in command sync data. `commandBlocker` and `backgroundBlocker` check runtime availability and device admission. dsh starts in the real cwd in `dsh-bash-local/src/index.ts:122–133`, with file-local version checks in `dsh-fs-local/src/index.ts:345–359`. | `7b044d4b` | First-sweep `runtimeDiscardChecksExactSelectionAndUnblocksCommandsAndSafTools`. |
| 18 | DELETE | Foreground commands run in the available worktree after attempting sync. A dirty mirror does not refuse the command. | `7b044d4b` | First-sweep pending-change test and foreground call path. |
| 19 | DELETE | Exit outcome and sync warning travel in the same result. `AgentLoop` has no sync-conflict stop classification. | `7b044d4b`, `c902cd94` | `runtimeFailureResultNoLongerEndsTheTurn` and `CommandToolTest`. |
| 20 | DELETE | SAF tools operate directly on the selected documents. The workspace-wide `requireSafCurrent` prerequisite is absent. | `7b044d4b` | First-sweep pending-change test and direct SAF tool paths. |
| 21 | DELETE | A pending path leaves the last complete worktree available to the terminal. | `7b044d4b` | `openTerminal` dirty-worktree fallback at base. |
| 22 | DELETE | Background processes start in the available worktree after attempted sync. | `7b044d4b` | `spawnProcess` no longer rejects pending or failed mirror sync. |
| 23 | COPY | `WorkspaceMirror` publishes a new file by filling and verifying a random SAF sibling, then renaming into an unoccupied destination. Failed publication cleans up the known sibling. See `dsh-atomic-write/src/index.ts:80–92`. Providers without rename retain the legacy receipt path. The first version of this code did not compile; `c2b5a077` names the publish fingerprint apart from the post-create empty-file check. | `7a3d5aca`, `c2b5a077` | `refusedPublishLeavesNoEmptyDestinationOrReceiptAndCanRetry` and legacy receipt tests; the compile result above is the parent-failure proof for `c2b5a077`. |
| 24 | PROTECTS | `WorkspaceMirror.recover` still preserves dirty `previous/` and unverified discard state. | `e5ed3a73` | Existing recovery test explicitly preserves the dirty previous tree. |
| 25 | COPY | Foreground process execution releases `RuntimeController.operations`. Shells can attach to the same worktree while the command runs. Restore and discard still wait for a live worktree writer. dsh controls execution and ordered commits in `dsh-tools/src/batch.ts:186–196`. | `9e9e70d3` | `foregroundTimeoutPromotesTheSameProcessWithoutHoldingTerminalAdmission`. |
| 26 | COPY | Device slot refusal remains tool-local. dsh reports job start failures as results in `dsh-jobs/src/index.ts:73–79,106–114`. | `c902cd94` | Base process registry and tool-local failure handling. |
| 27 | PROTECTS | Thermal admission, low-battery handling and memory enforcement remain. The first sweep removed their turn-stop amplification. | `c902cd94` | `TerminalGovernor` device policy and tool-local result path. |
| 28 | PROTECTS | Background processes still need Android foreground-service admission. | `c902cd94` | Existing `foreground_service_unavailable` result path. |
| 29 | COPY | Cancellation while the current terminal starts settles the pane with a launch error. Stale launches still release their handles. The async start had also overwritten a checkpoint-restore notice; `fc30c329` carries that notice through the start. See `dsh-terminal/src/index.ts:224–227`. | `18695d23`, `fc30c329` | `cancelledLaunchLeavesAnExplicitTerminalError`, `restoreNoticeMarksAReplacedSessionAndClearsOnWrite`. |
| 30 | COPY | A running command can outlast its foreground wait under the same managed process ID. Existing device admission and foreground-service requirements still apply. See `dsh-tool-bash/src/index.ts:345–380`. | `9e9e70d3` | `foregroundTimeoutPromotesTheSameProcessWithoutHoldingTerminalAdmission`. |
| 31 | PROTECTS | Descendant counting, PSS watchdog and process-tree termination remain in place. | `59601da4` and existing governor commits | Existing command tree checks. |
| 32 | COPY | Command streams spill to app-cache files with workspace-owned handles. Existing line paging still works, and byte paging reaches the full stdout and stderr. See `dsh-tool-bash/src/index.ts:94–99,152–169` and `dsh-bash-local/src/index.ts:342–363`. The inherited test file dropped the `assertNotNull` import its own clear test uses; `61d6167d` restores it. | `05e86973`, `61d6167d` | `earlierSpillsRemainReadableAfterMoreThanEightCommandsAndReopening`, `bytePagingCanReadBeyondTheLinePreviewWithoutSplittingUtf8`. |
| 33 | COPY | Terminal close and background exit preserve the sync outcome. Terminal UI names a pending path and `process_output` carries sync fields. Android SAF still requires a mirror for shell access. See `dsh-fs-local/src/index.ts:225–235,269–273`. | `18695d23` | `closingReportsThePathThatCouldNotSync`. |
| 34 | PROTECTS | Discard still requires explicit user action and an exact project identity check. | `7965fa5e`, `7b044d4b` | Existing discard and workspace ownership tests. |
| 35 | DELETE | Full toolchain storage no longer latches preparation as done. Terminal and command results report the condition while the shell remains available. | `1bf08c08` | `fullStorageIsReportedAndPreparationCanRetry`. |
| 36 | DELETE / COPY | The tool deadline no longer discards the reply channel. It returns a pending operation ID that the same chat can poll without resending the command. Termux owns the external process. dsh promotes jobs in `dsh-tool-bash/src/index.ts:361–374`. | `e71dea82` | `lateCallbackCanBeReadWithoutDispatchingTheCommandAgain`. |
| 37 | COPY | Coordinator and worker SQLite event stores keep correlated results above 512 KiB. The threshold triggers compaction. The legacy one-value store retains its serialization bound. See `dsh-compaction-basic/src/region.ts:410–426,500–508`. | `73866fe3` | `longSqliteProjectionKeepsTheCorrelatedReceiptOnRecovery`, `compactsAndAcceptsAnActiveProjectionOver512KiB`. |
| 38 | COPY | Cloud requests continue after failed pressure compaction. A provider overflow still gets one retry when the request got smaller. The two-summarizer lifetime counter is gone. Native local capacity checks remain. See `dsh-compaction-basic/src/index.ts:157–175,213–257` and `src/config.ts:106`. | `9ba3f1fb` | `summaryProviderFailureStillSendsTheMainRequest`, `failedSummaryKeepsFullOriginalHistory`, `cloudProviderGetsTheRequestEvenWhenTheLocalEstimateIsOver`. |
| 39 | DELETE | F3 already uses a durable budget pause with Resume and a one-run cap raise. Unpriced routes use token evidence. Caps are user policy and still apply. | `beecf51d` and existing budget pause code | `RunSpendLedger` and `ChatViewModel` budget pause and resume paths. |
| 40 | COPY | Transient model failures retry with cancellable backoff and valid short HTTP Retry-After delays. See `dsh-llm/src/retry-policy.ts`, `dsh-llm-retry/src/index.ts:212–237` and `dsh-llm-deepseek/src/transport.ts:37–39`. | `b533c2b2` | `transientFailuresRetryBeforeToolDispatchAndHonorRetryAfter`. |
| 41 | COPY | The idle/progress watchdog uses the same timeout retry policy. The 16 MB memory bound still applies. See `dsh-llm-deepseek/src/adapter.ts:68–80`. | `b533c2b2` | The transient retry test includes an SSE progress timeout. |
| 42 | COPY / PROTECTS | Excess capacity and overlapping work return `activation_limit_reached`. The existing phone limits and protected 20-spawn bill boundary stay in effect. See `dsh-subagent/src/activation.ts:23–33`. | `8634ccfa` | `executeSpawnRefusesCapacityUntilTheCallerRetries`, `overlappingWriteScopesAreRefusedEvenWithFreeSlot`. |
| 43 | COPY | Pausing stops admitted work. Refused new work produces no backlog. Recovery can still resume already-admitted workers, including legacy queued records. See `dsh-subagent/src/manager.ts:194–233`. | `8634ccfa` | `pauseKeepsAdmittedWorkersWithoutAddingABacklog`. |
| 44 | PROTECTS | Read-only restrictions and worker file-scope enforcement stay in place. | `f9fac68a`, `64ac43f9` | Existing worker write-policy and shell-scope tests. |
| 45 | COPY | Compacted checkpoints mark omitted history in the stored checkpoint and its model text. The recent tail and canonical history remain available according to the existing retention policy. See `dsh-compaction-basic/src/region.ts:410–426,506` and `dsh-compaction/src/index.ts:92–106`. | `9ba3f1fb` | `trimmingKeepsTheNewestStateAndRecordsTheOmissionAcrossReload`. |
| 46 | COPY | Run recovery searches the owning chat's records using the transcript tail and persists a prompt digest for new runs. Starting a new turn explicitly settles a superseded pause. App-private journal save failures generate a recovery notice. dsh's event format and flush boundary are in `dsh-session/src/types.ts:206–227` and `dsh-session-checkpoint-policy/src/index.ts:64–82`. | `41fd3d48` | `recoveryCanFindTheInterruptedTurnThatOwnsTheTranscriptTail` and existing receipt recovery test. |
| 47 | COPY | Both loops observe paths after successful tool execution and load instructions for the next request. See `dsh-agent-instructions/src/index.ts:330–373`. | `1ed150b6` | `nestedInstructionsAreLoadedAfterTheMutationSucceeds`. |
| 48 | COPY | Depth, directory count and evicted observed paths now produce prompt notices. See `dsh-agent-instructions/src/render.ts:303–313`. | `1ed150b6` | `directoryAndDepthOmissionsReachThePrompt`. |
| 49 | COPY | Project goals are app-owned. Existing files are imported once. File tools can edit the legacy goal file and the existing memory reservation still applies. See `dsh-goal/src/index.ts` and `dsh-tool-goal/src/authority.ts:99–116`. | `db04ecfe` | `projectGoalMigratesOnceAndMemoryStillUsesTheProjectFile`, `legacyGoalFileCanBeEditedAsAnOrdinaryFile`. |
| 50 | DELETE | Bundled scripts now follow ordinary command approval. The first-use matcher, added prompt and standing grant were removed. | `cd501b8e` | `inspectingAnInstalledScriptUsesNormalCommandApproval`. |
| 51 | PROTECTS | Browser auth, SSO, renderer memory and device form safety continue to use the existing policies. | Base `eb6f4f78` | Browser safety sources unchanged. |
| 52 | COPY | A denial applies to the pending call. A repeated request receives its own ordinary policy decision. See `dsh-tools/src/pipeline.ts:431–479`. | `b533c2b2` | `deniedMutationGetsItsOwnDecisionAfterOverflowCompaction`. |
| 53 | COPY | A result over the wire budget keeps UTF-8-safe head and tail with an explicit marker and original success/error status. See `dsh-compaction-tool-result-pruner/src/index.ts:83–121`. | `b533c2b2` | `oversizedToolResultKeepsBothEndsWithoutChangingTheOutcome`. |
| 54 | COPY / DELETE | Background output tracks retained-start and total UTF-8 byte positions. Shell jobs run until settlement, stop, or teardown. Old `ttl_ms` arguments decode without applying a timer. See `dsh-jobs-local/src/ring.ts:37–46,100–111` and `dsh-jobs/src/types.ts:243–247`. | `830e0716` | `reportsTheAbsoluteByteOffsetAfterEviction`, `spawnProcessIsPersistentProcessWithApproval`. |
| 55 | PROTECTS | Project-load access failures still explain the invalid binding. | `1e08b6be`, `c3de3107` | Existing project binding path in `ChatViewModel`. |

## Immediate parents

| Implementation | Parent | Rows |
|---|---|---|
| `b533c2b2` | `eb6f4f78` | 5, 8, 14, 40–41, 52–53 |
| `1ed150b6` | `b533c2b2` | 47–48 |
| `cd501b8e` | `1ed150b6` | 50 |
| `830e0716` | `cd501b8e` | 54 |
| `8634ccfa` | `830e0716` | 10–11, 42–43 |
| `96a74ba8` | `8634ccfa` | 13 |
| `18695d23` | `96a74ba8` | 29, 33 |
| `1bf08c08` | `18695d23` | 35 |
| `db04ecfe` | `1bf08c08` | 49 |
| `73866fe3` | `db04ecfe` | 37 |
| `9ba3f1fb` | `73866fe3` | 9, 38, 45 |
| `7a3d5aca` | `9ba3f1fb` | 23 |
| `41fd3d48` | `7a3d5aca` | 46 |
| `9e9e70d3` | `41fd3d48` | 25, 30 |
| `e71dea82` | `9e9e70d3` | 36 |
| `05e86973` | `e71dea82` | 32 |
| `c2b5a077` | `05e86973` | 23 (compile fix) |
| `61d6167d` | `c2b5a077` | 32 (test import fix) |
| `fc30c329` | `61d6167d` | 29 (restore-notice regression) |

## Test repairs

The inherited test pass was not real: the branch did not compile. Once it compiled,
the focused suite exposed stale and regressed tests. These commits repair the tests
or the one production regression; none changes an inventory verdict.

| Commit | Parent | What it repairs |
|---|---|---|
| `9ceecc5b` | `fc30c329` | `AgentLoopTest`: per-request denial (not the removed latch), the retry policy, and head/tail result truncation. |
| `5ea39829` | `9ceecc5b` | `ContextPreparationTest`: a failed summary surfaces to the caller without rewriting history. |
| `7b2519db` | `5ea39829` | `ChatViewModelTest`: wait for project recovery before sending; use a non-retryable error for the partial-answer retry case. |
| `365b10b5` | `7b2519db` | `CodingSessionsTest`: the recovered projection is trimmed to the active cap and keeps the prior summary and one synthetic tool result. |
| `b75ade70` | `365b10b5` | `WorkspaceMirrorTest`: drive the replacement test through the legacy no-rename receipt path. |

## Verification

Environment: JDK 17 (`/home/hatch/tmp/jdk-17.0.13+11`), Gradle 8.13,
Android SDK 35 (`/home/hatch/android-sdk`), `GRADLE_USER_HOME` a workspace copy
of the prepared dependency cache, `-Djava.net.preferIPv4Stack=true` for the
Gradle/Kotlin daemons, Kotlin compiled in-process, and `LANG=C.UTF-8` for the
test JVMs. The sandbox's default locale is US-ASCII; the mirror test uses a
Unicode path and fails under it. CI is UTF-8, so this is an environment
workaround, not a code change.

Real results from this worktree:

- `:app:compileDebugKotlin` on `05e86973` (the inherited head): **FAILED** —
  `WorkspaceMirror.kt:255` and `:284` declared the same `fingerprint` local.
- `:app:compileDebugKotlin` on `c2b5a077`: **PASSED** (`BUILD SUCCESSFUL in 17m 8s`).
- `:app:compileDebugUnitTestKotlin` on `61d6167d`: **PASSED** (`BUILD SUCCESSFUL in 3m 59s`) after the missing `assertNotNull` import.
- Focused suite on the final tree, 28 classes: **425 tests, 0 failures, 0 errors, 0 skipped** (`BUILD SUCCESSFUL in 1m 49s`; final result files in `app/build/test-results/testDebugUnitTest/`).
- `:app:compileDebugKotlin` on the final tree: **PASSED** (`BUILD SUCCESSFUL in 39s`, all tasks up to date).

The first focused run failed 11 of 425 tests. The failures were stale
expectations for mechanisms this sweep removed (denial latch, no-retry policy,
`output_limit` replacement, two-summary cap, exact 512 KiB projection), two
`ChatViewModelTest` races that waited only for `ready` before sending, one
Unicode-path mirror test under the sandbox's US-ASCII locale, and the terminal
restore-notice regression. All are explained; the final run above is after
those repairs.

Parent-failure evidence: `c2b5a077` is proven by the compile failure on its
parent `05e86973`; `fc30c329` is proven by
`restoreNoticeMarksAReplacedSessionAndClearsOnWrite` failing on the pre-fix
tree and passing in the final suite. The other rows name their immediate
parent in the table above and ship the named JiTTest. I did not rebuild every
parent commit: a full Kotlin compile in this worktree takes about 17 minutes,
and there are sixteen parent checkouts. That is the main verification gap.

## Gate fix

CI run `45781513337` on `a8b5d159` ran 1894 tests and failed three. I checked
the parent `eb6f4f78` first, in a detached worktree using the same dependency
cache: all three failing methods passed there (`CoordinatorBudgetTest` 2/2 and
`ConversationTitleLifecycleTest.failedIncompleteOrBlockedToolTurnNeverRequestsTitle`
1/1, `BUILD SUCCESSFUL`). None is PARENT-RED.

| Failure | Verdict | Evidence | Fix commit |
|---|---|---|---|
| `CoordinatorBudgetTest.emptyCloudProjectPinsTheSyntheticBaseline` | SWEEP-CAUSED (test pin) | Branch pin was 7,243; actual request is 7,269. Parent passes at 7,243. The test's dynamic `baseline` checks at lines 56–57 still pass. | `bc14219e` |
| `CoordinatorBudgetTest.emptyCloudProjectPinsTheDeviceBaseline` | SWEEP-CAUSED (test pin) | Branch pin was 7,250; actual request is 7,276. Parent passes at 7,250. Same cause and same +26 delta. | `bc14219e` |
| `ConversationTitleLifecycleTest.failedIncompleteOrBlockedToolTurnNeverRequestsTitle` | SWEEP-CAUSED (test scenario) | Branch times out at the failed-turn wait; parent passes the same test in 18.7 s. The turn still never requests a title; the old empty-response mode now enters the retry path. | `52db883b` |

The coordinator delta is the `run_command` schema added by `05e86973`: cached
output byte paging (row 32) adds the `byte_offset` and `output_stream`
properties. The test sums the serialized tool definitions, and those two
properties add 26 estimated tokens (about 78 bytes at the 3-bytes-per-token
estimate) for both models; the model-to-model difference remains 7 tokens. The
feature needs the properties, so the pinned baselines move by the same
deliberate amount. This is not a bare number bump: the dynamic request size and
the reading still agree, and only the intentional prompt-growth pin changed.

The title failure is the retry policy from `b533c2b2`: `ProviderError.EmptyResponse`
is now retryable, and `AgentLoop.requestWithRetry` makes five retries before it
surfaces `EmptyResponse` (row 40). This test sets `Dispatchers.setMain` to an
`UnconfinedTestDispatcher`; the retry `delay`s run on that virtual scheduler
while the test's `withTimeout` is real-time, so the previous empty-response mode
never advanced. The production retry is intended and is covered by
`PostToolCompletionTest.aBlankBeforeAnyToolAlsoUsesTheRetryPolicy` (six
requests). The title guard test now uses an explicit non-retryable
`ProviderError.IncompleteGeneration("empty")`, which still exercises the
failed/incomplete/blocked turn invariant and the no-title assertion without
depending on the retry scheduler.

Local gate after the fixes: `:app:compileDebugKotlin` passed; the gate classes
ran 14 tests with 0 failures; the 28-class focused suite ran 425 tests with 0
failures, 0 errors, 0 skipped. A combined run of both sets ran 30 classes and
439 tests with 0 failures, 0 errors, 0 skipped.

One additional local flake was observed while rerunning the gate classes:
`ConversationTitleLifecycleTest.cancelledTurnNeverStartsTitleRequestOrRegeneratesLater`
failed once with `expected:<2> but was:<1>` because its wait saw
`acceptedMessageCount == 2` before the second provider call. It passed
unchanged on the next rerun and is not one of the three CI failures; I left it
unchanged.

## Honest gaps

- The inherited branch did not compile, so its earlier report could not have run
  the tests it named. The fixes here restore the compile and the test pass; the
  results above are the first real runs in this worktree.
- Not every parent checkout was rebuilt (see the verification note). The named
  JiTTests and their immediate parents are listed, but only `c2b5a077` and
  `fc30c329` were observed failing on their parents here.
- The local test run needs `LANG=C.UTF-8` and an IPv4 daemon flag in this
  sandbox. CI has a UTF-8 locale and does not need the flag; the code does not
  depend on either.
- SAF providers without rename still use the recoverable create receipt. SAF
  supplies provider-defined rename semantics, and a provider failure or
  process death can leave a temporary sibling visible. Existing legacy
  receipts remain readable.
- Shell writes still reach a private worktree first. Terminal and process
  results expose copy-back failures. A physical Android device is required
  to exercise the JNI process tree, foreground service promotion, and real
  document providers together.
- The local native path retains its capacity preflight. The legacy
  single-value conversation store retains the byte limit it needs for
  migration and recovery.
- Journal records remain atomically updated snapshots backed by durable
  conversation events. Older runs can be recovered for a matching tail.
  There is no UI for switching between arbitrary earlier run snapshots in
  the middle of a newer transcript. Project-copy writes remain best effort
  after the app-private record is saved.
- Command spill files are in Android's app cache and can be reclaimed by the
  platform. Termux operation handles survive subsequent turns in the same
  chat for the lifetime of the view model. Termux owns external execution
  and its reply channel does not survive Rivet process death.

---

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

---

# Rivet N1–N7 device-report fixes

Branch `fix-n1n7` starts at `6f9f78adaf462b8daef3e539c7375084f36bd134` on `batch/surface-fix`. The parent column gives the immediate parent of each implementation commit. The regression run uses the original branch point's production code with the new tests copied in.

| Item | Implementation | Parent |
| --- | --- | --- |
| N1 | `0d78aa73`, `88a03972` | `6f9f78ad`, `a18a8882` |
| N2 | `12429cfa` | `0d78aa73` |
| N3 | `a18a8882`, `9ba48327`, `d095dc34` | `3908446b`, `88a03972`, `565143ed` |
| N4 | `135b8513` | `12429cfa` |
| N5 | `889972d5` | `135b8513` |
| N6 | `ac8b7e8b` | `889972d5` |
| N7 | `3908446b`, `565143ed` | `ac8b7e8b`, `9ba48327` |

## N1 · Termux replies

The branch point already has the explicit private receiver and decodes Termux's `result` bundle. For runs with a project output directory, `TermuxFileWait.kt` discarded the decoded callback when the files were missing or unreadable. It also waited past a published exit status when both streams were empty. The wrapper redirected both streams, so a valid callback could arrive without any output to recover.

`TermuxCommand.kt` and `TermuxBridge.kt` return a bounded preview of each stream alongside the saved files and keep the command's exit status. `TermuxFileWait.kt` prefers a completed file result, accepts useful callback output when the files are unavailable, and preserves a Termux error if neither path supplies output. The file marker can complete a silent command. The callback transport uses the [Termux RUN_COMMAND result contract](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).

JiTTests exercise the saved-output shell wrapper with real stdout, stderr and exit status, the file-wait path with an unreadable project and a usable callback, and a successful command with empty streams.

## N2 · Starter openings

`DsmlToolCalls.kt` already intercepts streamed DSML and dispatches complete calls through the normal agent loop. The tester's envelope has a closed `run_command` invocation followed by an empty `git_status` invocation with no closing tag. The parser lost that second call and could also absorb a following invocation into an earlier unclosed one. It now splits on invocation starts and accepts an omitted closing tag for a blank argument body. Native tool calls retain precedence.

The JiTTest streams the tester's shape one character at a time. It checks that the opening sentence is the only visible text and that both tool names and argument objects reach the agent's tool-call response.

## N3 · Compaction, worker output, and recall

- `CodingSessions.kt` commits the visible compaction notice with the durable checkpoint, including the storage-pressure path. Previously manual and automatic compaction relied on a later save through `ChatViewModel.kt`, and storage-pressure compaction skipped that save. New markers live in the display history. The projection validator still accepts earlier saved markers.
- `ContextPreparation.kt` pins the session's explicit goal when summarizing. `TaskCheckpoint.kt` prioritizes short user quotes that explicitly pin a fact. `ArchiveRecall.kt` recognizes the pinned-number question and the existing archive answer path includes the saved goal for a combined question.
- `ResultEnvelope.kt` and `SubagentContext.kt` bound the coordinator's awaited payload to 6 KiB and each pushed payload to 3 KiB. Previously the limits were 24 KiB for an await and up to 32 KiB for a pushed result. `AgentContext.kt` can compact oversized legacy await results into a structured 3 KiB result while keeping the call/result pair and the original event history.
- `ChatScreen.kt` takes a reader to a newly submitted command reply even when they were scrolled back. `ChatViewModel.kt` remaps transcript metadata when inserting a command notice drops an old visible row.

JiTTests reopen a newly committed checkpoint before any further save, compact a 33-turn history with an oversized worker result in the newest group, check truncation at both worker delivery points, and exercise a 33-turn saved-goal and pinned-5000 round trip. The transcript test scrolls away from a compaction marker, inserts a `/goal` reply, and verifies that the reply comes into view.

## N4 · Phone captures in the project

A bound project already had a SAF write path in `ChatViewModel.kt`. `PhoneCaptureTool.kt` treated the writer as optional and reported the private cache file as a successful deliverable when there was no destination. Successful results now require a capture saved through the conversation's project grant. The model's vision attachment can still use the local capture while the chat image and file tools use the project-relative path.

The JiTTest submits a capture without a workspace writer and checks that it returns a tool error before invoking capture. The bound-path test verifies that the reported project path and the vision image are both available to their consumers.

## N5 · Worker selection

`ChatViewModel.kt` let an earlier transcript read publish after another selection or after close. One selected worker ID now owns the sheet, older reads are cancelled, and `RivetApp.kt` and `WorkerSheet.kt` use the same loaded record for the title and selected chip. The chip uses Compose's selected state for its appearance and accessibility.

The JiTTest closes the sheet with a read pending and checks that the old transcript cannot reopen it.

## N6 · Goal notice

`GoalMemory.kt` put an excerpt of automatically recorded assistant progress inside the `/goal` status. The excerpt was clipped at 160 characters and looked attached to the assistant's reply. Status now shows the goal, state and set date. `SlashCommands.kt` also normalizes invisible formatting within a repeated slash prefix, which could previously send a visually correct command to the unknown-command path.

JiTTests verify status with a long prior assistant answer and dispatch of a slash prefix containing a zero-width character. An intermittent failure from byte-identical plain `/goal` input did not reproduce in code. Its device input bytes were unavailable.

## N7 · Jump to latest

The chip exists at the branch point in `ChatScreen.kt`. Its visibility depended on follow mode and a 72 dp near-bottom threshold as well as the list's scroll state. The chip now appears whenever the conversation can scroll forward, has an accessibility button role, and reattaches follow when pressed.

The JiTTest moves the real Compose transcript a small distance from the bottom, presses the chip and checks that it disappears at the end. The existing longer gesture test covers following a growing reply after the jump.

## Verification

### Gate re-run 45744527879

CI failed two browser tests that this branch does not modify:
`BrowserEvalSessionTest.evalReturnsBoundedSuccessAndOrigin` and
`BrowserKeyModalTest.browserKeyStillSucceedsWithoutAModal`.

Both files, and the browser production code they exercise, are byte-identical
at the branch point `6f9f78ad` and at `dd0cf165`:

    git diff 6f9f78ad..dd0cf165 -- app/src/main/java/com/kaiser/rivet/browser \
                                     app/src/test/java/com/kaiser/rivet/browser

is empty. The six tests in the two classes pass on the parent checkout and on
the branch. The CI failure is therefore parent-red, not branch-broke.

### Browser teardown race

The failure is in `@After`, not in either test body: kotlinx-coroutines-test
reports that `Dispatchers.Main` is used concurrently with `resetMain()`.
`BrowserSession.onPageFinished` starts the post-load budget job, which jumps
to `Dispatchers.Default` and then hands back to `Dispatchers.Main`. When the
test ends before that handoff, the handoff can read Main while the next
`resetMain()`/`setMain()` writes it.

`BrowserSession.awaitPendingWorkForTest()` now cancels and joins the session's
transient renderer jobs. Both test classes wait for it before resetting the
test dispatcher. This is a test-support seam; no production behaviour changes.

### Local results

- `BrowserEvalSessionTest` and `BrowserKeyModalTest`: 6 tests green on
  `6f9f78ad` and on `dd0cf165` with the fix.
- Full `:app:testDebugUnitTest`: 1904 tests, 1903 pass. The only failure is
  `WorkspaceMirrorTest.materializesTextBinaryNestedUnicodeAndEmptyFiles`,
  which also fails on `6f9f78ad` in this container; the CI gate did not
  report it.
- `python3 -m unittest discover -s scripts -p 'test_*.py'`: 100 tests OK.

## Honest gaps

- No handset was attached. The Samsung callback from another Android package, live starter-provider output, Android capture consent and notification, SAF behavior on that device, and visual rendering on its screen still need a device pass.
- The Termux report's package-targeted callback predates the explicit private receiver at the branch point. The reproduced drop on this branch was in the file-result path. The reported Termux storage appops were outside this change.
- The recall fixtures verify Rivet's saved state, provider request preparation, and archive reply. They do not measure factual recall across live providers or reconstruct device prompts beyond the captured evidence.
