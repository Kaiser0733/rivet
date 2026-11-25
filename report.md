# Rivet F3 run integrity

Branch `flaw-run` starts at `6f9f78ad` from `origin/batch/surface-fix`. Hunt 1 D2, D3, D5 and D6 came from the copy of `flaw-hunt-big-tasks.md` in the adjacent Rivet worktree. Hunt 2 sections 3.1, 3.2 and 5.1 came from `flaw-hunt-2-report.md` at `origin/docs/flaw-hunt-2`. The line references below are for the starting commit. Version fields and self-updater files have no diff.

## 1. Workers with no live execution

`SubagentSupervisor.kt:311–379,539–543,624–633` restored stored Running rows into the new supervisor without restoring their jobs. The coordinator's completion hold could wait ten minutes for an execution that no longer existed. The live launcher also lacked an idle deadline.

Recovery persists unowned Running workers as Failed and supplies an explanation in the worker result and UI row. The coordinator can collect that terminal result immediately. Live workers renew a five-minute deadline with streamed text, reasoning or worker events. A timeout or unexpected cancellation supplies a failed result. Explicit user stop and supported pause reasons retain their own states.

**JiTTests.** `killedWorkerIsFailedAndDoesNotHoldTheResumedCoordinator` and `silentWorkerEndsWithAReasonAndReleasesTheCoordinator`. Parent: the recovery test found Running where it expected Failed. The first test uses a persisted Running record to simulate a killed process. The idle test exercises a cancellable provider using a short deadline. A provider or native callback that ignores cancellation can delay cleanup.

## 2. Replay after interruption

`AgentLoop.kt:571` executed each tool before it emitted the group result. `RunJournal.kt:32–50,264–268` stored step summaries without operation identities. The retry path in `ChatViewModel.kt:1783` reused the user prompt, and session recovery inserted a synthetic interruption result for unfinished groups.

The project run journal now commits a start receipt and a result receipt for each side-effecting call in app-private storage, with a best-effort project copy. Receipts distinguish a completed action from an action whose effects are unknown. A resumed loop compares the operation and normalized arguments, independent of provider call IDs, and returns a correlated `already_done` or outcome-unknown result without re-executing the action. The journal lists recent receipts in model context. The most recent user turn in the persisted transcript supplies a fallback for older runs and chat-only sessions.

**JiTTests.** `resumedWriteIsReportedAsAlreadyDoneAndNeverOverwritesTheFileAgain` and `receiptSavedBeforeTheToolBatchSurvivesReopening`. The separate receipt crash-window test uses the new journal receipt API. Arbitrary commands can express the same action with different arguments. Rivet compares normalized calls and does not infer that two different commands have the same effects. If an action completes immediately before death and the result receipt was never written, Rivet withholds a matching replay and reports the outcome as unknown. Chat-only sessions use transcript evidence.

## 3. Pending approvals

`AgentApprovalGate.kt:23–39` had an indefinite wait. `PersistentProcessService.kt:150–201` directed dangerous approvals to the app and let other requests be granted from a notification that omitted the full operation detail.

Pending approvals stay visible from Rivet's app-wide entry point. The run notification opens the review. The dialog displays the current token's operation and worker, when applicable. Granting checks that the request still owns the token, that the workspace is current and that current destructive-target details match the review. Existing file mutation guards still validate the actual target before execution. Requests expire after two minutes, including time spent in the queue. The loop returns `approval_expired` to the model and withholds the action. The full YOLO policy still bypasses approvals.

**JiTTests.** `expiredApprovalReachesTheModelAndCannotExecute`, `approvalChecksLiveDetailsBeforeGranting` and `pendingApprovalOpensTheReviewFromTheRunNotification`. The live-grant API and the notification's review action first appear here. The expiry test uses virtual time. Its covered operation is a project shell command.

## 4. Command ownership

`terminal-emulator/src/main/jni/command.c:63–82` forked a shell without an owner check. `ManagedProcesses.kt:53–57` tracked it in memory. The terminal PTY had a separate native fork, and the Python bootstrap used `ProcessBuilder`.

Rivet-owned foreground, background, PTY and bootstrap commands now run below a native process monitor. It observes the app PID via the parent relationship and ends the command group when ownership ends. Linux subreaper semantics also let it kill and reap descendants that created another session. Startup checks for old orphaned monitors with PID identity protection where the kernel supports it. The monitor's own check covers devices without that startup mechanism. [Linux subreaper semantics](https://www.man7.org/linux/man-pages/man2/PR_SET_CHILD_SUBREAPER.2const.html)

**JiTTest.** `terminal-emulator/src/test/native/command-owner-death.py`. Parent red after the test killed the owner with SIGKILL and the command tree remained live past three seconds. Branch green under the same Linux host test, including a detached child. Both JNI sources compiled against NDK 27 for ARM64, ARMv7, x86-64 and x86. The host test substitutes the host shell path. Android process-death and PTY behavior still need device verification. The optional Termux integration launches commands under another app's UID and remains outside Rivet's process supervision. The startup sweep identifies monitors from this implementation. It cannot reliably attribute or kill unmarked children left by older versions.

## 5. Spend limits

`SpendGuard.kt:35–41,60–61,79–82` and `SpendStore.kt:16–21` left fresh settings empty. A model with no price entry or user-supplied price could not reach a USD limit.

Fresh settings now enable **$1 or 500,000 tokens per run** and **$15 or 5,000,000 tokens per month**. Users can edit them in **Settings → Agent → Spend guard**. New explicit opt-outs persist. Legacy preferences with individually saved limits retain those choices. An unpriced cloud model shows a warning beside the chat composer and uses the applicable token limits. An enabled USD limit supplies its default token limit if the corresponding token field is blank. The coordinator and workers check accumulated spend before a subsequent request, and project-run totals are restored on resume after process death.

**JiTTests.** `freshCapsAreOnAndAnExplicitOptOutSurvives` and `unpricedModelWarnsAndAnEnabledUsdCapFallsBackToTokens`. The warning beside the composer is a new surface. These are usage-based pause thresholds. An in-flight response or concurrent in-flight workers can exceed a threshold before usage is known. Phone-only chats do not persist per-run spend across a process death. Provider billing and the separately issued auto-title request are outside this guard. Older preferences that stored no limits have no record distinguishing an explicit all-blank save from an untouched install.

## 6. Worker shell scope

`FileScopeRegistry.kt:85–135` enforced scope through explicit paths. `WorkerRunner.kt:348–375` and `RuntimeController.kt:166,642` could launch worker commands with Rivet's full filesystem privileges.

Worker foreground and background shell calls now fail closed with `worker_shell_scope_unavailable` before launch. The worker runner, executor and checkpoint scope gate enforce that rule. Worker file tools continue to enforce the declared scope. The coordinator uses the existing shell path.

**JiTTests.** `workerShellCannotWriteOutsideItsScopeButCoordinatorCan` and `workerCannotRunTheShellThroughACustomTool`. The executor test uses the same shell command and real file for both roles. **Partial.** In-scope worker shell execution is disabled too. The available filesystem policy does not cover all metadata operations, varies by kernel and can depend on inherited descriptors. Providing arbitrary worker shells with enforceable scoped writes requires stronger process isolation than this round allows. [Landlock kernel documentation](https://kernel.org/doc/html/v6.12/userspace-api/landlock.html)

## Verification

Parent and branch results are recorded above. Full diff reviewed against `6f9f78ad`. No merge, PR, release or APK.
