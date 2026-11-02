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

### S2 — Undo must survive a conversation switch  [ DONE — carry 50f21e1, rehydration c30b008 ]

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
| F6 per-delta rendering cost | **DONE** — pins `859c601`, cost cut `952370f` (scanner resumes per delta; scroll key bucketed) |
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
  boolean on whichever session is active) — closed in `96ac827`; restart rehydration of the
  Undo row — closed in `c30b008`, below; the end-to-end "queued switch keeps the Undo row
  visible" test — closed in `c30b008`, below.

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
Implementation settled for the next run (verified against the code, not yet written):

- Extract the loop in `lastFinishedParagraphFirstLine` (`ChatText.kt:56-70`) into a small state
  holder `PreviewScan(summary, paragraphStart)` with `advance(text): PreviewScan`. The text only
  grows, so a scan resumed from the cached `paragraphStart` with the carried `summary` produces
  byte-identical output to a scan from zero — the loop's own variables are already exactly the
  state to carry.
- `thinkingPreview(text, streaming)` becomes a one-shot run of that scanner (`PreviewScan().advance(text)`).
  This is the important part: the pins in `859c601` then exercise **the same code path** the
  composable uses, so an incremental bug cannot hide behind a separately-tested pure function.
- `ThinkingBlock` holds `remember { PreviewScan() }` plus the text length it last scanned, and
  advances only over the new tail; `tailOfLastLine`/`firstLine` keep their current behaviour for
  the not-yet-a-finished-paragraph case (the pins already cover both).
- Bucketed scroll key at `ChatScreen.kt:759-769`: the effect currently keys on
  `streamingReasoning.length` and `streamingText.length`, which restarts it on every delta. Key it
  on the message count and the activity instead, and let the existing `scrollToConversationBottom`
  handle growth — the visible behaviour is unchanged because the effect's action already scrolls
  to the end regardless of which of those inputs changed.

Not written in this run: it is an assembly-level edit to a composable plus its render path, and I
had no room left to write it and re-read it against the pins line by line. Stopping beats a
half-verified "no behaviour change" refactor.

### F6 — built in `952370f` (what the cut actually is, and one bug it found)

**The scanner.** `lastFinishedParagraphFirstLine` (`ChatText.kt:56-70` before) became
`PreviewScan` (`ChatText.kt:78-108`): the loop is unchanged, its own two locals (`summary`,
`paragraphStart`) are the carried state, and `advance(text)` resumes at `paragraphStart` instead
of at 0. `thinkingPreview(text, streaming, scan = PreviewScan())` is a one-shot run of that
scanner, so the pins in `ThinkingPreviewTest` exercise the composable's code path rather than a
parallel pure function. `ThinkingBlock` (`ChatScreen.kt:1029-1037`) keeps one `remember {
PreviewScan() }` and passes it, so a delta reads the open paragraph, not the chain.

**The bug the design as recorded would have shipped.** Resuming at `paragraphStart` alone is not
equivalent to scanning from zero, and the pins do not catch it: a blank-line run that is still
open when a delta arrives (text ends `"...\n\n"`) becomes a *longer* separator on the next delta
(`"...\n\n \n"`), which moves where the following paragraph starts. A resume point inside that
run reads the next line as part of the open paragraph and loses it. Found by executing the scan
in a line-by-line transcription and checking every prefix of 2000 random chains against a
from-zero scan (host has no JDK; the transcription is `round3/preview-scan-check.py`, untracked working evidence, re-runnable with `python3 round3/preview-scan-check.py`); fixed by never resuming inside a trailing run of blanks —
`minOf(start, blankTailStart(text))` (`ChatText.kt:101`, `:113-117`), which can only move the
resume point earlier, and resuming earlier is harmless because the loop is deterministic and
`summary` is overwritten, not accumulated. Pinned for CI by
`aBlankLineThatGrowsAcrossDeltasKeepsTheNextParagraphsFirstLine` (the exact failing sequence),
plus `resumingAScanAtEachDeltaMatchesScanningFromZero` (every prefix of a chain with paragraph
breaks, an open paragraph, a trailing separator and the tail fallback) and
`aScanHandedAShorterChainStartsOver`. **The three pinned tests from `859c601` are unedited**
(additions only — `bash /tmp/audit-checks.sh pins-untouched`).

**Measured cost** (same transcription, 4-char deltas): a 4 449-character paragraph-shaped chain
reads 2 475 312 characters scanning from zero versus 179 091 resuming (13.8×). Honest worst case:
a chain with no blank line at all is one open paragraph, so resuming gains nothing (1.0×) —
unchanged, not worse.

**The scroll key: I did not key on "message count and activity" as recorded, and here is why.**
That would drop the growth-triggered scroll entirely, and growth is the only thing that keeps a
long reply visible: `scrollToConversationBottom` (`ChatScreen.kt:958-965`) pins the *last* item's
bottom to the viewport bottom, and a LazyColumn keeps its anchor when an item above the fold
grows. So the reply would run below the fold until the turn ended (`state.streaming` flips, the
effect re-runs) and then jump. That is a visible behaviour change, which F6 forbids. What is in
`ChatScreen.kt:768-775` instead: the two streamed lengths are keyed in steps of
`STREAMED_SCROLL_STEP = 24` (under half a line at body size, `:659-663`) so a token-sized delta
no longer restarts the effect, while the two transitions that add or remove a row
(`streamingReasoning.isNotBlank()`, `streamingText.isNotBlank()`) stay exact because they move a
whole row at once. The tail can therefore sit at most ~24 characters below the fold and the
effect restarts a fraction as often.

