# Stability surgery + re-audit — working doc (order: stability-surgery-and-reaudit.md)

Base: `ab8d1f4`. Checklist is the order's phases, in order. One commit per fix.
The latest stop point is at the end of the final pre-merge section and says exactly where to
resume. Nothing half-done was left uncommitted.

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

## Phase A2 — second full audit  [ DONE — see "Phase A2 record" at the bottom ]

Verdict, stated up front: **thirteen defects found and fixed in the surgery audit — five in
product code, one in copy, six in tests (one of which broke compilation), and one compile break in
the main source set — plus six more from the final pre-merge correctness audit.** Four came from this audit, two from Hermes' review of its record, five from
CI's three executions, and two from the device (all at the end of this section). The residuals were
re-checked one by one: none could be cleared outright, five are confirmed as stated, and this
pass adds three of its own. The SCOPE/ENVIRONMENT note at the head of the A2 record is
load-bearing: no Kotlin was executed on this host, so every test named below runs for the
first time in CI.

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

`ChatScreen.kt:868` (now `:884`) `rememberSaveable { SystemClock.elapsedRealtime() }` has no turn key, and the
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

**Corrected in A2 (the chosen implementation did not do what it claimed).**
`rememberSaveable(sessionId)` satisfies the *first* half only. Its documented contract is that
changed inputs "cause the state to reset and revert to the init lambda", so `327ff50` destroyed
the draft on every switch and switching back showed an empty composer — the device check above
would have failed. Fixed in `6e26d94`: the drafts are one saveable `Map<String, String>` keyed
by session id (`ChatScreen.kt:1297-1318`), typing writes only that conversation's entry
(`:1348`), a send clears only the entry it sent, and `draftFor`/`withDraft`
(`Composer.kt:13-20`) are pure and pinned in `ComposerTest`
(`everyConversationKeepsItsOwnDraft`, `sendingClearsOnlyTheConversationThatSent`). The map is
saved through a `mapSaver`, so drafts still survive process death, which the keyed version also
did.

### S2 residual — `markInterrupted` binding  [ DONE — 96ac827, one path still unbound ]

Full fix, not a wrapper: `markInterrupted(interrupted, expectedSessionId)` reads the pointer
once, refuses a stale one, and uses that same id for both the pending-tool-call query and the
`UPDATE`, all inside one `onDatabase` block. The 1-argument override delegates with `null`, so
the interface and all fakes are untouched. `stableFailure` routes through the bound overload
when the store is the SQLite one. Test: `theInterruptedFlagIsBoundToItsConversation`.

**Correction (A2).** "Closed" is true of `stableFailure` only. Two other writes of the same
flag happen *inside* a turn and still call the unbound overload: `ChatViewModel.kt:897` (the
`AgentSessionLimitException` handler) and `ChatViewModel.kt:994` (the user-cancel path). The
reachability argument is the same one the rest of S1/S2 rests on — the pointer cannot move
while `streaming` is true (`changeSession` returns at `:1428`, `selectProject` at `:339`) — so
this is an incomplete defence, not a live defect, and it is left as is deliberately: both
sites sit inside error handlers where a new throw path from the bound call (which raises
`IllegalStateException("Session changed")`) would have to be given its own handling, and the
guard they bypass cannot fire today. Named here so the claim and the code agree.

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

**A2 re-check of those two.**
(1) **Confirmed, and it bounds the win.** The lines are now `ChatViewModel.kt:663`
(`reasoningPreview.toString()`) and `ChatScreen.kt:819` (`displaySafeText(streamingText)`). A
reasoning delta therefore still costs one full copy of the chain plus one full escape pass
before the incremental scan saves anything, so the 13.8× figure is the *scan's* ratio, not the
turn's; the end-to-end per-delta cost is still linear in the chain. Not changed: stopping the
per-delta publication is the round-4 decision `3641fb9`.
(2) **Narrowed: "unreachable" is too strong.** The row leaves composition only if a
recomposition actually observes `streamingReasoning == ""`. Compose can coalesce the blanking
update and the first delta of the *next* chain into one frame; the item then never leaves, the
same `PreviewScan` (`ChatScreen.kt:1039`) sees the new chain, and the resume path only restarts
when the new text is *shorter* than the old one read (`ChatText.kt:85-89`). A new chain that
first arrives longer than the old one — plausible when step 2's reasoning bursts after a fast
tool — would resume at the old `paragraphStart` with the old `summary` and print a wrong one-line
preview. Only the preview line is affected; the chain text and the transcript are untouched.
Settling evidence: a Compose-level test (none exists here) or one device turn whose reasoning
runs two paragraphs, then a tool call, then a longer second chain. Not fixed: the fix needs a
chain identity in the state (a counter or an epoch on `streamingReasoning`), which is a state
shape change this pass could not execute.

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

## Phase A2 record — second full audit of `c0c49c8..HEAD`

### Scope and environment (read this first)

Branch `stability-v1.1.0`, `c0c49c8..HEAD` — 107 commits when this pass started, 112 with the
five it adds — audited fresh. The previous pass
worked from `ab8d1f4`, which is inside this range, so the whole range was re-read with the
hardest look on the code the surgery itself added: `bb5d149` (S1), `50f21e1` + `c30b008` (S2),
`96ac827` (flag binding), `295f61a` (F2), `5209bc8` (F4), `327ff50` (draft), `952370f` (F6).

**This host has no JDK.** `java`, `javac`, `kotlinc` are absent and there is no `/usr/lib/jvm`,
so **no Kotlin was compiled and no Gradle task was run — not the unit tests, not lint, not the
assemble.** CI (`./gradlew testDebugUnitTest`, `.github/workflows/android-build.yml:37-38`) is
the first execution of every test named here, including the ones this pass adds or corrects.
Every claim below is either (a) a reading of source with `file:line`, or (b) the output of a
Python audit I ran and name; nothing is from a device or a JVM.

### Verdict

**Thirteen defects, all fixed: five in product code (A2-3, A2-4, C1, DF-1, DF-2), one in
user-facing copy (C2), six in tests (A2-1 could never pass; A2-2 had lost its subject; CF-2 would
not compile; CI-3, CI-4 and CI-5 pinned the right behaviour on fixtures that could not observe
it), and one compile break in the main source set (CF-1).** Commits: `0db23a5`, `b4b7206`,
`6e26d94`, `2f9f5c4`, `fbb152f`, `4e677da`, `0b28b03`, `acbc563`, `e83d218`, `ae7fb58`, `86d80cf`,
`3051632`, `b06145d`.

| # | Finding | Kind | Commit |
|---|---|---|---|
| A2-1 | F2's pin asserted a classification the code cannot produce; would have failed CI | test, unsatisfiable | `0db23a5` |
| A2-2 | F4 silently made the projection-cap pin vacuous (guess halves the trigger) | test, lost coverage | `b4b7206` |
| A2-3 | Per-conversation drafts were destroyed by every switch, not kept | product code | `6e26d94` |
| A2-4 | F1 put provider words in the error banner unescaped (bidi controls) | product code | `2f9f5c4` |
| CF-1 | `ContextBudget` called two provider extensions it did not import: the file did not compile | **found by CI, not here** | `0b28b03` |
| CF-2 | `ProviderErrorClassificationTest` called `assertEquals` without importing it: the unit-test set did not compile | **found by CI, not here** | `acbc563` |
| CI-3 | `theInterruptedFlagIsBoundToItsConversation` asserted a flag on a conversation that had no turn to interrupt | test | `e83d218` |
| CI-4 | `aTurnNeverWritesIntoAConversationItDidNotStartIn` moved the pointer before the turn's first write | test | `ae7fb58` |
| CI-5 | `titleProviderFailureAndInvalidOutputNeverFailCodingTurn` reused one store across three cases, so a pending title retried into the next case | test | `86d80cf` |
| DF-1 | the live-status clock's start lived in the list row, so a relayout (a thinking block folding) restarted it at 0s | **found on device** | `3051632`, audit `31f0895` |
| DF-2 | the follow-scroll bottom-aligned to the viewport and only fired on character buckets, so a wrap left the live-status row half below the list's edge | **found on device** | `b06145d` |
| — | `turnSessionId` lifetime, F2's content-type gate and its four readers, S1 write ordering, Undo carry/drop and rehydration, F4's reach into the send path, the draft's leak into another chat | **no defect** | withdrawn, below |

### A2-1 — F2's `ModelNotFound` assertion cannot pass (`0db23a5`)

`HttpBridgeTest.aNonStream200IsReportedForWhatItIs` (`:198`) enqueued a 200 carrying
`{"error":{"message":"model 'x' does not exist"}}` and asserted the reader throws
`ProviderError.ModelNotFound`. It cannot: `requireEventStream` (`Http.kt:294-302`) calls
`httpError(response.code, body)` with `response.code == 200`, and `httpError` (`:109-135`) only
reaches `ModelNotFound` through `isModelNotFound`, whose first line is
`if (code != 400 && code != 404) return false` (`:168`). A 200 therefore lands on
`ProviderError.ProviderMessage(detail, 200)`.

**Contract, stated because a test changed:** F2's contract is "the provider's own sentence
reaches the user", not "a 200 is re-classified". The pin now holds the wording
(`"model 'x' does not exist"`) and the status code, which is stricter about the thing that
matters and no longer asserts a branch that cannot be taken. Re-classifying 200 bodies as
missing models was rejected: it would mean weakening the guard that keeps a base-URL path
problem from walking the user into the model picker (`:163-174`).

### A2-2 — F4's guess halved the trigger this test was written against (`b4b7206`)

