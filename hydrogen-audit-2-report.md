# Rivet Hydrogen Audit 2 — Findings & Fix Log

- **Repo**: `/home/hatch/workspace/rivet-hydrogen-audit`
- **Branch**: `audit/hydrogen-2` (base commit `2f04f6de`, `versionName = 1.5.0`, `versionCode = 35`)
- **Audit Scope**: Full repository review with deep pass on `subagent/`, P2 shared-run primitives, P3 `ChatViewModel` engine wiring, P4 subagent UI/settings, v13 browser paint-fix paths, plus `agent/`, `runtime/`, `local/`, `provider/`, `storage/`, `workspace/`, `ui/`, `cpp/`, and `terminal-emulator/`. (`updates/` excluded per brief hard limit.)
- **Environment / Verification Constraint**: Local machine has no JDK (`java`/`javac`) or `kotlinc` on `PATH` and `JAVA_HOME` is unset, so `./gradlew` / `/home/hatch/gradle-8.13/bin/gradle` cannot start here. All fixes and their **JiTTests** are carefully verified by static/code review and documented with CI gate requirements below.

---

## Severity-Ordered Findings

### Blocker

#### A1 — `BrowserSession.samplePaint` takes the post-scroll screenshot after scrolling back to `startY`, causing false-positive `PaintVerdict.Stale` on every healthy scrollable page
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/browser/BrowserSession.kt` L689-720 (`samplePaint`), L778-799 (`recoverStalePaint`)
- **Why It Is a Bug**:
  In `samplePaint`, Rivet captures `firstShot` at `startY` (L682), calls `window.scrollBy(0,1)` (L693), delays 80ms, records `scrolledY` (`startY + 1`, L695), and then immediately executes `window.scrollBy(0,-1)` and delays 80ms (L696-697) **before** calling `captureScreenshot(view)` for `secondShot` (L698). It then passes `FrameProbe(startY, firstHash)` and `FrameProbe(scrolledY, secondHash)` to `PaintStaleness.isStale(...)` (L718-719). On any healthy page where `window.scrollBy(0, 1)` actually scrolls (`scrolledY != startY`), scrolling back `-1` restores the viewport to `startY`, so `secondHash == firstHash`! `PaintStaleness.isStale` sees `after.scrollY != before.scrollY && before.hash == after.hash` and returns `true` (`PaintVerdict.Stale`). Worse, `recoverStalePaint` (L788) calls `samplePaint(url)` a second time after `onResume()`/`invalidate()`, hits the same bug, and immediately triggers `requestRendererTeardown()`.
- **Concrete Failure Scenario**:
  User or agent opens any scrollable webpage (or local preview). Within 1.5s of `onPageFinished`, `armPaintWatchdog` runs `samplePaint(url)`. Because scrolling down 1px and back 1px produces `scrolledY = startY + 1` with identical screenshots at `startY`, `samplePaint` falsely classifies a live, painted page as `PaintVerdict.Stale`, shows `"The page stopped updating — Rivet recreated the browser view."`, and destroys/recreates the WebView.

#### A2 — `ChatViewModel.openWorker` invokes `WorkerSessionStore.loadTranscript` on a live `RunPhase.Running` worker, mutating SQLite event history and crashing the active worker
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt` L2403-2422 (`openWorker`)
  - `app/src/main/java/com/kaiser/rivet/subagent/WorkerSessionStore.kt` L148-166 (`loadTranscript`), L168-195 (`saveMessages`)
- **Why It Is a Bug**:
  `ChatViewModel.openWorker(workerId)` calls `workerStore.loadTranscript(workerId)` when the user opens `WorkerSheet` to inspect a worker. Inside `WorkerSessionStore.loadTranscript`, `val interrupted = InterruptedTurnRecovery.hasPendingToolCall(active.lastOrNull()) || (worker.interrupted && InterruptedTurnRecovery.hasUnfinishedTurn(active))` (L155-156) triggers `recoverInterruptedLocked(...)` whenever the last persisted message in `worker_active` is an assistant message with pending `toolCalls` — which is true for the entire duration of every tool execution while the worker is still `RunPhase.Running`! `recoverInterruptedLocked` appends a synthetic `"interrupted"` `Tool` error result to both `worker_events` and `worker_active` and flips `interrupted = 1` in SQLite. When the live worker completes its tool call and invokes `saveMessages(workerId, messages, interrupted = false)`, L185 checks `messages[count - 1] != last` against the corrupted `worker_active` tail and throws `IllegalStateException("Worker event prefix changed")`.
