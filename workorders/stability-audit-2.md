# Stability surgery + re-audit — working doc (order: stability-surgery-and-reaudit.md)

Base: `ab8d1f4`. Checklist is the order's phases, in order. One commit per fix.
**This run stopped early by context limit; the STOP POINT section at the bottom says
exactly where to resume.** Nothing half-done was left uncommitted.

## Phase S — structural surgery

### S1 — a turn must never write into the wrong conversation  [ DONE — bb5d149 ]

Diagnosis (verified at `ab8d1f4`):
- The turn captures its session once: `ChatViewModel.kt:563` `val context = ContextPreparation(sessions, sessionId, …)`; every write then goes through `sessionStore.save(...)` (`:592` at turn start, `:790` per message, `stableFailure` at the end).
- `CodingSessions.save` resolves the target from the **DB pointer**, not from the turn: `CodingSessions.kt:102-105` `val id = activeId(db)`.
- `changeSession` flips that pointer (`ChatViewModel.kt:1362`, inside `action(store)`) and only afterwards checks the generation (`:1371`), with several suspends between. A `send()` landing in that window passes every guard (`send` checks only `streaming/ready/recoveringProjectChanges`, `:377-382`) and writes A's transcript into B. `compact` already shows the correct pattern: `CodingSessions.kt:316-330` takes `expectedSessionId` and throws `IllegalStateException("Session changed")`.

Chosen design (smallest that closes the hole, no fake churn):
1. `AgentSessionPersistence.save(messages, interrupted, expectedSessionId: String? = null)` — a **defaulted** parameter, so all 10 implementations and test fakes keep compiling and only `CodingSessions` needs the check.
2. `CodingSessions.save` compares `expectedSessionId` with `activeId(db)` inside the same `onDatabase` block and throws `IllegalStateException("Session changed")` when they differ — the same failure `compact` already raises.
3. `ChatViewModel` passes the captured `sessionId` on the three turn write sites (`:592`, `:790`, and the save inside `stableFailure`).
4. Test: interleave `newSession()`/`resumeSession(other)` with an in-flight turn (blocking provider), then assert `sessions.recent(first)` still holds A's transcript, `recent(other)` is untouched, and the turn surfaced a failure rather than writing to B.

### S2 — Undo must survive a conversation switch  [ DONE — 50f21e1 (carry implemented; restart rehydration still open) ]

Diagnosis (verified):
- The turn registers undo in its inner `finally`: `ChatViewModel.kt:951-959` sets `lastTurnFiles`, `lastTurnFileCount`, `undoCheckpointId`.
- The queued switch runs later in the *same* `finally` (`:1003-1008`) and calls `changeSession`, which rebuilds the state from scratch (`:1371-1378`); `undoCheckpointId`/`lastTurnFiles`/`lastTurnFileCount` fall back to their defaults (`:108-110`). The row only renders when `undoCheckpointId != null` (`ChatScreen.kt:586`).
- Nothing rehydrates: `runtime.latestCheckpoint()` (validator at `:952`, `:1254`) reads complete records from disk (`TurnCheckpoint.kt:107-113`) but is never used to *populate* the state after a switch.

Chosen design: **carry across when the workspace matches, plus rehydrate on load.** Undo is workspace-scoped (`RuntimeController.kt:140-143` keys on `selection.currentIdentity()`), so a checkpoint from the same project is still the right Undo after switching conversations in that project; a switch that changes workspace must drop it. Rehydrating from `runtime.latestCheckpoint()` on `load`/`select` covers restart. Justification: carrying is O(1) and exact (the state already knows the workspace), rehydrating alone would lose the file list for a checkpoint made in the same session.

## Phase F — flagged items

| Item | Status |
|---|---|
| F1 401/403/5xx bodies | **DONE** — `83febf3` (detail on `Unauthorized`/`Forbidden`/`Server`, appended as "The provider said: …"; bodyless keeps the short sentence) |
| F2 non-SSE 200 | **DONE** — `295f61a` (`requireEventStream` in both readers; JSON error → `httpError`, otherwise `InvalidResponse` with `NOT_A_STREAM` copy) |
| F3 classifier replacing a real message | **CLOSED, no code change** — boundary is the tightest the evidence supports, see below |
| F4 `capacitySource` gating | **DONE** — `5209bc8` (guessed window plans at 131 072; entered/detected windows byte-identical, pinned by `aGuessedWindowPlansLowerWhileMeasuredWindowsAreUnchanged`) |
| F5 title dead-end | **DONE** — `c4dbe7f`, test `40a88bd` |
| F6 per-delta rendering cost | pins committed `859c601`; cost cut NOT done — design constraint below |
| F7 three SUSPECTED UI items | 1 settled (state resets, does not hop); 2 DEVICE-ONLY; 3 **FIXED** `327ff50` per Hermes' decision |

### F5 — title dead-end  [ DONE ]

Behaviour chosen (matches both the order and the "runs once" intent): a *failed* title
request keeps the marker `pending`, so the next **successful** turn retries it; a bad or
empty response still ends the marker, so a provider that answers with junk costs one
call. Failing *turns* never call the title path at all (it runs only on a completed
turn), so no title call is burnt on failing turns.

