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