`ContextBudget.assess` now plans a *guessed* window at `min(resolved, 131_072)` (`:69`), so the
fixture in `aBigWindowCompactsBeforeTheStoredProjectionHitsItsCap`
(`ContextPreparationTest.kt:220`) — a `ProviderConfig` with no entered or detected window — has
`compactAtTokens = 115_072 - 14_384 = 100_688` where it used to be 215_376. Its ~168k-token
transcript is therefore *over* the token trigger, so `prepareChecked`'s early return
(`:91`) is decided by `shouldCompact`, and the `projectionTriggerTokens` term (152,916) the test
exists to pin is never the reason compaction fires. The assertions still pass — for the wrong
reason. Entering the 262,144-token window restores `compactAtTokens = 215_376` and the test's
own stated numbers; no assertion changed, and F4's code is untouched by this commit.

This is the "never weaken a test" rule applied to a test weakened *by a code change under it*
rather than by an edit, which is the form that is easy to miss.

### A2-3 — `rememberSaveable(sessionId)` dropped the draft instead of keeping it (`6e26d94`)

`327ff50` replaced `rememberSaveable { mutableStateOf("") }` with
`rememberSaveable(sessionId) { mutableStateOf("") }`. Changing an input of `rememberSaveable`
does not carry the value to the new key — the documented contract is that changed inputs "cause
the state to reset and revert to the init lambda" — so conversation A's text is discarded the
moment B is opened, and reopening A composes a fresh empty state. The doc's own device check
("switch back, the text is still there") would have failed, and the half of the decision that
mattered ("loses nothing typed") was not implemented.

Fixed with one saveable `Map<String, String>` keyed by session id
(`ChatScreen.kt:1297-1318`), written on every change (`:1349`), cleared only for the
conversation that sent, and saved through `mapSaver` so drafts still survive process death.
The pure helpers `draftFor`/`withDraft` (`Composer.kt:13-20`) carry the contract into JUnit
(`ComposerTest`), because this source set has no `createComposeRule` and the wiring itself
cannot be asserted here.

### A2-4 — the error banner rendered provider text raw (`2f9f5c4`)

F1 (`83febf3`) made the banner show the provider's own sentence
(`ChatViewModel.kt:951-952` via `providerMessageText`, and `ProviderError.text()`'s
`said(detail)` for the 401/403/5xx cases). `ErrorRow` printed it with no `displaySafeText`,
while every other foreign string on the screen — model text, project names, model ids, file
paths, approval details, the thinking preview — goes through it. A bidi control inside an error
body reorders the line around Rivet's own sentence.

Fixed at the render site (`ChatScreen.kt:1274-1281`) rather than in the two formatters: the
provider layer must not depend on a `ui.chat` helper, and the banner also shows fixed app copy,
which this leaves untouched. No Compose test rule exists, so this rests on the code and CI.
***Same class, other surfaces — closed in `fbb152f` (Hermes' first closing fix).***
`ProvidersViewModel.editorError` (`:793-800`) also returns `ProviderError.text()`, and the
provider screens rendered it raw. The same one-line treatment now applies at each site, and a
site-by-site account of what flows in is in the closing-fix record at the end of this section.

### Withdrawn suspicions (each examined, none survived)

- **`turnSessionId`'s lifetime.** Suspicion: the outer `finally` clears it unconditionally
  (`ChatViewModel.kt:1008`), so a second turn that started while the first was still finishing
  would run unbound. It cannot: the assignment (`:568`) is *inside* the turn coroutine and comes
  after `previous?.join()` (`:466`), so the next turn's binding is written strictly after the
  previous turn's clear. Verified by indentation as well as by reading. No defect.
- **F2's content-type gate.** `requireEventStream` returns early when the type is absent or
  contains `event-stream` (`Http.kt:295-296`), so it adds no failure for the four shipped
  readers (all covered: `sse` `:331`, `responsesSse` `ChatGptPlanClient.kt:348`) and keeps the
  old behaviour for a server that sends no type at all. The one behaviour it changes is named as
  residual 7 below.
- **S1 write ordering.** `save`'s check sits inside the same transaction as the write
  (`CodingSessions.kt:108-115`) and every pointer move goes through the same `onDatabase` mutex
  (`:524-526`), so no check/write pair can be split. The pinning test drives the pointer move
  with a direct `sessions.select(other)` (`ChatViewModelTest.kt:1559`) — a backdoor the ViewModel
  itself cannot take while `streaming` is true — so it proves the *binding*, not the reachability
  of the switch. That is the right thing for it to prove; the comment "as a switch in flight
  does" overstates it.
- **Undo carry/drop and rehydration.** `undoSurvivesSwitch` compares the two workspace ids
  (`:1424`), `changeSession` carries the row only when they match (`:1447-1458`), and
  `rehydrateUndo` re-reads the store on the three paths that rebuild a conversation from disk
  (`:283`, `:370`, `:1362`) with a workspace check that both reads (`:1300`) and re-checks at the
  state update (`:1307`). `selection.currentIdentity()` is a persisted preference, not in-memory
  selection state (`WorkspaceSelection.kt:34-36`), so the restart path cannot lose the race
  against `loadProject()`. No defect.
- **F4's reach.** `allowedInputTokens` is read in exactly four places
  (`ContextPreparation.kt:83`, `:103`, `:143`, `:223`), never by `AgentLoop`, and
  `resolvedContextWindowTokens` is *used* only in the banner (`ChatViewModel.kt:1080`). The cap
  is bounded to planning. `capacitySource` remains unread by production code — the order asked
  for it to gate the target, and the gate is `guessedWindow` instead, which is the same
  question asked of the window rather than of the estimate. One leftover of that edit: the
  import of `resolvedContextWindowTokens` (`ContextBudget.kt:9`) is no longer used, because the
  call it served is now the `indicatedWindow ?: DEFAULT_PLANNING_LIMIT` pair at `:62-63`.
  Harmless — the Kotlin compiler does not warn on unused imports, and neither Gradle lint nor
  the workflow sets a policy that would (app/build.gradle.kts has no `lint {}` block) — so it
  is named here rather than bundled into a fix commit.

### Named residuals — re-checked one by one

1. **Per-delta `toString()` / `displaySafeText`** — confirmed, and it caps the F6 win (see the
   F6 section). Not changed; deliberate publication is `3641fb9`'s decision.
2. **The scan assumes one growing chain** — confirmed as an invariant, narrowed as an
   unreachability claim (F6 section). Not changed; needs a chain identity in the state.
3. **F3: a size-driven refusal that says only "Invalid inference request"** — confirmed
   unresolved. Settling evidence unchanged: that provider's body for a size complaint whose
   wording is outside `SIZE_REJECTION`. No code change made or implied.
4. **F7 item 2, the saveable clock with no turn key** (`ChatScreen.kt:884`) — confirmed
   DEVICE-ONLY. I could not narrow it further: whether the state survives depends on
   `LazySaveableStateHolder`'s dispose rule, and no Compose sources or jars exist on this host to
   read. The settling observation stays the one recorded (20 s turn, second turn immediately
   after: does its line open at ~20 s?).
5. **F7 item 1, message-index-keyed UI state** — confirmed: `remember(messageKey)` (`:978-979`)
   and `rememberSaveable(id)` (`:1035`) revert to their init value when the key changes, which is
   the same documented contract A2-3 turned on. A drop, never a carry-over. Left as is.
6. **A switch away from a project and back keeps Undo hidden until the project is selected or
   the app restarts** — confirmed by the same reading that cleared the carry rule: rehydration
   only runs on load/`selectProject`/`clearChat`, and `changeSession` carries only when the
   workspace matches, so P → Q → P through `resumeSession` shows no row. Requires a
   `latestCheckpoint(workspaceId)` and a changes path that does not need the selection, as the
   earlier run wrote.
7. **New: a provider that serves SSE as something other than `text/event-stream`** now fails the
   turn with `NOT_A_STREAM` instead of streaming. Unproven — no shipped provider does it as far
   as the tree shows (Anthropic, OpenAI-compatible, Gemini `alt=sse` and the Responses API all
   send the proper type), and the check cannot tell a mislabelled stream from a JSON reply
   without reading the body. Settling evidence: one real response from such a proxy. The failure
   is loud and names the real problem, which is what F2 was for.
8. **New: the F4 refusal banner named a window the planner did not use.** `finish()` formatted
   `config.resolvedContextWindowTokens()` (`ChatViewModel.kt:1079-1080`), which is 262,144 for a
   guessed window, while `ContextUnavailable`/`ContextTooSmall` are produced against
   `allowedInputTokens` = 115,072. A user was told the chat "does not fit the 262,144-token
   context window" when nothing was ever judged against that number. **DECIDED BY HERMES and
   fixed in `4e677da`** — see closing fix C1 below.
9. **New: the open window between `changeSession`'s generation check and its `action(store)`.**
   `changeSession` checks the ticket *before* moving the pointer (`:1434-1435`), so a `send()`
   that lands in the gap after the check starts a turn whose `sessionId` is still the old
   conversation while the pointer is about to move. With S1 in place the turn then fails loudly
   ("Could not save the conversation…") instead of filing into the other conversation — which is
   S1 doing its job — but the banner blames storage for a race, and `activeMessages` has already
   been replaced with the new conversation's. Pre-existing shape, narrowed by S1, not fixed: no
   execution here to tell a real interleaving from a theoretical one.

### F6 — the equivalence claim, checked independently

I wrote my own transcription of `PreviewScan` and of the pre-F6 loop from
`ChatText.kt:78-117` (`round3/a2-scan-equivalence.py`, untracked working evidence, re-runnable
with `python3 round3/a2-scan-equivalence.py`), not from the earlier run's script, and compared:

- exhaustive over every string of length ≤ 8 on `{A, space, LF, CR}` and length ≤ 7 on
  `{A, space, LF, TAB, CR}` — 97,656 chains — advancing one character at a time and comparing
  every prefix against a from-zero scan: **0 mismatches**;
- 4,000 random chains over 11 tokens (words, `\n`, `\n\n`, `\r\n`, `\r\n\r\n`, `\r`, tab)
  with one-character deltas and with random delta sizes: **0 mismatches**;
- 3,000 streams whose deltas *skip* prefixes (two deltas consumed by one composition read):
  **0 mismatches**;
- handcrafted growth sequences including the recorded bug (`"A\n\n"`, `"A\n\n "`,
  `"A\n\n \n"`, `"A\n\n \nB"`, …), `\r\n\r\n` separators and a blank run that grows
  across deltas: **0 mismatches**;
- repeated `advance` with the same text: idempotent.

Why it holds, beyond the samples: growth can only change where a separator starts if that
separator was *incomplete* at the old end, and an incomplete separator is a run of whitespace
from its newline to the end, so it starts at or after `blankTailStart`. Resuming at
`min(start, blankTailStart)` therefore never skips a paragraph boundary that later moves, and
`summary` is overwritten rather than accumulated, so re-reading a few characters is harmless.
The tab/space class of `BLANK_LINE` is a subset of `Char.isWhitespace`, which is what makes the
"resume at or before the whitespace run" argument safe.

Run for cross-reference, the earlier run's `round3/preview-scan-check.py` also passes and
reproduces its quoted cost figures (2,475,312 characters from zero vs 179,091 resumed on a
4,449-character paragraph-shaped chain, 13.8×; 1.0× on a chain with no blank line). **What is
not proven here:** that the Kotlin above is what I transcribed. Nothing on this host can compile
or run it, so the first execution of `ThinkingPreviewTest`'s new cases is CI.

### What this pass could not execute

- Any Kotlin compilation, unit test, lint run or APK build. CI is the first execution of
  `0db23a5`'s corrected assertion, `b4b7206`'s entered window, `6e26d94`'s Compose wiring and
  `2f9f5c4`'s render change.
- The two `ChatViewModelTest` Undo row tests from `c30b008`, and the S1/S2/markInterrupted pins
  — read line by line against their fixtures; not run.
- Any on-device observation, including F7 item 2 and F7 item 1's severity.
- The Compose runtime's actual saveable-state behaviour (no Compose jars or sources available
  offline); A2-3 rests on the documented `rememberSaveable` contract, not on a run.

