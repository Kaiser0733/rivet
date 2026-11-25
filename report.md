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