- **Concrete Failure Scenario**:
  User taps a running subagent chip to view its transcript while it is executing `run_command` or `read_file`. `openWorker` calls `loadTranscript`, inserts a synthetic `"interrupted"` tool response into SQLite for the still-in-flight tool call, and when the tool finishes, the worker's `AgentLoop` crashes inside `saveMessages` with `IllegalStateException("Worker event prefix changed")`.

---

### High

#### A3 — `SubagentSupervisor.pauseAll` cancels active workers whose `finally` blocks run `drainQueue()`, launching queued workers into a paused run
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/subagent/SubagentSupervisor.kt` L317-323 (`launchWorker` `finally`), L331-381 (`drainQueue`), L507-510 (`pauseAll`)
- **Why It Is a Bug**:
  `pauseAll(reason)` sets `currentPauseReason = reason` and cancels `activeJobs.values`. Cancelled workers enter `launchWorker`'s `finally` block under `withContext(NonCancellable)` and call `drainQueue()` (L321). Neither `drainQueue()` nor `pauseAll()` checks `currentPauseReason != null` or transitions queued workers to `RunPhase.Paused`. As soon as a cancelled active job releases its slot, `drainQueue()` pops the next `QueuedWorker`, marks it `RunPhase.Running`, and launches it despite the active pause.
- **Concrete Failure Scenario**:
  Coordinator spawns 4 workers (2 running at `MAX_RUNNING = 2`, 2 queued). User taps Pause (or a spend/thermal/battery pause fires `pauseAll`). Workers 1 and 2 cancel and transition to `RunPhase.Paused`, and their `finally` -> `drainQueue()` immediately starts queued workers 3 and 4 while the run is paused.

#### A4 — `SubagentSupervisor.drainQueue()` leaves `Admission.Refuse` workers permanently stuck in `RunPhase.Queued`
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/subagent/SubagentSupervisor.kt` L351-378 (`drainQueue`), L456-461 (`beforeCompletionHold`), L600 (`hasLiveWorkers`)
- **Why It Is a Bug**:
  Inside `drainQueue()`, `is Admission.Refuse` (L375-377) is a no-op comment: it neither removes the entry from `queue` nor transitions the `WorkerRecord` out of `RunPhase.Queued` (`isTerminal == false`). Because `hasLiveWorkers()` checks `workers.values.any { !it.isTerminal }`, any queued worker later refused at drain time remains non-terminal forever.
- **Concrete Failure Scenario**:
  A local or model-constrained worker is queued and later hits `Admission.Refuse` during `drainQueue()`. It stays `RunPhase.Queued` permanently, causing `beforeCompletionHold` to hang for the full 10-minute (`600_000L`) timeout and blocking turn Undo / checkpoint restore while `hasLiveWorkers()` stays `true`.

#### A5 — `SubagentSupervisor.executeSpawn` evaluates `workers` and `SubagentScheduler.admit` outside `queueLock`, risking lost wake-ups and slot/scope overcommitment
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/subagent/SubagentSupervisor.kt` L156-242 (`executeSpawn`), L331-381 (`drainQueue`)
- **Why It Is a Bug**:
  `executeSpawn` builds the `live` snapshot from `workers.values`, calls `SubagentScheduler.admit(...)`, and only acquires `queueLock` *after* saving a `Queued` worker (L198). Meanwhile, active workers finishing on `scope` call `drainQueue()` under `queueLock`.
- **Concrete Failure Scenario**:
  Two workers are running (`MAX_RUNNING = 2`). Coordinator calls `executeSpawn` for Worker C, observes 2 running, gets `Admission.Queue`, and prepares to enqueue. Before line 198 acquires `queueLock`, both running workers complete and run `drainQueue()`, seeing an empty `queue` and returning. Line 198 then appends Worker C to `queue` with zero running workers left to ever call `drainQueue()`, stranding Worker C in `Queued` until `beforeCompletionHold` times out after 10 minutes. Conversely, if `executeSpawn` gets `Admission.Start` and `drainQueue()` runs before `updateWorker` (L228), `drainQueue()` can admit extra workers past `MAX_RUNNING` or overlapping file scopes.

#### A6 — `SubagentSupervisor.awaitTerminal` stalls for the full `timeoutMs` (default 300s) when given an unknown/nonexistent worker ID
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/subagent/SubagentSupervisor.kt` L383-437 (`awaitTerminal`)
- **Why It Is a Bug**:
  `awaitTerminal` checks `allFinishedInitially = idsToWatch.all { ... w != null && w.isTerminal }` and then awaits `state.first { ... idsToWatch.all { w != null && w.isTerminal } }` with `withTimeoutOrNull(timeoutMs)` (L391-405). If any requested ID does not exist in memory or in `workerStore`, `w` is always `null`, so `state.first` never satisfies the predicate and blocks until the entire `timeoutMs` (default `300_000L`) expires, after which `workerIds.mapNotNull` silently drops the unknown ID.
