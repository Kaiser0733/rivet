# Full audit — stability-v1.1.0 (`c0c49c8..HEAD`, HEAD 1b57389)

Ordered by kaiser (2026-11-01 night). Method: four independent read-only passes
(turn/session state, provider transport, persistence/compaction, Compose UI + copy)
plus my own verification of every finding before acting. Each claim below carries
`file:line`; PROVEN means the mechanism follows from the code alone, SUSPECTED means
it needs a runtime observation, which is named.

**Execution limit, stated once:** no JDK, no Android SDK, and the sandbox refuses JVM
sockets, so nothing here was compiled or run locally. Tests written in this round are
reasoned against the code and executed first by CI. Where a verdict would need a run,
it is marked SUSPECTED.

## Findings

Severity: ★★★ user-visible damage on a normal path · ★★ wrong behaviour, workaround exists · ★ rarely hit.

| # | Sev | Finding | Evidence | Verdict |
|---|---|---|---|---|
| 1 | ★★★ | **The composer can latch dead.** `canSend` omits `!streaming`, while the keyboard Send action added on this branch routes through `submit()`, which sets `pendingText` for a send the view model silently refuses. The cleanup effect only clears it on a *newer* accepted message or on `error`/`notice`; a successful turn sets neither, so the send arrow stays disabled for the rest of the process, surviving rotation and conversation switches. | `ChatScreen.kt:1284` (no `!streaming`), `:1286-1293` (`submit`), `:1309-1313` (IME Send), `:1276-1283` (cleanup), `ChatViewModel.kt:377-382` (silent refusal). Pre-branch only the IconButton could send (`git show c0c49c8:…ChatScreen.kt`). | **PROVEN** |
| 2 | ★★★ | **The progress watchdog kills a stream that resumes.** The 120 s deadline is evaluated *before* the `data:` branch, so the first line after a longer gap throws even when it is a real payload — the token is discarded and the turn dies. Heartbeat/comment lines never refresh it, so a thinking model that keeps the connection warm is killed at 120 s, while a silent socket is killed at 300 s by the socket timeout. Duplicated in `responsesSse`. | `Http.kt:315-318` (check) vs `:323-332` (refresh), `:48` (budget), `ChatGptPlanClient.kt:360-363`. Plan existed in `round4-diagnosis.md` ("fix now") but no arming/order change shipped (`git blame` = `a31f712`). | **PROVEN** |
| 3 | ★★ | **The compaction trigger is unreachable above ~200 K tokens, so long turns still dead-stop at the 512 KB projection cap.** The trigger needs `compactAtTokens` of 215 376 for the default 256 K window, but a 512 KB projection can never estimate above ≈179 K, so `save()` throws `AgentSessionLimitException` first and the crossing tool result is discarded. | `ContextBudget.kt:69-75`, `ContextPreparation.kt:85`, `AgentSessionCodec.kt:20` (512 KB), `CodingSessions.kt:127-129` (throw), `ChatViewModel.kt:884` + `:1390` (copy). Arithmetic: 512×1024/3 ≈ 174 762 + ≈4 K fixed. | **PROVEN** |
| 4 | ★★ | **A request that fits is refused.** In `[compactAtTokens, allowedInputTokens)` with nothing left to compact (no tool pair, no room for a summary), `prepare` throws `context_headroom_unavailable` before the request is sent, so the turn ends with "compacted as far as it could". Pre-branch the guard was `needsReduction` (the ceiling), so such a request was simply sent. | `ContextPreparation.kt:85`, `:110-160`, `:180-184`; `AgentLoop.kt:105-113`; `ChatViewModel.kt:1081`. | **PROVEN** |
| 5 | ★★ | **After scrolling up, sending leaves your own message and the reply off-screen.** `followBottom` is never re-armed when a message is accepted; this branch changed it to `rememberSaveable`, so the stale flag now survives rotation too. The draft clears, so it reads as "the send was lost". | `ChatScreen.kt:744`, `:753-758` (only writer), `:759-769`, `:1278` (draft cleared). | **PROVEN** |
| 6 | ★★ | **A 404 whose body merely contains "model" becomes `ModelNotFound`, and the sentence becomes the model name.** A base URL missing `/v1` yields `Model "Invalid URL (GET /models)" was not found by this provider.` plus the Open-Settings action, walking the user to a model picker for a URL problem. | `Http.kt:153-154` (`detail.contains("model")` for 404), `:119-120`, `:168-171` (`take(60)` fallback). | **PROVEN** |
| 7 | ★ | **A per-minute 429 is reported as a billing problem.** `"quota exceeded"` matches `isUsageLimit` before `isResourceExhausted`, and `errorBodyCode` never reads Google's `error.status` (`RESOURCE_EXHAUSTED`), so "wait a minute" is reported as "check your billing". | `Http.kt:112-113`, `:136-139`, `:98-103`. | **PROVEN** |
| 8 | ★ | **Division by zero in `prefixUtf8`** when the retained prefix is entirely lone high surrogates: `end * maxBytes / size` with `size == 0`. Reached from `boundedPriorState` on every summary attempt, so such a session can never compact again. | `ContextPreparation.kt:272-281`, `:252-259`. | **PROVEN** |
| 9 | ★ | **Manual max output ≥ context window zeroes the input budget.** `reserve = min(window, maxOutput)` → `allowedInputTokens = 0` → every send throws `ContextCapacityTooSmall` and the copy blames the window. The editor does not cross-validate the two fields. | `ContextBudget.kt:61-69`, `ContextPreparation.kt:82`, `ChatViewModel.kt:1082`, `ProvidersViewModel.kt:525-539`. | **PROVEN** |
| 10 | ★ | **Copy law:** the reasoning label was renamed "Default" → "Off", but the app only *omits* the parameter, so the provider keeps its own default effort — "Off" describes something the code does not do. `ProviderError.kt:47` still tells users to "set reasoning to Default", a label that no longer exists. "XHigh"/"Ultra" are not plain words and the ladder's order is ambiguous. | `strings.xml:39`, `:43`, `:45`; `ProviderError.kt:47`; `OpenAiCompatibleClient.kt:224`, `:88-90`. | **PROVEN** |
| 11 | ★ | **Dead API / dead copy introduced on this branch:** `PreviewWindow.reset()` (only its own test calls it); the `commandBusyReason` note in `ChangeSummary` cannot render (`undoCheckpointId` is null while a turn runs); `HistoryScreen`'s `actionsEnabled` default is never used. | `PreviewWindow.kt:20-23`; `ChatScreen.kt:1196-1199` vs `:586`, `ChatViewModel.kt:613-617`, `:947-963`; `HistoryScreen.kt:166`. | **PROVEN** |
| 12 | ★ | **Four orphaned string resources** (`chatgpt_models_*`): their lookups were replaced with Kotlin literals on this branch, so future copy edits in `strings.xml` silently do not take effect. Plus a live typo: "could't" in two strings. | `strings.xml:76-77`, `:101-104`; `ProvidersViewModel.kt:243`, `:260`, `:386`, `:392`, `:404`, `:406`, `:407`. | **PROVEN** |
| 13 | ★ | **Provider error classes drop the body.** 401/403/5xx discard `detail`, so Gemini's "API not enabled" 403 and OpenAI's region 403 are reported as "check your API key", and a 5xx body is lost. | `Http.kt:109-110`, `:115`. | **PROVEN** (not fixed: needs a banner/action change) |
| 14 | ★ | **A 200 that is not SSE is read as an empty stream** and reported as a truncation; the JSON completion or the error body is discarded. No `Content-Type` is read anywhere on the streaming path. | `Http.kt:301-302`, `:323`; all four clients' "incomplete stream". | **PROVEN** (not fixed: medium change) |
| 15 | ★ | **The error-body extractor understands two shapes only** (`{"error":{"message"}}`, `{"message"}`), so `{"error":"…"}`, `{"detail":…}` and non-JSON bodies fall back to "HTTP $code" and a generic sentence. | `Http.kt:87-96`. | **PROVEN** (not fixed: ~10-line extractor) |
| 16 | ★ | **The effort classifier can replace a real provider message.** A generic 400 that says "invalid request" and mentions the model is read as an effort refusal, and `ChatViewModel.kt:924-931` then throws the provider's words away and shows the ladder. | `Http.kt:201-204`, `:207-223`; `ChatViewModel.kt:924-931`. | **PROVEN** (not fixed: banner policy is a product call) |
| 17 | ★ | **Surrogate split in the 300-char banner cap** (`take(300)` can keep a lone high surrogate), inconsistent with `Http.kt:82` which guards the same hazard. | `ProviderMessageText.kt:15-16`. | **PROVEN** |
| 18 | ★ | **Restart divergence around an interrupted tool call.** `load/select` inserts a synthetic "interrupted" result into `events` but only into `active_events` when it fits; otherwise the last active event is dropped and nothing tells the model. The UI then shows a tool result the model never saw. | `CodingSessions.kt:558-600`, esp. `:576-583`; `recent()` at `:294-312`. | **PROVEN** (not fixed: persistence change) |
| 19 | ★ | **Drafts are not keyed to the conversation** (`InputBar` sits outside `key(state.currentSessionId)`), so a draft typed in one conversation can be sent to another. | `ChatScreen.kt:576-590`, `:1273`. | SUSPECTED → settle by typing in A, switching to B |
| 20 | ★ | **Message-index-keyed UI state** (`remember(messageKey)`, `rememberSaveable(id)`) can re-associate after the 100-message trim shifts indices. | `ChatScreen.kt:959-960`, `:1016`, `:789`; trim at `ChatViewModel.kt:806-818`. | SUSPECTED → settle with a >100-message conversation |
| 21 | ★ | **`LiveStatusRow`'s saveable clock has no turn key**; restarting it per turn relies on LazyColumn dropping the item's saveable state. | `ChatScreen.kt:864-878`. | SUSPECTED → watch whether turn 2 starts above 0 s |
| 22 | ★ | **`capacitySource` is computed but never gates the planning target**, so providers with no detected window are planned against 262 144 tokens. | `ContextBudget.kt:20`, `:61`. | PROVEN, flagged — see below |