### Closing fixes (Hermes' review of this record)

**C1 — `fbb152f`: the provider screens' error text, same rule as the banner.** The A2-4
leftover, closed at the render sites and not in the formatter, as asked. No site already
escaped; six `Text` calls now go through `displaySafeText` — the four the review named plus
two on the same screens that print a stored string:

| Site | What flows in | Already escaped? |
|---|---|---|
| `ProviderEditor.kt:235` `test.message` | `editorError` -> `ProviderError.text()`: provider words | no |
| `ProviderEditor.kt:243` `fetchError` | same | no |
| `SettingsScreen.kt:163` `loadError` | fixed app copy (`ProvidersViewModel.kt:90/605/623`) | no |
| `SettingsScreen.kt:225` `autonomyError` | fixed app copy (`RivetApp.kt:156`) | no |
| `SettingsScreen.kt:280` `appearanceError` | fixed app copy (`RivetApp.kt:135`) | no |
| `SettingsScreen.kt:167` `headerNotice` | fixed app copy with a count (`ProvidersViewModel.kt:106-114`) | no |

The review named `fetchError`, `loadError`, `autonomyError` and `appearanceError`. `test.message`
is one line above `fetchError` and is the same exposure — the main "Test connection" error
surface — so it is included, and said so here rather than folded in silently. `headerNotice` came last: a broader source check ("no bare `Text(<stored string>, ...)`
left on the provider screens") failed on exactly that line, so the code was changed to satisfy
the rule rather than the claim narrowed to fit the code (`8e4b97dc851f`, `3ef966e`).

**One receipt in this ledger is void and stays that way.** The first recording of that check
was made as `bash -c '! grep ...'`, and the receipts tool stores a command as its argv joined by
spaces and replays it as a string, so the quotes were lost and `rcpt-0016` re-runs as
`bash -c ! grep ...`, which cannot exit 0 whatever the code says. It is left in the ledger
rather than rewritten, because editing evidence by hand is the thing a ledger exists to
prevent, and the rule it was meant to state is checked by `rcpt-0017`, whose recorded command is
`bash round3/provider-text-check.sh` (a script, so replay is exact).

Two more receipts, `rcpt-0021` and `rcpt-0023`, are void for a plainer reason: for kaiser's
thinking-box tap and again for the turn clock I called `receipts add` before writing
`round3/thinking-tap-check.py` / `round3/turn-clock-check.py`, so each recorded a command that did
not exist yet and replays as exit 127. `rcpt-0022` and `rcpt-0024` are the same claims against the
scripts, and both pass.

`rcpt-0005` fails for a fourth and different reason — **its subject was deleted on purpose**.
It listed `STREAMED_SCROLL_STEP` among the declarations that must resolve, and `b06145d` retired that
constant with the character-bucketed trigger it served, so the receipt now reports exactly one
missing symbol while the other three still resolve. `rcpt-0026` re-states the surviving claim.

The void lines stay — the tool has no remove, and hand-editing the ledger is the one thing a ledger
exists to prevent — so `receipts check` reports four failing lines out of 26: three recording
mistakes and one deliberate deletion, not evidence that the work is unverified. Recording before
writing the command is the same mistake twice; the fix is to write the script first, and the next
entry in this record does that.

The three SettingsScreen error values and the notice carry no provider text today, so escaping
changes nothing they show; they are done anyway so the rule is "every Text that prints a stored
string on the provider screens" instead of a list of exceptions to keep current. No test:
`displaySafeText` is already pinned
(`ChatPresentationTest.bidiFormattingControlsAreVisibleInApprovalAndPathText`) and this source
set has no `createComposeRule` to assert the wiring.

**C2 — `4e677da`: the context refusal names the budget it judged against (Hermes' decision).**
The F4 guess/measured distinction is used, not re-derived: `ContextAssessment` now carries
`windowIsGuessed`, set from the `guessedWindow` the planner already computes
(`ContextBudget.kt:34`, `:68`, `:75`, `:95`). `finish()` reads it once (`ChatViewModel.kt:1129`,
`:1131`) and the two sentences became pure functions (`:164`, `:175`), which
`ContextRefusalCopyTest` pins without a Compose rule:

- **Entered or detected window — unchanged, byte for byte.** "…and it still does not fit the
  200,000-token context window. Your messages were kept…" and "This model's context capacity is
  too small (200,000-token context window) for this request…".
- **Guessed window.** "…and it still does not fit the 115,072-token budget Rivet planned for
  this model (no context window is known for it). Your messages were kept…" and "This request
  does not fit the 115,072-token budget Rivet planned for this model (no context window is
  known for it). Choose a model with a larger context window or shorten the request."

Contract change, stated: the guessed sentence no longer claims anything about the model's
capacity, because Rivet does not know it; the number it prints is the ceiling the request was
actually refused against. **No test pinned the old wording**: `ChatViewModelTest:912` checks the
substring "compacted this chat as far as it could", which both variants keep, and its fixture
detects a 16,000-token window so it takes the unchanged branch.
`ContextBudgetTest.aGuessedWindowPlansLowerWhileMeasuredWindowsAreUnchanged` gained three
flag assertions (additions only, no assertion changed).

**Checked mechanically, not by eye.** `ContextRefusalCopyTest` cannot run on this host, so its
six expected sentences were compared against the strings the implementation assembles, by
`round3/refusal-copy-check.py` (untracked working evidence, re-runnable here with
`python3 round3/refusal-copy-check.py`). All six match, and the guessed variants are also
checked for the guess they must not print. The check was shown to be red-capable: a
one-digit change in the test's expected number turns it into exit 1, and the file was
restored byte-exact afterwards.

Banned-word check over both fixes' new copy against the seven terms the order bans: none of
them appears in the new sentences or in the new comments.

### CI-found fix (the branch's first execution)

**CF — `0b28b03`: `ContextBudget` called two provider extensions it never imported.** Evidence
from CI, which ran this branch for the first time: release run `41383346256` and Android Build run
`41383171000`, both failing the same way —

```
ContextBudget.kt:66:38 Unresolved reference 'manualContextWindowForModel'
ContextBudget.kt:67:23 Unresolved reference 'detectedContextWindowForModel'
```

Both are extensions on `ProviderConfig` declared in `provider/ProviderConfig.kt:113,118`; Kotlin
does not resolve an extension without its import. The import block (`ContextBudget.kt:3-13`) listed
`resolvedContextWindowTokens`, `resolvedManualMaxOutputTokens`, `trustedInputLimitTokens`,
`anthropicOutputCeiling` and `openRouterOutputCeiling` from that package, but not the two the file
calls. The defect came in with F4 (`5209bc8`), whose `guessedWindow` replaced the single
`config.resolvedContextWindowTokens()` call with these two and imported neither, leaving the
old import behind unused — which is the same fact this record noted and did not follow up.
**The tree therefore had not compiled since `5209bc8`**: the 29 commits after it, including the
F6 cost cut and the S2 rehydration, all inherited the break, and every one of them was audited
here as if it could run. Fixed by adding exactly those two imports.

**Why this pass missed it, plainly.** Every check this audit ran was behavioural or structural:
pins, line-by-line reading, and Python transcriptions of algorithms. The `balance` check proves
delimiters match; the `symbols` check proves a declaration exists in a package. **Neither proves
that a caller imports it, and nothing here can compile.** The neighbouring symptom was even in
this record — I noted that the `resolvedContextWindowTokens` import had become unused — and I did
not ask the obvious follow-up, which is whether the imports it needed were present. The lesson is
recorded rather than softened: an audit that cannot build cannot conclude "nothing left open"; it
can conclude only "nothing I could see is open".

### The sweep for the same shape

Hermes asked for a branch-wide sweep for this exact failure mode: an extension (or any top-level
declaration) defined in one package, referenced from another package whose file lacks the import.
`round3/import-sweep.py` does it statically (untracked working evidence, re-runnable here):

1. Reads every `.kt` under `app/src/main/java`, `app/src/test/java` and
   `terminal-emulator/src/main/java` — **163 files** — and blanks comments and string literals so
   words inside them cannot match.
2. Indexes every top-level declaration that is importable (skips `private`), by name and package:
   **400 names, each declared in exactly one package** (grouping by package rather than by file, so
   same-package overloads are handled instead of skipped).
3. For every file in another package that references such a name in a call, constructor or type
   position, requires `import pkg.Name` or `import pkg.*`. Anything else is reported with
   `file:line` and whether that file is inside `c0c49c8..HEAD`.
4. Reports two filtered categories separately rather than hiding them: references written with the
   full package at the call site (no import needed, 15 of them) and references to a name that is
   also a library member (`await`, 13 of them).

**Positive control, because a sweep that cannot fail proves nothing:** with the committed import
temporarily removed, the sweep prints `ContextBudget.kt:66 manualContextWindowForModel` and exits
1; the file was then restored and `cmp` confirmed it byte-exact, and the sweep exits 0.

**Result: after the fix, 0 findings.** The only real hits the sweep ever produced were the two CI
errors, so the commit carries the imports and nothing else. The 15 fully-qualified references
(`com.kaiser.rivet.agent.AgentResponse/AgentToolCall/AgentToolResult/InternalContext/SafAgentWorkspace/`
`PreparedAgentTool/modelMessages`, `com.kaiser.rivet.provider.AgentRequest/ReasoningLevel`,
`com.kaiser.rivet.runtime.ManagedProcesses/MirrorFailure`,
`com.kaiser.rivet.workspace.WorkspaceSelection`) and the 13 `await` sites (`Deferred`/`Job` members,
`AgentApprovalGate::await`, and the tests' own `await(viewModel)` helper) were each read before
being filtered; the lists print under `--verbose`.

**What the sweep cannot see, so the compiler stays the authority:** names and signatures of
library/external APIs (no dependency jars here), cross-package use of a `private` declaration (I
tried it with a crude matcher and got only false positives — local variables named `count`, and the
word "said" inside a UI string — so it is not reported rather than reported as noise), generic and
typealias resolution, and anything that only fails at compile time for non-resolution reasons. CI
listed exactly two errors and nothing else, which is why "no other file needed this fix" is a
conclusion from the compiler for the produced source sets and from the sweep for the rule.

The unused `resolvedContextWindowTokens` import stays, as recorded earlier: it is a warning-level
leftover with its own note, not part of this fix.

### CI-found fix 2 (second CI round)

**CF-2 — `acbc563`: a test called `assertEquals` without importing it.** Evidence from CI at head
`7c722ce`, exactly as reported and not extrapolated:

- Release run `41384155003` — **SUCCESS**. The main source set compiles, which confirms CF-1
  (`0b28b03`) closed the first break.
- Android Build run `41384152559` — **FAILURE at `:app:compileDebugUnitTestKotlin`**, with exactly
  two errors in the whole log: `ProviderErrorClassificationTest.kt:89:9` and `:91:9`,
  `Unresolved reference 'assertEquals'`.

The file imported `org.junit.Assert.assertTrue`, `org.junit.Assert.assertFalse` and
`org.junit.Test`, and never `assertEquals`; lines 89 and 91 are the two assertions F1 added
(`83febf3`), the same commit whose banner text C1 had to fix. One import added; the three
`Assert` imports are now in alphabetical order.

**Why the first sweep could not see it, and what covers it now.** `round3/import-sweep.py` indexes
declarations made in this repository; `assertEquals` comes from a jar, and the CF-1 record already
named that as a limit rather than hiding it. This break is a library name in a test file, so it
needed a second rule.

**The test-source sweep — `round3/junit-import-sweep.py`** (untracked working evidence,
re-runnable here):

1. Reads all **78 Kotlin files under `app/src/test`**, blanking comments and string literals.
2. For each of the **18 public statics junit 4.13.2 exposes** on `org.junit.Assert`
   (`assertEquals`, `assertNotEquals`, `assertTrue`, `assertFalse`, `assertNull`, `assertNotNull`,
   `assertSame`, `assertNotSame`, `assertArrayEquals`, `assertThat`, `fail`, `assertThrows`,
   `assertDoesNotThrow`, `assertAll`, `assertTimeout`, `assertTimeoutPreemptively`,
   `assertIterableEquals`, `assertLinesMatch`), finds unqualified call sites and requires either
   `import org.junit.Assert.<member>` or an object wildcard (`import org.junit.Assert.*`).
   `Assert.<member>(...)` behind `import org.junit.Assert` is accepted and reported separately.
   Resolution is by member name, not by JUnit specifically, so an equivalent import from another
   matcher library would also count.
3. **230 (file, member) pairs** resolve through an import or a wildcard; **1 finding**: the file
   above at `:89` and `:91`. After the fix: **0 findings**.
4. **Positive control:** removing the committed import again makes the sweep print the same file and
   lines and exit 1; the file was restored and `cmp` confirmed it byte-exact, and the sweep exits 0.

**What this sweep cannot see, stated so the compiler stays the authority:** missing imports of any
other library name — types, functions, constants from JUnit, Robolectric, OkHttp,
MockWebServer, kotlinx — because their members are not enumerable here without the jars; and
anything that fails for reasons other than resolution. CI reported exactly two errors for that
task; the Kotlin frontend reports unresolved references across the module rather than stopping at
the first, and two is nowhere near its cap, so this is expected to be the last compile error in
the unit-test source set. Expected is the right word: **the unit-test suite has still never
executed.** Compilation of the test source set is the current gate; test results are next
round's question, and every behavioural claim in this record (the Undo row tests, the S1/S2
pins, `ContextRefusalCopyTest`, the corrected `HttpBridgeTest` assertion) still rests on
reading until then.

### CI round 3 — the suite executed, three fixtures failed

Evidence, exactly as reported: Android Build run `41384666315` at head `8c042ea`, task
`:app:testDebugUnitTest`, **742 tests completed, 3 failed**; Release run `41384669562` **SUCCESS**.
The other **739 passed, and nothing they cover was touched**: every change below is fixture setup
inside a failing test, and **the product code was not modified in this round at all**.

All three are test-wrong, and each verdict was reached from the code path before anything was
edited. No assertion was weakened, deleted or relaxed: the round's diff is one added setup line
(CI-3), one replaced wait (CI-4) and two added setup lines (CI-5), each with a comment that
states the mechanism, and nothing else.

#### CI-3 — `e83d218`: the flag had no turn to sit on

`ChatViewModelTest > theInterruptedFlagIsBoundToItsConversation`, `:1393`: *"the flag was not
cleared through the stale pointer expected:<true> but was:<false>"*.

`load()` does not return the column. It derives `interrupted` as
`hasPendingToolCall(last) || (header.interrupted && hasUnfinishedTurn(active))`, and
`hasUnfinishedTurn` is false for an empty transcript (`CodingSessions.kt:569-576`); worse, the read
of a conversation that derives as not-interrupted **clears the column** on the way out
(`:580-582`). The test set the flag on a conversation with no messages, so the value it asserted on
could never survive, whatever the binding did. The product is right: a flag with nothing to resume
means nothing, and crash recovery depends on that.

**Fix:** store one user message before setting the flag, so the read goes through the derivation.
Both assertions are unchanged; the stale-pointer call must still throw and still must not clear
first's flag — and now it is a real test, because an unbound `markInterrupted(false)` would clear
the flag of the session that is active (first) and the assertion would catch it.

#### CI-4 — `ae7fb58`: the pointer moved before the write it was meant to race

`ChatViewModelTest > aTurnNeverWritesIntoAConversationItDidNotStartIn`, `:1564`: *"the turn keeps
writing to the conversation it started in expected:<[Keep working]> but was:<[]>"*.

`startTurn` sets `streaming = true` **synchronously** (`ChatViewModel.kt:484`) and only then
launches the turn, whose first durable write is at `:634` after several suspensions. Waiting on
`it.streaming` therefore returned before the write; the test moved the storage pointer first, and
the turn's very first bound write was correctly refused. A turn that has written nothing has
nothing filed in the conversation it started in — refusing is exactly S1's contract, so the test
was racing the write it meant to protect.

**Fix:** wait for `acceptedMessageCount == 1`, the state update published immediately after
`persistTurn` returns (`:651-661`), so the pointer moves while the turn is inside the provider call.
Assertions are unchanged: the other conversation stays empty, the origin conversation keeps its user
message, and the refused write still surfaces as an error (`stableFailure`, `:1244-1269`, is reached
through the generic catch at `:982-986`).

#### CI-5 — `86d80cf`: a pending title retried into the next case

`ConversationTitleLifecycleTest > titleProviderFailureAndInvalidOutputNeverFailCodingTurn`, `:188`:
*"expected null, but was:<The provider returned an empty response.>"*.

The test loops three title-failure payloads over **one** database. F5 (`1b2d8bf`) deliberately made
a failed title attempt leave its marker pending, and added a startup retry for a pending marker
(`ChatViewModel.kt:311-326`). So case 2's fresh `ChatViewModel` spends provider call 1 retrying case
1's marker, and the coding turn receives call 2 — the invalid payload written for the title request.
An empty assistant reply is precisely what `finish()` reports as "the provider returned an empty
response" (`:1111-1116`). That the observed text is *that* string, and not a provider-refusal one,
is the corroboration: case 1 passes because its store is clean, and the empty-response case is the
one where the coding turn is fed `""`. (The same fixture would also have tripped
`assertEquals(2, calls)`, because the retried coding turn starts a third title request; the
assertion at `:188` fails first.)

The behaviour that broke the fixture is the F5 contract the order required — a failed title attempt
never fails or errors the coding turn, and its marker is retried — which
`aFailedTitleRequestLeavesTheMarkerForTheNextFinishedTurn` pins independently, and which passed in
this run.

**Fix:** each case gets its own store, exactly as `@Before` does for the test as a whole
(`app.deleteDatabase`, `AgentSessionStore(app).clear()`). All four assertions are unchanged,
including "no error", "still called New session", "two events", "two provider calls".

#### What this round does and does not establish

It establishes that the three named pins were testing the right contract on fixtures that could not
observe it, and that no product defect surfaced under 742 executed tests. It does **not** establish
that the fixes pass: nothing here can compile or run Kotlin, so CI round 4 is their first execution,
and if a fixture fix is wrong the run will say so. The rest of the suite has now executed once and
passed; the three device-only checks in this record still have no runtime evidence.

### Device-feedback addition (kaiser, 2026-11-02) — not a defect

Asked for on the green head `192c56b` (both workflows passing, 742/742 tests). One change, in the
chat thinking UI: **tapping the thinking box itself collapses it**, because a long chain pushes the
Show/Hide header off the top of the screen and collapsing meant scrolling back up to it.

**Where.** One composable: `ThinkingBlock` (`ChatScreen.kt:1035`). All three call sites go through
it — the live block (`:817`, `streaming = true`), the standalone finished block (`:806`) and the
finished block on an assistant message (`:983`) — so the streaming and finished blocks got the
behaviour from one edit, which is why "both" needed no second change.

**What changed.** The expanded chain's `Surface` gained

```kotlin
Modifier.clickable(
    interactionSource = interactions,   // remembered in the composable
    indication = null,                  // no ripple across a wall of text
    role = Role.Button,
    onClickLabel = "Collapse thinking",
) { expanded = false }
```

plus one import and one `remember { MutableInteractionSource() }`. Nothing else in the file moved:
the header row's clickable (`:1047`) is byte-identical, so Show/Hide toggles as before, and a tap on
it toggles exactly once because the inner target consumes the event before the box's target would.
The box is only composed while `expanded`, so its tap is always a collapse; expanding still goes
through the header, which is a full-width target already.

**How the tap-versus-drag split is implemented — the acceptance test.** There is no custom gesture
detector, and that is deliberate. `Modifier.clickable` fires only when the press is released with
its pointer changes unconsumed, and it re-checks on the **Final** pass — after the chat list's
scroll container has seen the same event. A drag starting on the chain therefore has its position
changes consumed by the list's scrollable, which cancels the press, so the drag scrolls exactly as
it did before and never toggles. Movement under the touch slop is a tap by the platform's own
definition, which is what "press and release without dragging" means. The chain carries **no nested
scroll** — it is a plain `Text` inside the Surface, no `verticalScroll`, no selection container (the
message-row `SelectionContainer` is a sibling, `:1011`) — so nothing can compete with the list for
the gesture. Had there been a nested scroller, the list would still have won only for drags starting
outside it, which is why the absence is stated rather than assumed.

**Untouched, as required.** The F6 preview path (`thinkingPreview`, `PreviewScan`,
`remember(text, streaming)` — `:1040-1044`), the thinking clock (`LiveStatusRow`, `:884`), the Undo
row, and every other composable. The diff is one import, one remembered interaction source, one
modifier on one Surface, and the comment explaining it.

**Verification, stated plainly.** No Compose UI test harness exists in this project, so nothing here
can prove a gesture. CI round 5 covers compilation and that the other 742 tests stay green — it
cannot see this behaviour at all. The tap-versus-drag split is proven by `clickable`'s documented
cancellation and by **kaiser's device check**, and by nothing else in this record.

### Device-found defect (kaiser, 2026-11-02) — the thinking clock restarted on fold

**The finding, verbatim in effect.** On the `192c56b` build with the tap toggle (`5f68178`) on top:
during a running turn the live status line read `Thinking · 2m`; kaiser tapped Show on the thinking
block, then Hide, and it read `0s`.

#### Root cause

The start was created inside the live-status list item, not with the turn:

```kotlin
// ChatScreen.kt:885, before the fix
val startedAt = rememberSaveable { SystemClock.elapsedRealtime() }
```

That row is one `item(key = "live-status")`, emitted on `showLiveStatus = streaming` (`:822` and
`:730` in the pre-fix file). So the value's identity was **the row**, and its lifetime was that item's
composition. Expanding a two-minute reasoning chain makes the item leave the viewport, the
`LazyColumn` disposes it, and when it comes back the only path for the value is the item's
save/restore. It came back initialised — and the initialiser is `SystemClock.elapsedRealtime()`,
i.e. **now**, which renders as `0s`. Nothing else in the path can produce a reset: `elapsed` is
recomputed from `startedAt` on every tick (`:889` pre-fix), so a correct restore shows the right
number within a second; `startedAt` is the clock's only input. *Why* the restore missed is
Compose-internal and deliberately not claimed here — the point of the fix is that nothing depends on
that answer any more. The comment that stood above the old code shows the disposal was already known as the risk this
`rememberSaveable` was meant to survive; the device says it does not.

**This record had already asked this exact question.** F7 item 2, "the saveable clock with no turn
key", was recorded DEVICE-ONLY, and residual 4 said the reason it could not be settled here was
`LazySaveableStateHolder`'s dispose rule with no Compose sources on this host to read it; the settling
observation was "run a turn ~20 s, start a second immediately, does its line open at ~20 s?". The
device answered the disposal half from the other side, and both questions are now moot: after the fix
the row holds no state to lose or inherit.

#### The fix — `3051632`

`ChatUiState.turnStartedAt` (`ChatViewModel.kt:121`), written at `:489` **in the same state update
that sets `streaming = true`**, read once at `ChatScreen.kt:732`, and passed into
`LiveStatusRow(label, startedAt)` (`:890`), which now only ticks.

**Why this home.** The turn is the ViewModel's concept — `streaming`, `generation` and the turn ticket
all live there — so the turn's start is a fact of the same kind, not a property of a row that renders
it. Composition cannot lose what it does not hold: no disposal, relayout or re-parenting can touch it.
And because `startTurn` is the single place a turn begins, it is also the single place the clock is
set, which is what makes direction 2 structural rather than remembered. The alternative — a turn-keyed
`rememberSaveable` in `ChatScreen` — is still composition state, would still be dropped by the same
class of event, and would encode "this turn" as a boolean instead of holding the turn.

#### The acceptance pair, direction by direction

1. **No toggle resets it.** Tapping Show/Hide changes `expanded` (`ThinkingBlock`, `ChatScreen.kt:1043`)
   and a box tap (the same state, `5f68178`); neither is an input to the clock. The clock's input is a
   parameter the row receives from state, so even when the relayout disposes and rebuilds the row —
   the exact event that produced the bug — the number is recomputed from the turn's unchanged start.
2. **A new turn starts at 0 and inherits nothing.** `SystemClock.elapsedRealtime()` is written in
   `startTurn` (`:489`) for every turn, and in no other place. The row is removed from the list
   whenever `streaming` is false (`ChatScreen.kt:732`), so there is no row state that could carry
   between turns, and the value itself is replaced at the start of the next one.

**Pinned by a unit test**, `ChatViewModelTest.theTurnClockHoldsWithinATurnAndRestartsForTheNext`: the
start equals the monotonic clock at turn start; advancing the Robolectric clock 5 s underneath a
running turn does not move it; the next turn's start is strictly later. It is the only execution
available on this host, it takes the suite to 743, and CI round 5 is its first run.

**What remains device-only.** Everything about the screen: that a Show/Hide or box tap never resets the
number while a turn runs, and that a drag on the chain never collapses it (`5f68178`'s acceptance).
The unit test pins the state contract; it cannot see composition.

#### Audit of this fix and the tap toggle together

| Check | Verdict |
|---|---|
| `LiveStatusRow` call sites | one (`:825`); the whole signature change is contained |
| `turnStartedAt` writers | one (`:489`). The two full-state rebuilds (`:1393`, `:1486`) leave it null and can only run with no turn streaming — `changeSession` joins `sendJob` first (`:1471`) |
| `streaming = true` writers | `:489` (turn start) and `:660` (same turn), so "streaming implies a start" holds in every state |
| other elapsed clocks in the app | none (`grep elapsedRealtime` in `app/src/main` returns only these) |
| state left in the row | none — `remember(startedAt)` for the initial value, the tick keyed on the same parameter |
| **count vs item** | **found and fixed** (`31f0895`): the item needed a start while `totalItems` still counted `streaming` alone. Unreachable today, but `totalItems` feeds the auto-scroll target, so a drift would scroll to an index with no item. Both now read one `liveStatusStart` |
| `ThinkingBlock` call sites and ids | three call sites, one composable. `expanded` ids are `"streaming"`, `"thinking-<index>"`, `"thinking-<index or group id>"`; `rememberSaveable(input)` resets on an input change, so a finished block's collapse reverts when the message index moves — the documented F7-1 behaviour, unchanged by either commit |
| live block's `expanded` across turns | keyed by the constant `"streaming"`, so a new turn's live block restores the previous turn's flag. Pre-existing, unchanged; the default is collapsed and neither commit makes it sticky |
| box tap vs the row's long-press and selection | the block is a sibling of the message row's `combinedClickable` (`:1004`) and of its `SelectionContainer` (`:1011`), so no tap is seen twice and selection mode is untouched |
| nested scroll in the chain | none — a plain `Text` in the `Surface`, so the list keeps every drag |
| F6 preview path | untouched by both commits: the diff over `thinkingPreview` and `PreviewScan` is empty, and it is still `remember(text, streaming)` |
| accessibility | the box is a second focus stop labelled "Collapse thinking" beside the header's Show/Hide; both toggle the same state, the label is accurate in every reachable state, and there is no focus trap |
| long press on the chain | fires the collapse on release; it did nothing before, the constraint was about drags, and no long-press action existed to preserve |

No other defect found. Nothing here was executed: CI round 5 is the first run of the new test, and
kaiser's device check is the only evidence for the gesture and for the clock on screen.

### Device-found defect 2 (kaiser, 2026-11-02 10:16 IST) — the status row half out of the list

**The finding.** On the `192c56b` build, while a turn streamed with the screen full of text, the
live-status row (`Thinking · 12s`) rendered with its lower half cut off at the bottom edge of the
chat list. Kaiser's guess was the auto-scroll; he was right, and the cause is two code facts.

#### Root cause

1. **The jump bottom-aligned to the viewport, not to the content.** `scrollToConversationBottom`
   (`ChatScreen.kt:970-977` before the fix) did `scrollToItem(index)` and then, for the item it wanted
   at the bottom, `offset = item.size - viewport` and `scrollToItem(index, offset)`. That places the
   item's bottom exactly at the viewport's bottom, which scrolls the list's own bottom content padding
   (`PaddingValues(bottom = 20.dp)`, `:794`) past the edge. The live-status row therefore ended up
   **flush against the list's edge with zero room under it**.
2. **The follow trigger counted characters, not layout.** The effect keyed on
   `streamingText.length / STREAMED_SCROLL_STEP` and the reasoning equivalent (`:779-782`, the constant
   at `:668`). A wrap is a layout event: the text row grows by a line height while the character count
   stays inside its bucket, so no scroll was scheduled. With (1) removing all slack, one wrap was
   enough to push the row's lower half below the edge — and if the stream then stalled, which is what
   `Thinking · 12s` looks like, nothing corrected it until 24 more characters arrived. **The clipped
   state was the settled state**, which is precisely what a screenshot catches.

**Did `31f0895` change this path?** No. It changed the *expression* for `totalItems`
(`showLiveStatus` → `liveStatusStart != null`) and the item emission (`liveStatusStart?.let`). Count and
emission were equal before it and are equal after it, so the index this effect targets and the rows it
emits are identical. The clipping therefore exists at the current head **for the same reason**, not a
different one; the head also carries `5f68178` (tap toggle, `ThinkingBlock` only) and `3051632` (clock
start moved out of this row, no layout or scroll change), so neither could have masked or caused it.
Kaiser's `192c56b` has the same scroll code, byte for byte.

#### The fix — `b06145d`

- `scrollToConversationBottom` now bottom-aligns to the **content's** end:
  `offset = item.size + layout.afterContentPadding - viewport`. The row keeps the list's 20 dp padding
  under it instead of sitting on the edge.
- Rows appearing still land exactly, through the same count-based jump; the two length-bucket keys and
  `STREAMED_SCROLL_STEP` are gone with the trigger they served.
- Growth is now followed from the **layout**: a `snapshotFlow` over
  `last.offset + last.size + afterContentPadding - viewportEndOffset`, and while following, a
  `scrollBy` of exactly that gap. It settles on the content's end, so the last row is fully visible;
  it fires on the layout that actually grew, so a wrap is followed and a stall cannot leave drift
  behind.

**What deliberately does not change.** A reader who has scrolled up has `followBottom = false`, and
the settle returns before it scrolls — the position stays theirs, exactly as today. A gesture in
progress is skipped outright (`listState.isScrollInProgress`), which is stricter than the old effect
but never looser. `followBottom` is still written in exactly one place, the near-bottom updater. The
jump and the settle agree on where "the end" is — both read `afterContentPadding` — so they cannot
fight each other.

#### Audit of the change and its neighbours

| Check | Verdict |
|---|---|
| count vs emitted items | still one-to-one: `timeline.size`, the two streaming rows, `liveStatusStart`, `showActivity`, `showInterruptedTurn`, notice, error, unsupportedReasoning — nine terms, nine emissions |
| `unsupportedReasoning` as a trigger key | **absent from the structural effect, and it was absent before this change too** (pre-existing). Covered now by the settle: a row appended below the viewport leaves a gap of at least the 20 dp padding, so the settle reveals it in two steps. No code change |
| structural effect and settle both landing on the end | consistent: both read `afterContentPadding`; after a jump the gap is 0, so the settle is silent |
| jump's stale `layoutInfo` read after `scrollToItem` | pre-existing; the early return is harmless because `scrollToItem(lastIndex)` already clamps to the content end, and the settle corrects anything left |
| can the settle loop | no: `snapshotFlow` emits only on change, a consumed scroll leaves gap 0, a clamped one changes no layout, so at most one extra emission |
| content shorter than the viewport | gap negative, no scroll, no behaviour change |
| nested scrollers in the screen | two, both outside this path: the empty-state card (`:615`) and a horizontal row inside markdown (`:1183`); neither is vertical inside the list |
| viewport height changes (undo row appearing, keyboard rising) | the settle re-aligns while following; that is the same class as the report and the reason it is included |
| the clock row | untouched by this commit — `round3/turn-clock-check.py` still passes |
| the three `ThinkingBlock` call sites, tap target and header | untouched — `round3/thinking-tap-check.py` still passes |
| `STREAMED_SCROLL_STEP` references | none left in the source; the only mention is the earlier section of this record that described the old design, left as written |

**Device-only.** This is a scroll path with no Compose test harness. CI round 5 can only confirm it
compiles and the 743 unit tests still pass; that the row is fully visible while streaming, that a drag
still scrolls, and that a reader who scrolled up is left alone are kaiser's checks.

## Final pre-merge audits (kaiser, 2026-11-02)

Two audits of the whole app, then fixes. **The limit, stated once and meant:** there is no
profiler, no device and no JDK on this host, so audit two is static. Findings are ranked by the
user-visible impact the code makes arguable, the hot streaming path first because that is what
kaiser feels on device, and device feel itself remains his check.

### Device-found fix round (kaiser's ruling, 2026-11-02 11:25 IST) — the context wall

His device test of the long Minecraft session ended at the refusal banner: *"Rivet compacted this chat
as far as it could, and it still does not fit the 115,072-token budget Rivet planned for this model
(no context window is known for it). Your messages were kept. Start a new conversation to keep going."*
His ruling, in substance: the copy is incomprehensible to a non-technical user, the budget is far too
small, and telling someone to move sessions after hours of work is a rage-quit dead end in an app made
for long tasks. All three were right, and the third was a defect, not a limitation — see the third fix.

**What actually limits continuation, read before touching anything.** `ContextBudget.assess` derives
`allowedInputTokens` from the window: a window that was entered or detected is used as it is, and with
neither, Rivet's 256K default is a *guess*, planned at `GUESSED_WINDOW_PLAN` (half of it) minus a 16K
reserve — 115,072. From that ceiling it derives `compactAtTokens` (trigger) and `compactToTokens` (aim)
by two margins of `target/8`. `ContextPreparation.prepareChecked` then prunes old tool results, stubs
them aggressively, and finally asks `AgentContext.plan` for a retained tail that fits a byte target
derived from `compactToTokens`, summarising the removed messages into the task state. `noHeadroom`
refused whenever the request was already at the ceiling and the projection had not reached the *aim*.

**FIX 1 — the copy (`900abb8`).** No numbers, no "budget", no "token", no "context window", no
parenthetical about unknown windows, no "start a new conversation":

- *"This chat is too long for the model to read all at once, even after Rivet shortened it. Your
  messages are safe. Try again, and if it keeps stopping here, remove the longest message."*
- *"The newest message is too big for this model to read. Your earlier messages are safe. Shorten it
  and send it again."*
- the session cap: *"This chat is full. Your messages are safe. Start a new chat to keep working."*

The two copy functions lost their number parameters, so a count cannot drift back into the sentence,
and the formatting and imports that built it are gone. `ContextRefusalCopyTest` pins the three
sentences and asserts digits and the banned words cannot return;
`round3/refusal-copy-check.py` rebuilds the sentences from the Kotlin literals and checks the same
rules without a JVM.

**FIX 2 — the budget (`b5c5a47`).** A guessed window is planned at 220K of the 256K instead of half:
`allowedInputTokens` = 220,000 for the default guess, with the trigger and aim margins keeping their
shape. Measured windows — entered or detected — never enter that branch and behave exactly as before.
Trade-off, recorded as kaiser accepted it: a model whose real window is smaller than the guess now
fails at the provider instead of at Rivet's polite wall; the dead end at half was worse. The pinned
numbers in `ContextBudgetTest` are updated as a stated contract change.

**FIX 3 — the dead end (`f56a2cb`).** The conversation now continues in place. A plan is built against
the ceiling as well as against the aim, and either is sent when it is sendable: fits the ceiling, and
smaller than the request it replaces so a retry is never the same request twice. That rule is one
named function, `projectionCanBeSent`, used at every acceptance site, and pinned by
`aProjectionThatFitsTheCeilingIsSentEvenAboveTheComfortableTarget` — which goes red if the comfortable
margin ever becomes a condition again. Nothing is deleted: `commit` changes only the durable active
projection, the stored transcript and scrollback are untouched, and the removed messages are carried
by the summary. "Start a new conversation" is no longer the answer to a long chat.

**The hard blocker that remains, named rather than papered over:** when the request's *minimum*
projection — fixed text, the newest message and the latest user turn — already exceeds the ceiling,
there is nothing to shorten and nothing to summarise into room, so the turn is refused before the
provider (`ContextCapacityTooSmall`, pinned by `aRequestTooLargeToFitIsRefusedBeforeTheProvider`). That
is a genuinely unsendable request, not a dead end: the new copy tells the user which message to
shorten. It is reachable only when a single message or tool result is larger than the whole budget.

**Limit of this round:** no device and no JVM here, so the "previously refused, now continues" path is
pinned at the rule and not end-to-end — reproducing the exact device transcript needs estimate
arithmetic this host cannot run. CI runs the suite; kaiser's device pass on this flow is what the merge
waits for.

### Audit two — performance and allocation

| # | Finding | Fix |
|---|---|---|
| P-1 | every published delta re-parsed the **whole** answer: split every line, rebuild every block, re-join every block's text — O(answer) per publication, O(n²) per reply | `4da714d` |
| P-2 | `displaySafeText` built a new string of the value's length on **every** call, including clean text; the streaming path hands it the whole answer per publication and the whole thinking chain per reasoning delta | `1e52764` |

**P-1 — incremental block parsing (`4da714d`).** `AssistantMarkdown` called
`parseAssistantMarkdown(text)` per publication. `MarkdownBlocks` now keeps the blocks before the
last pair and re-parses from there. Two blocks, not one, because the final line may not have
settled: `- a\n-` is a list plus a paragraph, and one more character makes it a single list of two
items, so the last block can be absorbed by the one before it. Anything that is not an extension
of the previous text (a shorter text, a retry, an edit) takes the full parse. **Internal change:**
work per publication falls from O(answer) to O(tail). **Why behaviour is identical:** the value is
the same list of blocks, in the same order, with the same text; nothing renders differently, and
unchanged blocks keep their instance so the already-memoised `InlineMarkdown` work stays cached.

Proven, not asserted: `ChatMarkdownTest` walks every prefix of ten documents (every block kind,
blank runs, an open fence, CRLF), grows each prefix by more text, and compares with the full parse;
`round3/markdown-incremental-check.py` transcribes both implementations and checks the identity over
**341,635 comparisons across 5,010 documents** plus random replacements — 0 mismatches. That check
earned its keep: the one-block window failed **1,977 of 20,094** comparisons on a case the eye does
not see (`- one\n-` then a space), which is why the window is two blocks.

**P-2 — no copy when there is nothing to escape (`1e52764`).** `displaySafeText` returned
`buildString(value.length) { … }` unconditionally. It now scans, returns the same instance when no
bidi control is present, and takes the previous escaping body only when one is. **Internal change:**
zero allocation for clean text, which is every streamed delta. **Why behaviour is identical:** the
returned value is character-for-character the same in both paths; only the instance differs, and
callers only read it. `ChatPresentationTest` keeps the escaped-output assertion and adds that a
clean string comes back as the same instance.

**Residuals from this audit, deliberately not fixed** (each would change behaviour, which the order
forbids):

- **P-R1 — reasoning deltas publish unconditionally.** `ChatViewModel.kt:695-696` does
  `reasoningPreview.toString()` and a state update per delta; the answer path is windowed at 80 ms
  (`PreviewWindow`), reasoning deliberately is not, for the reason recorded at `:690-693`. Cost: a
  30 k-char chain republished ~15 times a second is ~450 KB/s of short-lived strings and a
  recomposition per delta. A fix needs a window plus a flush at the phase boundary, which changes
  the preview cadence.
- **P-R2 — an expanded chain re-lays-out its whole text per publication**
  (`ThinkingBlock`'s `Text(displaySafeText(text))`). Inherent to showing it; throttling would be a
  behaviour change.
- **P-R3 — `persistTurn` re-encodes the whole transcript per message** (`onMessage`), O(n) per
  message rather than per delta. That is the durability design — each message is durable before it
  is shown — so changing it changes crash semantics.

**Checked and left alone:** `timeline` is memoised on `(messages, toolLifecycle, messageReasoning)`
(`ChatScreen.kt:674`); `InlineMarkdown` memoises both `parseInlineMarkdown` and the
`AnnotatedString` on their inputs (`:1201`, `:1204`); the thinking preview is incremental
(`PreviewScan`, F6); the answer preview is windowed at 80 ms; `nearBottom` is a `derivedStateOf`;
`LiveStatusRow` ticks once a second and recomposes only itself. List items are keyed where they can
be inserted (`streaming-*`, `live-status`, `interrupted-turn`) and index-keyed only for the
append-only transcript, where indices do not shift under them.

### Audit one — correctness, area by area

All 85 files under `app/src/main` were read across five parallel area passes (chat, agent+storage,
provider, runtime+workspace+updates, UI shell), each asked for findings it could point at with a
line and an execution path. **Every candidate was then re-derived from the code here before a fix
was made; six survived that and are fixed below. The rest are recorded as residuals because I could
not re-derive them inside this run** — that is the limit of this pass, not a verdict that they are
false. A residual that ships unfixed is a known unknown; a "fix" built on an unverified reading is
a second bug.

**Fixed (each re-derived from the code):**

| # | Defect | Commit |
|---|---|---|
| C-1 | in-stream rejections read billing words before capacity words, so a per-minute quota error was reported as a usage limit — the reverse of `httpError`'s documented rule (`Http.kt:118-123` vs `:137-141`) | `13c84e3` |
| C-2 | `sse` relabelled anything its payload callback threw as `InvalidResponse("malformed stream event")`, so a context-preparation abort accused the provider of unreadable output and took the wrong recovery path (`Http.kt:362`, `:379`) | `2d84400` |
| C-3 | Retry was offered on `ContextUnavailable`, `ContextTooSmall` and `SessionLimit`, which re-run the same transcript against the same window and can only reproduce the stop (`ChatViewModel` error action) | `24ed5e7` |
| C-4 | a 300-unit cut could split a surrogate pair, rendering a replacement glyph in the provider-said banner (`ProviderMessageText.kt:15-16`) | `fab9c9e` |
| C-5 | `save()` called `store.setActive()` outside its try/catch: a failing DataStore write escaped an unguarded `viewModelScope.launch` as an uncaught exception after the provider had already been saved (`ProvidersViewModel.kt:572-584`) | `2f16c37` |
| C-6 | the "open Processes" launch intent was consumed only from the live `Intent`, so every recreation redelivered it and forced the user back to Processes (`MainActivity.kt:17-19`, `:37-43`) | `88338c3` |

**Checks I ran across the whole source myself, which found nothing:** every `!!` and unchecked
`first()/last()/single()/requireNotNull()` in `app/src/main` — all guarded (the `single()` in
`SafWorkspace.resolve` sits behind a size check at `:419-421`; `ProvidersViewModel`'s `first()`
calls behind `isEmpty` checks at `:391`, `:436`, `:687`; `UpdatesViewModel:81`'s `requireNotNull`
after a `verify()` that throws instead of returning null; `Http.kt:90`'s behind an `in` check).
And all 79 `catch (…: Exception)` blocks traced for swallowed cancellation: every hot path rethrows
`CancellationException` first (`AgentLoop.kt:105-114`, the turn catch in `ChatViewModel`, and
`stableFailure`), and the remainder are cleanup/close paths or non-suspend code.

**Residuals — reported by the area passes, not verified here.** Ordered by the severity claimed,
with the mechanism exactly as reported so the next pass can start from the line:

1. **Undo may delete every `.gitignore`d tree** (`node_modules`, `build`, `dist`) from the real
   project: `TurnCheckpoint.kt:180-192`, `:229-233` → `:126-157`, `WorkspaceMirror.kt:270-281`,
   `:158-170` — `scan()` skips ignored subtrees in manifest and `before.zip`, `installCheckpoint`
   swaps that stage in for the whole of `current/`, and the post-undo sync (whose snapshot has no
   ignore filter) then deletes the paths the baseline still lists. *If true this is the most
   serious line in this record and needs a device test before merge.*
2. **An Undo whose post-restore sync returns `Conflict` is neither undone nor marked desynced**
   (`RuntimeController.kt:165-176`), so the row is offered again, every retry fails, and the
   workspace stays `sync_required` with only "discard" as an exit.
3. **A failed SAF write leaves the project file truncated and never repairs it**
   (`SafWorkspace.kt:223-247` truncates before writing; `WorkspaceMirror.kt:212-222` records
   `Failed` with no baseline, after which the path is treated as an external conflict).
4. **An undecodable legacy transcript is converted to "no history" and the migration marked done**
   (`AgentSessionCodec.kt:38`, `AgentSessionStore.kt:44-59`, `CodingSessions.migrate`), with no
   quarantine copy and no retry.
5. **Saving a provider deletes custom-header ciphertext that could not be decrypted**
   (`ProviderStore.kt:147` drops it from the decoded view; every write rebuilds the list from that
   view), which is exactly the recovery the migration code promises to keep.
6. **`ResponsesAgentStream` accepts a stream cut after a text item as complete** and returns a
   half-written function-call argument as a tool call (`ChatGptPlanClient.kt:201-203`, `:293-305`),
   with no validation, unlike the other three accumulators.
7. **`recoverInterrupted` leaves the visible history and the model's context disagreeing** when the
   synthetic interrupted result does not fit: the row stays in `events` while `active_events` drops
   it and the pending call (`CodingSessions.kt:596-616`).
8. **The reasoning-effort classifier can claim a generic 400 that only names the model**
   (`Http.kt:233-244` + `ChatViewModel.kt:974-983`), discarding the provider's sentence and
   persisting the level the user then picks.
9. **Free-tier setup saves the provider before resolving a model** (`ProvidersViewModel.kt:180-215`),
   so a failed fetch reports failure for a save that happened and each retry adds another provider.
10. **"Fetch models" persists the editor's uncommitted edits** for an existing provider
    (`ProvidersViewModel.kt:684-712`), so Cancel does not cancel.

**Lower-confidence suspects, recorded not acted on:** the unbound `markInterrupted` in the
`AgentSessionLimit` handler (`ChatViewModel.kt:929`); `selectProject` not bumping `generation`
against a late `finishCheckpoint`; `ManagedProcesses.stopAll` not finishing a preview entry when
its stop throws; an update download that cannot be cancelled; a staged reasoning change lost on
recreation; a `configs.collect` that ends for the session on one failure; nested
`limitedParallelism` in `SafWorkspace.search`; and the ChatGPT-plan model-listing shape.

## END OF RUN — nothing half-done

Done on this branch, in order: S1 (`bb5d149`), S2 carry (`50f21e1`), markInterrupted binding
(`96ac827`), F1 (`83febf3`), F2 (`295f61a`), F3 (closed, no code), F4 (`5209bc8`), F5 (`c4dbe7f`,
test `40a88bd`), F7 items 1-3 settled (`d156e48`, fix `327ff50`, `4ceceeb`), F6 pins (`859c601`)
and F6's cost cut (`952370f`), S2 rehydration + row tests (`c30b008`), then this audit:
`0db23a5`, `b4b7206`, `6e26d94`, `2f9f5c4`, then Hermes' two closing fixes `fbb152f`, `4e677da`,
the sixth provider-screen site `8e4b97d`, the two compile breaks CI found `0b28b03` and
`acbc563`, the three test fixtures CI's first full run found `e83d218`, `ae7fb58`, `86d80cf`,
kaiser's device-feedback addition `5f68178`, the device-found clock fix `3051632`, the audit
fix `31f0895`, the device-found scroll fix `b06145d`, then the final pre-merge audits: performance
`4da714d` and `1e52764`, correctness `13c84e3`, `2d84400`, `24ed5e7`, `fab9c9e`, `2f16c37` and
`88338c3`, and the compile error CI found on `6bf3313` in the C-2 fix, repaired in `3a55247`.

**CI-found fix on the audit round (`3a55247`).** The first CI run over these commits
(Android Build 41393230961 on `6bf3313`) failed to compile, with one error in the C-2 fix:

```
app/src/main/java/com/kaiser/rivet/provider/Http.kt:398:91
Argument type mismatch: actual type is 'kotlin.Throwable?', but 'kotlin.Throwable' was expected.
```

`resumeWithException(e.cause)` — `getCause()` is nullable, and the wrapper is only ever built with a
cause, so the compiler was right and my type was the optimistic one. Fixed by handling the null
instead of asserting it away: `e.cause ?: e`. The wrapper is the same failure (built from the
thrown exception, carrying its message and stack), so the caller still gets an error rather than a
dropped one — which is what C-2 exists to guarantee. No `!!`: a null on the path whose job is to
stay careful must not become the crash.

**CI round 2 on the audit fixes — run 41393672361, head `a7b6365`: 746 tests, 1 failed. Verdict: the
test was wrong, not the code.** The failure was
`HttpBridgeTest.malformedStreamEventFailureResumesWithProviderError`, ending in
`java.lang.IllegalArgumentException: bad field type` thrown by the test's own fake.

Ruled in (a), the test pinned the pre-`2d84400` contract, and ruled out (b), a hole in the fix, from
the path:

1. The callback is invoked at `Http.kt:374`, and that line is inside the `try` opened at `:334` and
   closed by the catches at `:391-408` — it is inside the guarded region, not outside it.
2. A callback throw is caught at `:377` and rethrown as `StreamCallbackFailure`, which the chain
   catches at `:397` and resumes the continuation with `e.cause ?: e` — the caller's own exception.
   So the stream *does* resume with an error; it cannot hang, and it cannot be dropped.
3. The CI stack is the original exception's **throw-time** stack: the fake at
   `HttpBridgeTest.kt:110-111` called from `Http.kt:374`. That is the fingerprint of a preserved
   error, not of an escape — `resumeWithException` carries the exception object, and its stack was
   captured where it was thrown.
4. The test's `catch (_: ProviderError.InvalidResponse)` at `:114` therefore cannot match. The
   original `IllegalArgumentException` surfaces from `runBlocking`, which is exactly what the JUnit
   XML records.
5. Those first four points describe the behaviour `2d84400` was written to produce. The old test
   asserted `InvalidResponse("malformed stream event")` for a callback failure, which is the
   relabelling that commit removed.

The test is corrected to pin the new contract in its three parts, and nothing weaker: the caller's
error reaches it **as itself**, it is **not relabelled** (`assertFalse(thrown is ProviderError)`),
and it is **not lost** (a lost error leaves the continuation unresumed and fails the two-second
budget instead of passing). The test count is unchanged at 746 — one test replaced, none added, none
removed. No production code changed in this commit.

*Round 3 corrected the first of those three: "as itself" was pinned as `assertSame` on the instance,
and the coroutine library does not preserve the instance. See the next entry.*

**CI round 3 — run 41394255113, head `c962c77`: 746 tests, 1 failed, the corrected test, on its own
`assertSame`. Verdict: the pin was wrong again, and the copier is named below.**

```
java.lang.AssertionError: the callback's own error must reach the caller as itself
  expected same:<java.lang.IllegalArgumentException: context_summary_limit>
  was not:<java.lang.IllegalArgumentException: context_summary_limit>
```

Same type, same message, different instance — so something between the throw and the caller copies
it. Where it is, ruled from the code:

- **Not the bridge.** `StreamCallbackFailure` is `Exception(cause)`, which stores the reference;
  `e.cause ?: e` at `Http.kt:403` hands that very reference to `resumeWithException`. Nothing in the
  chain constructs a new exception out of the original.
- **Not OkHttp.** The failure never leaves `onResponse`: it is caught at `Http.kt:377`, resumed at
  `:403`, and the callback returns normally.
- **Not the fixture.** MockWebServer serves bytes; the exception is thrown by the test's own lambda.
- **The coroutine library.** Code path: `resumeWithException` resumes a suspended coroutine with a
  failure; when that resumption crosses a dispatcher, kotlinx resumes through `DispatchedTask` and
  applies stack-trace recovery — `recoverStackTrace` → `tryCopyException`
  (`kotlinx/coroutines/internal/StackTraceRecovery.kt`), which builds a **new** exception through the
  class's `(Throwable)` copy constructor, preserving type, message and cause. `IllegalStateException`
  and `IllegalArgumentException` both have that constructor. Recovery is on by default
  (`kotlinx.coroutines.stacktrace.recovery`, default `true`), and this test crosses two dispatcher
  boundaries: `runBlocking`'s event loop resuming `sse`'s `suspendCancellableCoroutine` from the
  OkHttp thread, and `withTimeout`'s timer resuming the outer coroutine. Version in the tree:
  `kotlinx-coroutines-test:1.9.0` (`app/build.gradle.kts:111`).

So the copy is the library's, applied to every non-`CancellationException` that is resumed across a
dispatcher — and the test demanded the one property it does not preserve.

**What the contract actually needs** — the abort this exists for, with both ends:
`ContextPreparation.kt:249` and `:260` throw `IllegalStateException("context_summary_limit")` from
inside the delta lambda handed to `client.streamAgent`, which is the shape the test imitates.
Downstream, `ContextPreparation.kt:62-67` catches `Exception` and keys the recorded failure code on
`e.message` against a fixed set of codes — **the message is the contract** — and `AgentLoop.kt:106-114`
matches by **type**, with `catch (e: ProviderError) { throw e }` ahead of `catch (_: Exception)`, so a
relabelling is what sends the abort down the provider branch instead of `ContextUnavailable`. Nothing
in the app stores the exception or compares identity.

**Fixed on the test side, in `c962c77`'s successor:** the pin is now the honest contract — same type
(`thrown is IllegalStateException`), same message (the failure code), **not** a `ProviderError`, and
**not lost**. It also now throws the real production type and message instead of
`IllegalArgumentException("bad field type")`, which exists nowhere in the app, so the test sits
closer to the path it protects than the version it replaced. It still fails if the error is
relabelled (type assertion and `is ProviderError` both fail) or dropped (`withTimeout` surfaces a
`TimeoutCancellationException`, which is not an `IllegalStateException`). Test count unchanged at
746.

*What I cannot do here: print the received stack trace to show the copy's fingerprint, because a
passing test records no trace and there is no JVM on this host. The copier is identified by mechanism
and version, not by observing the copy — the confirming one-liner for anyone with a JVM is that
`thrown.stackTrace` points into `kotlinx.coroutines` rather than at the throw site.*

**STOP POINT — this run stopped where kaiser's standing order says to stop rather than rush.** The
performance audit is complete and fixed. The correctness audit covered all 85 files; six findings are
verified and fixed, and the ten residuals above plus the suspects are *unverified reports*, not
cleared code. Not done in this run: verifying and fixing those residuals. CI has run once over these
commits and found the one compile error recorded above, now fixed; the compile and the full test
total have not been seen green, and Hermes verifies both from the next run. Every fix above is its
own commit, so any one of them can be dropped.

**Nothing is uncommitted and nothing is half-done.** Phase A2, Hermes' two closing fixes, both
CI-found import fixes, the three CI-found fixture fixes, kaiser's device-feedback addition, the
device-found clock fix with its audit, and the device-found scroll fix are complete. No push was
made.

**What the next step is, in this order:** (1) Hermes re-runs both workflows on exact head
(`b06145d` or later) and verifies from the run JSON. CI is a compile-and-tests check only: it must
show the three fixture fixes from round 3 still holding, the thinking-box, clock and scroll
changes compiling, and 743 tests green, including `theTurnClockHoldsWithinATurnAndRestartsForTheNext`,
which has never run; (2) kaiser's device check — tap a long chain collapses it, a drag on the
chain scrolls and does not, the status line keeps counting through Show/Hide and box taps with a
new turn opening at 0s, and while streaming with the screen full the status row is fully visible;
and scrolling up by hand still stays put. No unit test in this project can see any of that; (3) the three existing device
checks the doc already names (draft per conversation, the live-status clock, the reasoning preview
across two chains); (4) residuals 7 and 9 if Hermes wants them settled rather than named —
residual 8 is closed by `4e677da`.
