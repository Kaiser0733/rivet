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

## Phase A2 — second full audit  [ DONE — see "Phase A2 record" at the bottom ]

Verdict, stated up front: **six defects found and fixed — three in product code, one in copy,
two in tests — and nothing proven left open.** Four came from this audit, two from Hermes'
review of its record (the closing fixes at the end of this section). The residuals were
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

**Six defects, all fixed: three in product code (A2-3, A2-4, C2), one in user-facing copy (C1),
two in tests (A2-1 could never pass; A2-2 had lost its subject). Nothing proven is left open.**
Commits: `0db23a5`, `b4b7206`, `6e26d94`, `2f9f5c4`, `fbb152f`, `4e677da`.

| # | Finding | Kind | Commit |
|---|---|---|---|
| A2-1 | F2's pin asserted a classification the code cannot produce; would have failed CI | test, unsatisfiable | `0db23a5` |
| A2-2 | F4 silently made the projection-cap pin vacuous (guess halves the trigger) | test, lost coverage | `b4b7206` |
| A2-3 | Per-conversation drafts were destroyed by every switch, not kept | product code | `6e26d94` |
| A2-4 | F1 put provider words in the error banner unescaped (bidi controls) | product code | `2f9f5c4` |
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
escaped; five `Text` calls now go through `displaySafeText`:

| Site | What flows in | Already escaped? |
|---|---|---|
| `ProviderEditor.kt:235` `test.message` | `editorError` -> `ProviderError.text()`: provider words | no |
| `ProviderEditor.kt:243` `fetchError` | same | no |
| `SettingsScreen.kt:163` `loadError` | fixed app copy (`ProvidersViewModel.kt:90/605/623`) | no |
| `SettingsScreen.kt:225` `autonomyError` | fixed app copy (`RivetApp.kt:156`) | no |
| `SettingsScreen.kt:280` `appearanceError` | fixed app copy (`RivetApp.kt:135`) | no |

`test.message` is one line above the site the review named and is the same exposure — it is the
main "Test connection" error surface — so it is included, and said so here rather than folded
in silently. The three SettingsScreen values carry no provider text today, so escaping changes
nothing they show; they are done anyway so the rule is "every Text that shows a stored error
string" instead of a list of exceptions to keep current. No test: `displaySafeText` is already
pinned (`ChatPresentationTest.bidiFormattingControlsAreVisibleInApprovalAndPathText`) and this
source set has no `createComposeRule` to assert the wiring.

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

Banned-word check over both fixes' new copy against the seven terms the order bans: none of
them appears in the new sentences or in the new comments.

## END OF RUN — nothing half-done

Done on this branch, in order: S1 (`bb5d149`), S2 carry (`50f21e1`), markInterrupted binding
(`96ac827`), F1 (`83febf3`), F2 (`295f61a`), F3 (closed, no code), F4 (`5209bc8`), F5 (`c4dbe7f`,
test `40a88bd`), F7 items 1-3 settled (`d156e48`, fix `327ff50`, `4ceceeb`), F6 pins (`859c601`)
and F6's cost cut (`952370f`), S2 rehydration + row tests (`c30b008`), then this audit:
`0db23a5`, `b4b7206`, `6e26d94`, `2f9f5c4`, and Hermes' two closing fixes `fbb152f`, `4e677da`.

**Nothing is uncommitted and nothing is half-done.** Phase A2 and Hermes' two closing fixes
are complete. No push was made.

**What the next step is, in this order:** (1) exact-head CI on this branch — nothing here has
ever run, and four of the last five commits are new code or changed tests; (2) the two
`ChatViewModelTest` Undo row tests, the only end-to-end coverage the Undo row has; (3) the three
device checks the doc already names (draft per conversation, the live-status clock, the reasoning
preview across two chains); (4) residuals 7 and 9 if Hermes wants them settled rather than named (residual 8 is
closed by `4e677da`).