### Turn lifecycle / session state (fourth pass)

| # | Sev | Finding | Evidence | Verdict |
|---|---|---|---|---|
| 23 | ★★★ | **Retry sent the prompt twice.** `reuseExistingTail` only reused the failed tail when it ended in a user or tool message; a failure after partial output leaves an assistant message (the cut-off answer is appended before rethrow), so the retry appended the user text again — two identical bubbles and a doubled provider request, once more per retry. | `ChatViewModel.kt:570-573` (guard), `AgentLoop.kt:187-193` (partial append), `:919-939` (`stableFailure` → Retry). | **PROVEN** |
| 24 | ★★★ | **A turn writes to the storage layer's global active session, not the session it started in.** `save()` resolves `activeId(db)`; `changeSession` flips that pointer and only then checks the generation. A send in that window writes conversation A's transcript into B while the UI switch is aborted. | `ChatViewModel.kt:553` (capture), `CodingSessions.kt:102-105` (`save` uses `activeId`), `ChatViewModel.kt:1356-1371`; contrast `compact(..., expectedSessionId)` at `CodingSessions.kt:316-330`. | **PROVEN** mechanism (race window) — flagged |
| 25 | ★★ | **Undo for the turn that just ran is thrown away by the queued switch** (registered ~45 lines earlier in the same `finally`, then wiped by `changeSession`'s fresh state), and nothing rehydrates it from the on-disk checkpoint. | `ChatViewModel.kt:951-959` vs `:1003-1008`, `:1371-1378`; `TurnCheckpoint.kt:107-113`. | **PROVEN** — flagged |
| 26 | ★★ | **Retries ignored an Undo in flight** (the sibling actions check it), and `stableFailure` left `commandRunning` set so the new error row showed "a command is running" and disabled its own Retry button. | `ChatViewModel.kt:410-424` (guards), `:1199-1205` (no `commandRunning`/`toolLifecycle` reset). | **PROVEN** |
| 27 | ★ | **`retryInterruptedTurn`'s guard was dead** (`&&` where the sibling uses `||`): a compacted transcript with no user message could persist and send an empty prompt. | `ChatViewModel.kt:411` vs `:422`. | **PROVEN** |
| 28 | ★ | **`selectProject` is not generation-gated**, so the finished turn's "couldn't prepare Undo" error can land in the newly selected project, and its half-made checkpoint occupies a retention slot. | `ChatViewModel.kt:332-359`, `:964-967`, `ChatScreen.kt:406-409`. | **PROVEN** mechanism — flagged |
| 29 | ★ | Dead state: `ChatUiState.contextEstimate` is written every turn and read by nothing; `clearChat()` has no production caller. | `ChatViewModel.kt:100`, `:870-876`, `:1277-1296`. | PROVEN — flagged |

## Cross-check: findings the branch claims to have fixed

Verified at HEAD, all genuinely fixed: **F01** progress deadline exists (`Http.kt:48`, `:315`); **F02** all four clients now raise `IncompleteGeneration`, never `InvalidResponse("incomplete stream")` (`AnthropicClient.kt:322`, `GeminiClient.kt:250`, `OpenAiCompatibleClient.kt:201`, `ChatGptPlanClient.kt:294`); **F03/F04** caps raised to 16 MiB with a retained-model cap (`Http.kt:49-52`); **F05** partial text is persisted before any throw (`AgentLoop.kt:189-195`, pinned by `AgentLoopTest.providerErrorMidStreamPersistsPartialTextBeforeRethrowing`); **F06** a length-cut tool call keeps the turn (`AgentLoop.kt:143-180`); **F08/F09** tool actions are derived from `WorkspaceFailure.Reason` so a new code cannot reach the model bare (`AgentToolError.kt:17-19`); **F28/F29** write paths use `decodeOrThrow` and the unreadable store is quarantined before any rewrite (`ProviderStore.kt:63-67`, `:107-113`).

## The four open items from round 4

- **`MAX_ERROR_BODY_BYTES` unit mismatch — cleared, not a bug.** 64 KiB are read, then `take(64 KiB / 4)` = 16 384 characters are kept; 16 384 × 4 bytes = 65 536 = exactly the byte budget in the worst case (`Http.kt:53`, `:75-82`). The two caps coincide by construction.
- **`capacitySource` not gating the target — flagged, not fixed.** Making it gate would shrink the allowed input for every provider with no detected window; that is a product decision with broad blast radius, not a small fix. Cost: one field in `ContextAssessment` plus a policy for unknown capacity.
- **`PreviewWindow.reset()` dead — fixed (item 11).**
- **Title dead-end after one failed request — flagged, not fixed.** `runs once` is the documented product rule (rivet skill §12H), and today's code matches it: a failed title request clears the marker (`ChatViewModel.kt:1055`, `CodingSessions.kt:245-247`), so the session keeps "New session". The asymmetry with blocked/retried turns (`:557`) is real; changing it re-introduces per-turn title calls on a failing provider. Kaiser's call.

## Checked and clean

Approval gate (`AgentApprovalGate`: `cancel()` completes the deferred, no leak); SSE double-resumption impossible (`terminal.compareAndSet` everywhere); `readUtf8LineStrict` oversized-line guard is sound against okio 3.6.0; JSON helpers are all `as?`-safe; `onEvent` throwing is mapped to `InvalidResponse` (`Http.kt:342-346`); compaction `compact()` is one transaction that re-checks the expected projection (`CodingSessions.kt:327-331`); the newest user message survives all five commit paths; tool call/result pairs are never split; `MessageList`'s `totalItems` arithmetic matches the items emitted; no banned copy word anywhere; the queued session switch cannot be observed to drop in practice (the window is sub-frame and a second tap re-queues).

## What I fixed in this round (one commit each)

| Commit | Fixes |
|---|---|
| `ae0ad4c` | #1 composer latch |
| `9148f88` | #2 watchdog killing a resumed stream (both readers) + test |
| `79af4f6` | #5 follow-bottom re-armed on send |
| `dc5235a` | #4 a request that fits is sent + test |
| `21b5a44` | #3 compaction fires on projection pressure + test |
| `745559b` | #6 404 is not a missing model + test |
| `8da4e57` | #7 per-minute quota is not billing + test |
| `7f03466` | #8 division by zero in the summary clip |
| `aa9011f` | #9 manual output limit at the window + test |
| `f0bb78b` | #10 "Off" → "Provider default"; stale reference |
| `bbc22c8` | #23 retry no longer duplicates the prompt + test |
| `ae751dc` | #26 stale command flag + retries held during Undo |
| `61603f3` | #27 retry without a prompt |

## Flagged, not fixed (with cost)

- **#24** turn writes into the global active session. Fix needs an `expectedSessionId`
  on the persistence `save` contract (every implementation and test fake changes) or a
  synchronous `switching` flag honoured by `send()`. Data-integrity class: worth doing
  deliberately, not at the end of an audit.
- **#25** Undo state dropped by a session switch. Fix: carry `undoCheckpointId`,
  `lastTurnFiles`, `lastTurnFileCount` through `changeSession` when the workspace is
  unchanged, or rehydrate from `runtime.latestCheckpoint()` on every switch. ~1 hour
  plus tests; the workspace-scoping rule needs care.
- **#28** `selectProject` generation gate: one line, but it changes when a project
  switch invalidates a turn, so it wants a test harness for overlapping switches.
- **#13/#14/#15/#16** provider bodies dropped for 401/403/5xx, non-SSE 200s, unfamiliar
  error shapes, and the effort classifier replacing a real provider message. Each is a
  banner-policy change; #16 in particular is a product decision (does the ladder win
  over the provider's own sentence?).
- **#22** `capacitySource` not gating the planning target: a policy for unknown
  capacity, not a patch.
- **#19/#20/#21** the three SUSPECTED UI items, each with the observation that settles it.
- **#29** dead `contextEstimate` write per turn (also a small perf cost) and `clearChat`
  with no caller.