- **Concrete Failure Scenario**:
  Coordinator calls `await_subagents(ids=["nonexistent-id"])`. Instead of failing/returning immediately with the unknown ID reported, the tool stalls the turn for 5 minutes.

#### A7 — `FileScopeRegistry` is an unsynchronized `linkedMapOf` accessed concurrently by `SubagentSupervisor` and `RunCheckpointGate`
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/subagent/FileScopeRegistry.kt` L22-65 (`register`, `release`, `clear`, `scopeOf`, `liveScopes`, `checkWrite`)
  - `app/src/main/java/com/kaiser/rivet/subagent/SubagentSupervisor.kt` L231, L319, L362, L545
  - `app/src/main/java/com/kaiser/rivet/runtime/RunCheckpointGate.kt` L212-214, L471
- **Why It Is a Bug**:
  `RunCheckpointGate` wraps `scopeRegistry.checkWrite` and `scopeRegistry.scopeOf` in `synchronized(attributionLock)`, but `SubagentSupervisor` calls `scopeRegistry.register(...)` and `scopeRegistry.release(...)` directly from coordinator/worker coroutines without `attributionLock` and without internal synchronization inside `FileScopeRegistry`. `FileScopeRegistry.scopes` is a plain `linkedMapOf()`.
- **Concrete Failure Scenario**:
  Worker 1 completes on `Dispatchers.Default` and executes `scopeRegistry.release(worker.id)` in `launchWorker`'s `finally` while Worker 2 or the coordinator is concurrently inside `checkWrite` iterating `scopes.values`, throwing `ConcurrentModificationException` or corrupting map state.

#### A8 — `SubagentSupervisor` lifecycle defects in `launchWorker` (`activeJobs` race), `joinAll` (single-snapshot miss), and `relaunchPaused` (skips scheduler/checkpoint guards)
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/subagent/SubagentSupervisor.kt` L245-329 (`launchWorker`), L512-573 (`relaunchPaused`), L604-606 (`joinAll`)
  - `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt` L1950-1953 (`finally` teardown)
- **Why It Is a Bug**:
  1. `launchWorker` assigns `activeJobs[worker.id] = job` (L325) **after** `scope.launch { ... }` returns. If the job finishes or is cancelled before L325 runs (e.g., eager/fast failure or immediate cancellation), `finally` runs `activeJobs.remove(worker.id)` first, and L325 then leaks the completed `Job` into `activeJobs`. Using `CoroutineStart.LAZY`, inserting before `job.start()`, fixes the ordering race.
  2. `joinAll()` (L605) snapshots `activeJobs.values.toList().joinAll()` once. If a finishing worker triggers `drainQueue()` and launches a queued worker while `joinAll()` is awaiting, that newly launched worker is not awaited before `ChatViewModel` calls `supervisorScopeJob?.cancel()` (L1952), or queued workers remain un-terminal in `workers`/SQLite when `activeJobs` is empty.
  3. `relaunchPaused(runId)` (L512-573) promotes every `RunPhase.Paused` worker straight to `RunPhase.Running` without calling `SubagentScheduler.admit` (bypassing `MAX_RUNNING = 2`, `MAX_LOCAL = 1`, and overlap checks) and without calling `services.checkpointGate.beginRunBoundary(...)`, and constructs `ResolvedPoolEntry` with hardcoded `windowTokens = 262_000` / `PriceTier.Unknown` ignoring `services.poolState()`/`providerSource.loadFor()`.
- **Concrete Failure Scenario**:
  Resuming a paused run with 2+ paused workers or local/overlapping workers bypasses scheduler caps and checkpoint boundaries; fast-failing worker leaks `activeJobs` entry; or teardown `joinAll()` misses a queued worker promoted mid-shutdown and cancels it prematurely.

---

### Medium