**Residuals, named:** (1) `ChatViewModel.kt:655-656` still builds `reasoningPreview.toString()` per
delta and `ChatScreen.kt:815` still runs `displaySafeText` over the whole streaming answer per
delta — both are O(text) per delta and outside the recorded design; publication per delta is
deliberate (`3641fb9`, round 4 rejected stopping it). (2) The scan is valid only for one growing
chain: the streaming row is a keyed LazyColumn item whose text only grows, and a shorter text
restarts the scan, but a *different, longer* chain handed to the same instance would be wrong —
unreachable today because the row leaves composition when the reasoning blanks
(`ChatViewModel.kt:823-825`, `:917`), and stated as an invariant at `ChatText.kt:68-75`.

### S2 — rehydration and the two row tests  [ DONE — c30b008 ]

**Rehydration.** `rehydrateUndo(workspaceId)` (`ChatViewModel.kt:1295-1311`) reads
`runtimeController.latestCheckpoint()`, keeps the record only when `record.workspace ==
workspaceId`, and fills `lastTurnFiles`/`lastTurnFileCount`/`undoCheckpointId` from it. It runs
on the three paths that rebuild a conversation from disk: the restart load
(`ChatViewModel.kt:283`), `selectProject` (`:370`), and `clearChat` (`:1362`). The row's content is
now derived in one place, `undoRowFor` (`:1284-1289`, files only, five named and the rest counted)
— the turn end at `:970` uses it too, so the row a turn earns and the row a restart finds cannot
drift.

**Why the workspace comparison is the right guard, not a stricter-than-needed one.** A checkpoint
can only be made by a turn, and `startTurn` refuses to run unless the conversation is bound to the
selected project: `projectBindingState` returns `Mismatch` for a session with no workspace while a
project is selected (`ProjectBinding.kt:14`), and `startTurn` stops on Mismatch
(`ChatViewModel.kt:528-532`). So every checkpoint belongs to a conversation whose `workspaceId` is
the project identity, and requiring the loaded conversation's workspace to equal
`record.workspace` cannot hide a row a turn would have shown. The same comparison also keeps a
project P checkpoint off a conversation that belongs to project Q.
`TurnCheckpoint.latest()` (`TurnCheckpoint.kt:107-113`) returns only `complete && after != null &&
!undone && !desynced`, so a restart after a killed turn offers nothing (the interrupted-turn row
is the honest state there), and an undone or desynced checkpoint stays gone.

**The two end-to-end tests** (`ChatViewModelTest.kt:1439-1524`) drive a whole approved
`create_file` turn in the fake project, so the checkpoint is one the product made rather than one
the test wrote: `theUndoRowComesBackAfterARestartFromTheSavedCheckpoint` (a second ViewModel on the
same disk comes back with the same checkpoint id, file list and count) and
`aConversationSwitchQueuedBehindATurnKeepsTheUndoRow` (the owed S2 row test: `resumeSession` is
queued while the turn runs, the switch applies at the end, and the row is still there). The
harness piece the earlier run lacked was not a new fake — `TestDocumentsProvider` +
`selectProject` + `RuntimeController` already produce a real checkpoint; what was missing was a
helper that runs the turn (`undoFixture`, `createFileTurn`). **Both tests are CI-first evidence:**
this host has no JDK, so they have not been executed here; their logic was reasoned from source.

**Residual, stated:** a switch *away* from a project and back still drops the row until the project
is selected again or the app restarts. `RuntimeController.latestCheckpoint()` is scoped to the
selected project (`RuntimeController.kt:140-143`) and `checkpointTurnChanges` refuses a record whose
workspace is not the selected one (`:145-148`), so rehydrating by *conversation* workspace needs a
`latestCheckpoint(workspaceId)` overload plus a changes path that does not require the selection —
a runtime API change outside this order's design. The carry rule keeps the behaviour safe in the
meantime: a drop, never a wrong row.

## STOP POINT (for the next run)

Stopped after S2's rehydration and its two row tests (`c30b008`), on instruction: the Phase A2
re-audit is a separate assignment and waits for Hermes' review of F6 and S2.

Done on this branch, in order: S1 (`bb5d149`), S2 carry (`50f21e1`), markInterrupted binding
(`96ac827`), F1 (`83febf3`), F2 (`295f61a`), F3 (closed, no code), F4 (`5209bc8`), F5 (`c4dbe7f`,
test `40a88bd`), F7 items 1-3 settled (`d156e48`, fix `327ff50`, `4ceceeb`), F6 pins (`859c601`)
and F6's cost cut (`952370f`), S2 rehydration + row tests (`c30b008`).

**Nothing is half-done and nothing is uncommitted.** Phase A2 (the whole-branch re-audit from
`c0c49c8..HEAD`) has not started.

**What the next run must re-check first, in this order:** (1) exact-head CI on this branch — no
commit here has been executed, so the F6 scanner and the S2 rehydration code have never run;
(2) the two new `ChatViewModelTest` tests, which are the only coverage the Undo row has end to
end; (3) then Phase A2, hardest look at `952370f` and `c30b008`.
