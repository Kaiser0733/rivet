# parallel-build-1 gate-fix

Base: `batch/surface-fix` @ `0729dabc`.

## Source-check failures

The parallel-sessions phase-1 refactor moved the same behaviour behind the `SessionRun.owner` seam. I checked the production paths before changing each assertion.

1. **Compaction start + scroll** · `f1d09b8d`
   `ChatScreen` still keys the follow effect on `state.notice/showBrowserCard/state.compacting/totalItems/acceptedMessageCount`, and still scrolls to the last row. `ChatViewModel` now starts the row through `onCompactionStarted` and clears it in a `finally` guarded by `activeRun === owner`. The old `_uiState`/`ticket` check was updated to that refactored shape.

2. **Empty draft reuse** · `be3708e0`
   `newSession()` still passes `reuseEmpty`, now scoped to `activeRun == null && selectedState.value.goal == null`. The check was updated to the session-scoped expression, not weakened.

3. **Manual compaction preparation** · `8515620b`
   Manual compaction still calls `context.prepare(..., force = true, reason = "manual")` and still reuses `ContextPreparation`; the transcript is now `owner.activeMessages`. Assertion updated to that shape.

4. **Manual compaction edge outcomes** · `55beab0b`
   The no-op branch still returns the same “Nothing to compact yet.” notice, and the cancelled/error copy is unchanged. Only the comparison moved to `compacted == owner.activeMessages`.

All four are source-shape updates, not production regressions. `python3 -m unittest discover -s scripts -p 'test_*.py'` → **97/97 pass** after the four commits.

## Named Gradle failures from the author's local run

5. **`AgentLoopTest.afterMutationRunsOnSuccessFailureAndCancellationOnlyAfterNullBeforeMutation`** · no change
   Reproduced after a clean branch compile: passes. Ran 3× and in the full `AgentLoopTest` class: **67/67**. The branch test now asserts the cleanup sees cancellation (`currentCoroutineContext().isActive == false`), and the branch's `AgentLoop` no longer wraps `afterMutation` in `NonCancellable`. The author's failure was not reproducible; likely stale built classes from before the branch production compile.

6. **`AgentLoopTest.cancelledCommandSyncStopsBeforeTheNextModelRequest`** · no change
   Passes 3× and in the full class. It exercises the branch's new `TurnRunContext`/command-sync cancellation path (`requests == 1`, original cancellation preserved). No production change needed.

7. **`ProjectDownloaderTest.streamingLimitCatchesMissingOrFalseContentLength`** · no change
   Passes standalone and inside the full downloader class (the named case was green; other downloader cases in my sandbox fail with MockWebServer transport errors). `ProjectDownloader.kt` and `ProjectDownloaderTest.kt` are byte-identical between `0729dabc` and this branch, and base was gate-green, so this is a local harness/order effect, not a branch regression.

## Honest gaps

- The full Gradle `testDebugUnitTest` task cannot run in this sandbox; the Gradle failures above were checked with a local JUnit/Robolectric classpath. CI is the real gate.
- `ProjectDownloaderTest`'s other tests hit a local MockWebServer transport problem in this harness; the named streaming-limit test itself passes.
- No production files were changed for the three Gradle failures.

## Title lifecycle regression (Hermes round, 2026-11-25)

**Root cause.** The regression is the interaction between `73f21ded`'s
"immediate stop" change and the new app-scoped run registry; it is not an
incorrect title expectation.

- `73f21ded` made `ChatViewModel.stopRun` publish `streaming = false`
  immediately, then call `owner.job?.cancel()`. The run remains owned by
  `runRegistry.active` until `launchRun`'s `finally` calls
  `runRegistry.finish`.
- `ChatViewModel.send` now gates on `canStartRun`, which rejects whenever
  `runRegistry.active` is non-null. The test observes the first
  `!streaming` emission (published before the job finishes cancellation),
  sends `"Try again"`, and the send returns without appending or starting a
  turn. The test then times out waiting for `acceptedMessageCount == 2`.
- On base, `send` gated only on `_uiState.streaming`; the cancellation
  handler set that false only after cleanup, so the observed `!streaming`
  meant the old job was no longer active. The title marker was therefore
  already cleared before the second send; the second run completed without a
  title request.

The failing path starts no title request at all (`calls` stayed at 1); the
timeout is the dropped second send. The existing test's expectation is
correct, so it was not changed.

**Fix.** Commit `c01b9add` (`ChatViewModel.kt`). When the selected run has
`userCancelled` set but still owns the registry slot, `send` now waits for
that run's job to finish and then re-enters `send`. This keeps cancellation
cleanup (including `clearAutoTitle`) ordered before the next turn instead of
starting a second run while the first is still unwinding. The immediate
stopped UI is unchanged.

**Proof.**

- Base `0729dabc`, scratch worktree, named test:
  `run=1 fail=0`.
- Branch head `f3e2e173` before the fix, named test:
  `run=1 fail=1`, `kotlinx.coroutines.TimeoutCancellationException`.
- Branch with `c01b9add`, named test:
  `run=1 fail=0`.

**Surrounding tests.** All below were run with the local JUnit/Robolectric
harness because the Gradle unit-test configuration cannot resolve its test
dependencies offline.

- Full `ConversationTitleLifecycleTest`: **9/11 passed**. The two failures
  (`workspaceGuidanceNeverEntersTheActualTitleRequest` and
  `cancelledPendingToolTurnNeverGeneratesTitleOrExecutesMutation`) are
  timeouts that reproduce identically on base `0729dabc` in this harness, so
  they are harness failures, not this fix.
- `ConversationTitleTest` + `ConversationTitleWireTest`: **6/6 passed**.
- `StoredTurnDurationTest` + `WorkerPromotionTest`: **5/5 passed**.
- Targeted `ChatViewModelTest` stop/cancel cases:
  `stopWhilePreparingKeepsTheAcceptedMessageAndStartsNoSecondTurn`,
  `stopClearsStreamingWhileProviderCleanupIsStillHeld`, and
  `acceptanceHappensBeforeAProviderLoadThatNeverReturns`: **3/3 passed**.
- Full `ChatViewModelTest`: **57/72 passed** in this harness. The 15 timeouts
  include undo/checkpoint/approval setup failures; base has 14 timeouts in
  69 tests in the same harness. The branch adds three session tests, and the
  targeted stop tests are green. CI remains the authoritative full-class run.
- `:app:compileDebugKotlin` — **BUILD SUCCESSFUL** after the fix.

No merge, PR, tag, release, APK, or version change was made. The fix and this
report are pushed to `origin/parallel-build-1`.