## Phase A2 — second full audit  [ NOT REACHED ]

## What S1/S2 actually changed (read before touching them)

- `CodingSessions` gained a **3-argument `save(messages, interrupted, expectedSessionId)`**
  overload; the 2-argument override delegates with `null`, so the `AgentSessionPersistence`
  interface and all 10 fakes are untouched. The overload throws `IllegalStateException("Session changed")`
  inside the transaction when the pointer moved, matching `compact`.
- `ChatViewModel` holds `private var turnSessionId`, set at turn start (`:560`) and cleared
  in the outer `finally` (`:1002`); `persistTurn(...)` (`:1199`) routes all four transcript
  writes through the bound overload when the store is the SQLite one. Test:
  `ChatViewModelTest.aTurnNeverWritesIntoAConversationItDidNotStartIn` (pointer moved under a
  live turn → other conversation untouched, failure surfaced).
- S2 carries `undoCheckpointId`/`lastTurnFiles`/`lastTurnFileCount` across `changeSession`
  when `undoSurvivesSwitch(old, new)` holds (workspace equality), and drops them otherwise.
  Test: `undoSurvivesAConversationSwitchOnlyInsideTheSameProject`.
- **Residuals of the surgery, stated plainly:** `markInterrupted` is still pointer-based (a
  boolean on whichever session is active); restart rehydration of the Undo row from
  `runtime.latestCheckpoint()` is not built; the end-to-end "queued switch keeps the Undo
  row visible" test needs the workspace/checkpoint harness and is still owed.

### F2 plan (verified shape, not yet written)

`Http.sse` reads the body as SSE whatever `Content-Type` says (`Http.kt:301-302`, `:323`), so a
JSON completion or a JSON error served with `200` is read as an empty stream and reported as a
truncation by all four clients. Fix in one place: after `isSuccessful`, read the content type;
when it is present and is not `text/event-stream`, read the body (bounded, same 64 KiB rule as
`errorText`), and (a) if it parses as an error body, `throw httpError(code, body)` so the real
provider sentence is used, else (b) throw `InvalidResponse` with copy naming the problem —
plain words, e.g. "The provider sent a normal reply instead of a stream. Check that this is a
streaming chat endpoint." `ChatGptPlanClient.responsesSse` needs the same branch (it is a
deliberate duplicate).

### F3 — settled with the device evidence, no code change (closing note)

Evidence: the device refusal body is "Invalid inference request. Check the model's supported
parameters and input limits." (`workorders/device-fix-round4-dsh.md:58`, kaiser's screenshot).

- It names **no effort field**, so any rule requiring an effort word would be blind exactly
  where the device fix needs it. The current rule only requires an effort to have been sent,
  a client-side status, a parameter phrase, and no size phrase — that is the minimum that
  covers the device body.
- Adding "input limits" to `SIZE_REJECTION` would re-classify the device case as a size
  problem, drop the ladder, and undo the round-4 device fix. That is why the doc's standing
  warning exists, and the new evidence confirms it rather than contradicting it.
- No concrete misclassification follows from the evidence, so the code stays as it is.

Named residual: a size-driven refusal whose body says only "Invalid inference request" (no
size phrase) is still attributed to effort, and the provider's sentence is replaced by the
ladder in the banner (it survives in the error detail, not on screen). Settle it with a body
from that provider whose size complaint uses wording outside `SIZE_REJECTION`; until then a
change would be guessing against a hardware-verified path.

### F3 — original note (why I did not change the classifier blind)

Tightening `PARAMETER_REJECTION` (`Http.kt:207-223`) so a generic 400 reaches the user verbatim
would also weaken the recognition that the **device fix depends on**: the ultra-send path was
verified on hardware only because a generic 400 mentioning the model is classified as an effort
refusal (`OpenAiCompatibleClient.reasoningEffort` + `ChatViewModel.kt:924-931`). Any tightening
must be checked against the exact body that provider returned on the device; changing it from
reading alone risks re-breaking a device-verified path. Needs that body, which is in the
round-4 device notes, not in the tree.

### F4 — contract change, stated

Two existing assertions changed with the fix, deliberately: `compactionTriggersBeforeTheCeilingAndAimsLowerStill`
and the default-window half of `contextWindowResolutionOrderManualBeatsDetectedBeats256kDefault`
now expect `131 072 - 16 000`. Justification: the subject of both tests (the two-margin
relationship, and the manual > detected > default resolution order) is unchanged — the
resolution still returns 262 144, asserted in the same test — while the *planning* target for a
guessed window is exactly what F4 changes. The default-window input was lowered from 120 000 to
100 000 so `needsReduction` still tests "under the ceiling", since 120 000 now sits above it.

### F7 item 2 — the saveable clock with no turn key: DEVICE-ONLY