#### A9 — `SubagentSupervisor.validateSpawn` `retryOf` single-retry guard only checks in-memory `workers` and ignores retries from earlier turns
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/subagent/SubagentSupervisor.kt` L91-109 (`validateSpawn`)
- **Why It Is a Bug**:
  Line 92 looks up the failed target worker in both `workers[brief.retryOf]` and `services.workerStore.loadWorker(brief.retryOf)`, so `retry_of` works across turns. However, line 102 checks `workers.values.any { it.retryOf == brief.retryOf }` only against the current turn's `workers` map, not `services.workerStore.workersForSession(parentSessionId)` (or `hasRetryFor`).
- **Concrete Failure Scenario**:
  Worker `w-1` fails in Turn 1 and is retried (`w-2`, `retryOf = "w-1"`). On Turn 2 (new `SubagentSupervisor` instance), the coordinator spawns `retry_of = "w-1"` again and passes validation, breaking the one-retry-per-failed-worker limit.

#### A10 — `ResultEnvelope.assembleAwaitResult` second pass does not enforce `maxTotalBytes` (24 KiB)
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/subagent/ResultEnvelope.kt` L171-179 (`assembleAwaitResult`), L205-232 (`trimClaimed`)
- **Why It Is a Bug**:
  After stripping/capping diffs in pass 1, if `afterDiffCap` still exceeds `maxTotalBytes` (24 KiB), pass 2 calls `trimClaimed(it, perWorkerClaimBudget)` using a heuristic `- 256` overhead estimate and immediately returns `render(capDiffs(trimmedClaims, remainingDiffBudget))` without checking if the rendered JSON fits in `maxTotalBytes`. If `parsed.size` is large or `verified.files_changed`/`commands`/`timed_out`/`still_running` arrays are large, the output exceeds `maxTotalBytes`.
- **Concrete Failure Scenario**:
  Coordinator calls `await_subagents` on a large batch of workers with many modified files/commands. `assembleAwaitResult` returns an envelope JSON larger than `MAX_TOTAL_BYTES` (24,576 bytes).

#### A11 — `ChatViewModel.continueWorker` drops pre-compaction worker events by importing `active` messages as both `events` and `active`; `WorkerSheet` hides errors and uses non-unique `hashCode()` keys
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt` L2436-2448 (`continueWorker`)
  - `app/src/main/java/com/kaiser/rivet/subagent/WorkerSessionStore.kt` L148-165 (`loadTranscript`), `backend.getAllEvents`
  - `app/src/main/java/com/kaiser/rivet/ui/chat/WorkerSheet.kt` L30-62
  - `app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt` L722-740
- **Why It Is a Bug**:
  `WorkerSessionStore` separates `worker_events` (full history) from `worker_active` (compacted view), but `WorkerTranscript` only carries `active` messages, so `continueWorker` passes `events = transcript.messages, active = transcript.messages` to `store.importSession(...)`, dropping all pre-compaction history when a compacted worker is promoted. Additionally, `WorkerSheet` uses `items(filtered) { message -> MessageRow(message, messageKey = message.hashCode()) }` without stable unique keys, and neither receives nor renders `workerSheet.error` when `openWorker` fails.
- **Concrete Failure Scenario**:
  1. A long worker compacts mid-run, completes, and the user taps **Continue**: the promoted conversation's `events` table lacks pre-compaction turns.
  2. Opening a worker whose transcript fails to load clears `loading` to `false` and shows a blank sheet with no error message (`workerSheet.error` is ignored in `ChatScreen.kt`).

#### A12 — `SpendStore` loses sub-microdollar spend increments via per-call `"%.6f"` rounding and drops `0` in `long()`
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/storage/SpendStore.kt` L50-68 (`add`, `long`, `Double.text`)
- **Why It Is a Bug**:
  `SpendStore.add(costUsd, tokens)` formats `(oldCost + costUsd).text()` with `String.format(Locale.US, "%.6f", this)` on every single API response. Small/short calls on inexpensive models (e.g. `gemini-2.0-flash` or `gpt-4o-mini`, costing `< $0.0000005` per call) round down to `"0.000000"`, so repeated tiny requests never accumulate into `MONTH_USD`. (`long()` also uses `.takeIf { it > 0 }` instead of `>= 0`.)
- **Concrete Failure Scenario**:
  Hundreds of short requests costing ~$0.0000004 each result in `MONTH_USD` remaining `0.000000` instead of accumulating toward the user's monthly spend cap.

---

### Low

#### A13 — `ProvidersViewModel.updateSubagentPool` swallows save errors and can race with `subagentStore.state` collection
- **Files & Lines**:
  - `app/src/main/java/com/kaiser/rivet/ui/provider/ProvidersViewModel.kt` L185-197 (`updateSubagentPool`)
- **Why It Is a Bug**:
  `updateSubagentPool` reads `_subagentPoolState.value` outside a lock/atomic loop and launches an unserialized `viewModelScope.launch { subagentStore.save(next) }` that silently swallows any persistence exception while leaving the UI showing the unsaved state until the next external DataStore emission reverts it.
- **Concrete Failure Scenario**:
  Rapid toggles in Subagents Settings or a DataStore write failure silently drop the user's pool selection change with no error surfaced and out-of-order coroutine writes.

---

## Fix Progress Log

- **A1** — Commit `ad6da21e`: Fixed `BrowserSession.samplePaint` so `secondShot` is captured at `scrolledY` while scrolled (`finally` restores the scroll delta cleanly), and added paint JS/screenshot injection seams for deterministic testing + JiTTest `BrowserSessionPaintSamplingTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A2** — Commit `b0c428ba`: Added read-only `WorkerSessionStore.inspectTranscript(workerId)`, switched `ChatViewModel.openWorker` to `inspectTranscript`, and guarded `ChatViewModel.continueWorker` with `!current.isTerminal` before calling `loadTranscript` + JiTTests in `WorkerSessionStoreTest` and `WorkerPromotionTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A3** — Commit `a1140400`: Guarded `SubagentSupervisor.drainQueue()` on `currentPauseReason == null`, transitioned queued workers to `RunPhase.Paused` inside `pauseQueuedWorkers(reason)` from `pauseAll(reason)`, and deduplicated `queue` entries in `relaunchPaused` + JiTTest in `SubagentSupervisorTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A4** — Commit `bbc63a6b`: Handled `Admission.Refuse` inside `SubagentSupervisor.drainQueue()` by removing the queued entry, building a `spawn_refused` failure envelope, and persisting `RunPhase.Failed` + JiTTest in `SubagentSupervisorTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A5** — Commit `4048d8dd`: Wrapped `SubagentSupervisor.executeSpawn` (`configs` lookup, live snapshot, `SubagentScheduler.admit`, pause guard, worker persistence, enqueue/launch) under `queueLock` so `executeSpawn` and `drainQueue()` serialize cleanly + JiTTest `concurrentExecuteSpawnUnderQueueLockNeverExceedsMaxRunningSlots` in `SubagentSupervisorTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A6** — Commit `75e59c0a`: Partitioned `SubagentSupervisor.awaitTerminal` `workerIds` into known vs unknown IDs, awaited only known workers, and surfaced `unknown_workers` via `ResultEnvelope.assembleAwaitResult` + JiTTest in `SubagentSupervisorTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A7** — Commit `bba09ea2`: Synchronized all reads and mutations of `FileScopeRegistry.scopes` with an internal `lock` + multi-threaded stress JiTTest in `FileScopeRegistryTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A8** — Commit `8d671b04`: Used `CoroutineStart.LAZY` + `activeJobs.remove(id, job)` on completion in `launchWorker`, converted `joinAll()` to drain queued continuations across iterations, and routed `relaunchPaused` through `SubagentPool.resolve`/`resolvedEntry` + `queue` + `drainQueue()` + JiTTest in `SubagentSupervisorTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A9** — Commit `7728da07`: Extended `SubagentSupervisor.validateSpawn` `retry_of` duplicate check across persisted `workersForSession`/`workersForRun` records + JiTTest `retryOfRejectsSecondRetryAcrossSeparateTurnSupervisors` in `SubagentSupervisorTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A10** — Commit `550cfd69`: Enforced `maxTotalBytes` across `ResultEnvelope.assembleAwaitResult` pass-2 claim trimming (`truncateJsonEncodedPrefix`), pass-3 verified array stripping (`commands`, `out_of_scope_changes`, `files_changed`), and final tail-trimming fallback + JiTTest in `ResultEnvelopeTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A11** — Commit `a8302c37`: Exposed full `events` in `WorkerTranscript` so `ChatViewModel.continueWorker` preserves pre-compaction turns in `CodingSessions.importSession`, updated `WorkerSheet` with stable positional keys + `error` rendering, and wired `workerSheet.error` from `ChatScreen` + JiTTest in `WorkerPromotionTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A12** — Commit `306fdd88`: Replaced `String.format("%.6f")` truncation in `SpendStore` with lossless finite `Double.toString()` persistence and added `nonNegativeLong` for monthly token reads + JiTTest `addAccumulatesSubMicrodollarCostsWithoutPrecisionLoss` in `SpendStoreTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
- **A13** — Commit `ba7ab0e5`: Serialized `ProvidersViewModel.updateSubagentPool` writes via an ordered channel consumer, guarded optimistic state against stale store emissions while saves are pending, surfaced `subagentPoolError` on save failures (reverting optimistic state to persisted snapshot), and rendered the error in `SubagentsSettings`/`SettingsScreen` + JiTTest `ProvidersViewModelSubagentPoolTest`. Local Gradle/JDK unavailable (`JAVA_HOME` / `java` / `kotlinc` missing); CI test run required.