`ChatScreen.kt:868` `rememberSaveable { SystemClock.elapsedRealtime() }` has no turn key, and the
row is a LazyColumn item. LazyColumn preserves saveable state for items it disposes, keyed by
the item key, and this row's key is its list position — so whether turn 2 inherits turn 1's
start time depends on whether the row reuses a slot. Nothing in the code makes it impossible.
Observation that settles it: run a turn for ~20 s, let it finish and start a second turn
immediately — if the second turn's line opens at ~20 s instead of 0 s, it is proven, and the
fix is to key the row by the turn (pass the turn's identity into `LiveStatusRow`).

### F7 item 3 — the draft is not scoped to a conversation: PROVEN (product call)

`InputBar` is called at `ChatScreen.kt:590`, **outside** the `key(state.currentSessionId)` block
that closes just before the ChangeSummary (`:576-585`), and its draft is
`rememberSaveable { mutableStateOf("") }` at `:1276`. So a message typed in conversation A stays
in the composer after switching to B and would be sent to B; A loses it. The mechanism is
proven from the tree. It is a product decision, not a defect with one right answer, so it is not
changed here: the honest fix is `rememberSaveable(state.currentSessionId) { … }` with the
session id passed into `InputBar`, which keeps a draft per conversation and never leaks one into
another. **Decided by Hermes: per conversation.** Implemented in `327ff50` —
`rememberSaveable(sessionId)` in `InputBar`, with `state.currentSessionId` passed in. It cannot
leak text into another chat and loses nothing typed. No UI test harness exists
(`createComposeRule` is absent from this project), so this one rests on the code and CI, and the
device check is simply: type in A, switch to B, the composer is empty; switch back, the text is
still there.

### S2 residual — `markInterrupted` binding  [ DONE — 96ac827 ]

Full fix, not a wrapper: `markInterrupted(interrupted, expectedSessionId)` reads the pointer
once, refuses a stale one, and uses that same id for both the pending-tool-call query and the
`UPDATE`, all inside one `onDatabase` block. The 1-argument override delegates with `null`, so
the interface and all fakes are untouched. `stableFailure` routes through the bound overload
when the store is the SQLite one. Test: `theInterruptedFlagIsBoundToItsConversation`.

### F7 item 1 — message-index-keyed UI state: state is DROPPED, not mis-attributed

`ChatScreen.kt:960-961` `remember(messageKey)` and `:1016` `rememberSaveable(id)` key state by
message index. When the 100-message trim shifts indices, the remember key changes and Compose
**recreates** the state with its initial value — so the effect is a long-press menu closing and
an expanded reasoning block collapsing, never one message's state appearing on another. Real
but cosmetic; the same hazard in `ThinkingBlock`'s `id` is likewise a reset. Left as is: a
content-derived key would fix it but changes nothing a user would call a bug compared with the
drops. DEVICE-ONLY to confirm severity: expand a reasoning block, then keep chatting past the
100-message boundary and watch whether it collapses.

### S2 residual — `markInterrupted` binding (original note, superseded)

Binding it needs the check and the write inside one `onDatabase` block, i.e. refactoring
`markInterrupted`'s body the way `save` was refactored. A check-then-act wrapper would narrow
the window without closing it, and the flag is a boolean cleanup rather than transcript data,
so it does not meet the order's "if it fits the same shape" bar. Left named for Hermes.

### F6 — the pin, and the constraint it exposed

Pinned in `859c601` (`ThinkingPreviewTest`): the streaming preview is the first line of the last
paragraph that has finished a line, and it **stays** on that paragraph even after kilobytes of
new unfinished text — the tail fallback only applies before any paragraph has finished a line.
Two cases were added, and writing them corrected my own assumption: I first expected the long
open paragraph to win, and the implementation proved otherwise (`lastFinishedParagraphFirstLine`,
`ChatText.kt:56-70`).

Consequence for the cure: a shortcut that only scans the end of the text **cannot** be used —
it would move the preview to the open line, which is exactly what the new test forbids. The safe
incremental design is to track state across deltas: keep the last finished paragraph's first
line, and advance it only when a newline arrives (the streaming loop already sees each delta
exactly once), falling back to `tailOfLastLine` only while no paragraph has finished a line.
That is O(delta) per token with identical output for every case pinned, plus the bucketed scroll
key in `ChatScreen.kt:759-769` (recompute the follow target at most once per frame instead of per
delta). It is an assembly-level change to `ThinkingBlock`'s `remember(text, streaming)` and
wants a run with room to reason about it end to end; the pins are in place so that run cannot
silently change behaviour.

## STOP POINT (for the next run)

Stopped after F6's pins (`859c601`). Done: F5 (+test), S1, S2-carry, F1, F2, F3 (closed, no code),
F4, markInterrupted binding, F7 items 1-3 settled (1 resets rather than hops; 2 device-only with
the exact observation; 3 proven mechanism, product call). **Next: F6's cost cut** (see the design constraint above: incremental per-delta state, NOT a
tail window), then restart rehydration for Undo + the end-to-end Undo-row test, then Phase A2. Then F7 (three
SUSPECTED items: message-index-keyed `remember`/`rememberSaveable` re-association after the
100-message trim; `LiveStatusRow`'s saveable clock with no turn key; the draft not keyed to the
conversation — read first, they may be DISPROVEN), the two named S2 residuals, then Phase A2.
