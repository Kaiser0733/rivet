# Round 3 diagnosis — root cause and chosen fix per bug

Branch `stability-v1.1.0`, base `f5018ba`. Written **before** any code change, per the
work order. Evidence is source lines in this repo unless stated otherwise.

Note on evidence: the two screenshots named in the work order could not be opened here —
this model has no image input. Every claim below is traced from code, and the work
order's own descriptions of what the screenshots show.

## Reference model: what dsh does (studied first, per the work order)

Read under `~/workspace/dsh-cli/node_modules/@deepseek-ai/`:

- `dsh-client-ui-chat/lib/types/client/chat/ReasoningRow.d.ts` and the bundled
  implementation (`lib/client.js`, region `chat/ReasoningRow.js`):
  - one reasoning row, **always expandable**, collapsed by default:
    `useDisclosure()` → `expanded`, `toggle`; passed to `DisclosureRow` as
    `expandable: true, expandOnRowClick: true, onToggle: toggle` **regardless of
    `running`**. Being mid-stream never removes the toggle.
  - collapsed state renders a one-line **preview**, and while running that preview is
    `latestCompletedParagraphFirstLine(text)` — it advances only when a paragraph line
    completes, not on every token. Settled rows preview `firstLine(text)`.
  - the full chain is rendered **only when expanded**:
    `content = expanded ? <body> : undefined`. Collapsed costs one fixed 24 px row
    (`contain:size layout; height:24px` in its CSS).
  - memoisation is keyed on the *stable* summary string, not the growing full text.
- `dsh-client-ui-conversation/lib/client.js` (`publish`, region ~3988–4015): streaming
  publications are coalesced onto animation frames (nested `requestAnimationFrame`,
  effectively one flush per few frames) and repeated updates to one context are
  coalesced between flushes. There is no per-delta state publication.
- `dsh-chunked-list`: immutable append-only lists with bounded append copying, so
  appending a chunk never copies the whole collection. The analogue for Rivet is that
  the accumulator must not be stringified/relaid-out per delta.

Rivet's chosen behaviour therefore is: reasoning row always toggleable (running or not),
collapsed by default with a cheap one-line preview, full chain rendered only when
expanded, and streaming publications coalesced.

---

## B1 (MAJOR) — history will not open while a turn runs

**Symptom.** Tapping the history icon during a turn does nothing.

**Root cause.** Not an overlay, not the drawer, not tap-eating: the control is disabled.
`ui/chat/ChatScreen.kt:395-397`:

```kotlin
IconButton(onClick = onOpenHistory, modifier = Modifier.size(48.dp),
    enabled = state.ready && !state.projectLoading && !state.streaming && ...
```

A disabled `IconButton` consumes nothing and never calls `onOpenHistory`. The rows behind
it are gated the same way (`ui/history/HistoryScreen.kt:62-63`, `actionsEnabled` requires
`!state.streaming`, passed to every `HistoryRow`), and the switch itself is refused in
`chat/ChatViewModel.kt:1308` (`changeSession`: `if (_uiState.value.streaming || ...) return`).

**Why the switch is refused, and why simply deleting the guard is wrong.** A running turn
writes through `sessionStore.save(...)`/`markInterrupted(...)`, and `CodingSessions` binds
both to the *global active session pointer* (`storage/CodingSessions.kt:102-105` uses
`activeId(db)`; the pointer is moved by `select()` at `:201-210`). If the user switched
sessions mid-turn, every later write of the running turn would land in the *other*
session's event log — silent cross-chat corruption. That is the real reason for the
guard, and the reason `changeSession` already tries to `sendJob?.join()` before switching
(`:1313`).

**Chosen fix.** Let history open immediately and make switching during a turn safe by
**queueing** it rather than racing the write path:

1. `ChatScreen`: the history button no longer requires `!state.streaming`.
2. `HistoryScreen`: conversation rows are tappable while a turn runs; pin/rename/delete
   stay disabled during a turn (they are not part of this bug and touch the same rows).
3. `ChatViewModel`: `resumeSession(id)` during a turn records the target session and
   shows a plain notice ("Switching to <title> when this reply finishes.") instead of
   silently doing nothing; tapping the currently running conversation clears the queue.
   When the turn ends, the queued switch is applied through the existing
   `changeSession` path — the only place allowed to move the active pointer.
4. The turn is never cancelled: nothing in the change touches `sendJob`, `generation`
   or approvals, so the F22 foreground service keeps it running and returning to the
   running conversation still shows it streaming (the ViewModel is activity-scoped and
   the streaming fields are untouched by the queueing).

**Why this is the smallest correct fix.** Showing another conversation *concurrently*
with a live turn would need per-session live state (messages, streaming text, reasoning,
tool lifecycle, approvals) plus id-bound writes through `AgentSessionPersistence`
(`storage/AgentSessionStore.kt:31-34`) for two implementations and ~8 write sites in the
turn. That is an architecture change, not a bug fix, and it cannot be validated without a
device. Queueing keeps the single-active-turn invariant that the storage layer enforces
and still gives the user the flow they asked for: history opens now, the choice is
recorded, the switch happens the moment the turn is safe to leave.

**Tests.** ChatViewModel unit test: a switch requested mid-turn is recorded (notice set,
current session unchanged, turn still streaming), and after the turn finishes the queued
session is the one open. UI enablement itself has no test harness in this repo (no Compose
UI tests) — device check.

---

## B2 — thinking block cannot be collapsed while streaming

**Root cause.** `ui/chat/ChatScreen.kt:1004-1057` contains two unrelated renderings of the
same block:

- `streaming == true` (`:1005-1020`): a `Surface` with `Text("Thinking")` and the whole
  text — **no clickable, no expand state**.
- `streaming == false` (`:1021-1056`): header row with `clickable { expanded = !expanded }`
  and a `Show`/`Hide` affordance.

So the toggle exists only after the turn ends, which is exactly what the user reported.

**Chosen fix.** One implementation for both states, modelled on dsh `ReasoningRow`:
header row is always clickable and toggles; collapsed by default; while running the
collapsed header shows a one-line live preview (the first line of the latest completed
paragraph, like dsh) instead of the full chain; the full text renders only when expanded.
The settled row keeps its current appearance (one-line summary + Show/Hide).

**Why this is the smallest correct fix.** It deletes a duplicated code path instead of
adding a second toggle, and the "body only when expanded" rule is also the main rendering
win for B5.

**Tests.** No Compose UI test harness in this repo. The preview rule is extracted as a
pure function (`thinkingPreview`) and unit-tested; the interaction itself is device-check.

---

## B3 — the seconds counter freezes at 0

**Which surface freezes.** The string "Thinking · 0s" is produced by
`chat/LiveStatus.kt:22-27` (`liveStatusLine(label, elapsedSeconds)`) with the label
`"Thinking"` from `LiveStatus.kt:14` (the `else ->` branch: nothing has arrived yet, the
model is reading the turn). That row is the live status row rendered under the streaming
thinking text (`ChatScreen.kt:797`), which is why it reads as part of the thinking block.
The thinking block header itself has no counter.

**Root cause.** Two compounding faults, both only active while reasoning streams:

1. The reasoning delta path publishes on *every* delta with no throttle:
   `ChatViewModel.kt:632-639` does `reasoningPreview.toString()` + `_uiState.update` per
   delta — unlike the answer path at `:752-760`, which already limits publication to one
   per 80 ms. The whole chain is re-emitted and re-laid-out hundreds of times a second.
2. That churn re-drives the list-scroll effect, which is keyed on
   `streamingReasoning.length` (`ChatScreen.kt:744`), so the list re-scrolls per delta.
   The status row's clock is plain `remember`, not `rememberSaveable`
   (`ChatScreen.kt:858-859`), so every recreation restarts `startedAt`; the displayed
   value is `(now - startedAt)/1000` ≈ 0 each time — a counter that sits at 0, exactly as
   reported, and only in this phase.

**Chosen fix (kaiser's decision).** No seconds counter on the reasoning surface. The
status line shows the bare label while the state is "Thinking"; the clock stays for the
states that tick today (writing, a running tool, waiting for approval). The rule is a
pure function in `LiveStatus.kt` and is unit-tested.

**Tests.** Unit tests for the clock-visibility rule. The live rendering is device-check.

---

## B4 — the ultra rejection flow did not fire

**Symptom.** With reasoning set to `ultra` on River (OpenAI-compatible, DeepSeek-V4.1-Flash-1017)
the provider answered `Invalid inference request. Check the model's supported parameters
and input limits.` and Rivet showed the generic provider banner with Retry/Dismiss.

**Root cause.** `provider/Http.kt:173-191` (`isUnsupportedReasoningError`) decides from the
error **text only**. For a non-default level it accepts a message when it names the effort
field (`reasoning_effort`, `thinking_budget`, …), when it pairs a reasoning/thinking/effort
word with a refusal word, or when it repeats the wire value together with one of those
words. This provider's text contains none of them — it says "inference request",
"supported parameters", "input limits" — so the classifier returns false and the turn
falls through to the generic `ProviderMessage` branch (`ChatViewModel.kt:907-920`).
Additionally `ProviderError.ProviderMessage` carries no HTTP status (`ProviderError.kt`),
so nothing else can tell a 400-class parameter rejection from any other provider message.

**Chosen fix.**

1. Attach the status code: `ProviderMessage(text, statusCode)` with a default so every
   existing construction site still compiles, set in `httpError` (`Http.kt:111-131`).
2. Add a second, tighter rule to the classifier: a rejection is treated as an effort
   rejection when **all** of these hold —
   - the requested level is not `Default` (no effort was sent, so nothing to blame);
   - the error is a `ProviderMessage` with status **400 or 422** (401/403/404/429/5xx are
     already mapped to their own types and never reach here);
   - the text reads as a *parameter/request validation* rejection ("invalid ... request",
     "invalid/unsupported parameter", "supported parameters", "invalid value", …);
   - the text does **not** describe an input-size problem (too long/large, exceeds,
     maximum context, token count, payload) — those belong to `isContextOverflow`
     (`Http.kt:140-151`) and must not be blamed on the effort;
   - the text does **not** name a different request field we also send (messages, tools,
     temperature, top_p, stream, model, image, schema) — blaming the effort for those
     would be a wrong diagnosis.
3. Unchanged: an explicit reasoning/thinking/effort signal keeps winning regardless of
   status, so providers that name the field behave exactly as today.

**Why this is the smallest correct fix.** It adds one narrow branch plus a status code,
keeps the designed flow (banner text, Recommended step-down, tap-to-resend) untouched, and
makes the 400-class distinction explicit instead of guessing from prose.

**Tests (mandatory).** `ReasoningEffortRejectionTest`: the exact River text at 400 with
`ultra` → true; the same text with level `Default` → false; the same text at 500/429 →
false; an oversized-input 400 ("input token count exceeds…") → false; a 400 naming
`temperature` → false; an unrelated 400 ("model 'x' does not exist" style) → false.

---

## B5 — chat lags when the thinking chain gets long

**Root cause.** Three costs, all per delta and all growing with the chain:

1. `ChatViewModel.kt:632-639` — reasoning preview publishes **every** delta, and each
   publication does `reasoningPreview.toString()` (a full copy of the chain) plus a new
   `ChatUiState`. That is O(n) per delta, O(n²) over a turn, on the main thread.
2. `ChatScreen.kt:792` renders `ThinkingBlock(displaySafeText(streamingReasoning), …)`:
   `displaySafeText` walks the entire chain building a new string (`ChatText.kt:8-13`),
   and the full text is laid out again as one `Text` — per delta.
3. `ChatScreen.kt:744` keys the bottom-scroll effect on `streamingReasoning.length`, so
   the list re-scrolls (two `scrollToItem` calls) per delta.

The answer path already avoids (1) with the 80 ms gate at `:752-760`; the reasoning path
never got it.

**Chosen fix (dsh model).**

1. Gate reasoning publication with the same window as the answer, extracted into a small
   pure helper used by both paths, so the growth cost is paid a few times a second instead
   of hundreds. The settled message still takes the full chain from `reasoningPreview`
   (`ChatViewModel.kt:776-779`), so nothing is lost by dropping intermediate previews.
2. Render the chain only when the row is expanded (from B2), with the collapsed preview
   memoised on the derived one-line summary — so a collapsed, streaming chain costs one
   short line per publication instead of a full layout.
3. The scroll effect then fires at the publication rate, not the delta rate.

**Why this is the smallest correct fix.** It reuses the throttle that already exists for
the answer text, and it removes the full-chain layout from the common (collapsed) case
without changing appearance when expanded.

**Tests.** Unit tests for the publication window (first delta publishes, deltas inside the
window are dropped, the first delta after the window publishes). Rendering smoothness is
device-check.

---

## Constraint check

- Reasoning stays session-only: untouched.
- No storage-format change: B1 changes only which session the pointer may move to and
  when; no schema, no new persisted field.
- No unrelated refactors; every fix is confined to the four files named above plus the
  small helpers and their tests.

---

## Implementation notes (added after coding, so the note matches the code)

- **B1** landed as `2764445` plus `e8764f1`. The first commit put the queued-switch
  hand-off after the turn's `try/catch/finally`; verifying the stop path
  (`ChatViewModel.cancel()` cancels `sendJob`, `:1225-1228`) showed a queued switch would
  then be skipped on a stop, so the hand-off moved inside the outer `finally` — it now
  runs whether the turn finished, failed, or was stopped.
- **B4** also requires that the provider actually *sent* an effort: the new branch takes
  `effortWasSent`, computed in the ViewModel as
  `offeredReasoning(snapshot.config).contains(requested)`. A generic 400 on a model that
  never receives an effort (Gemini, a non-reasoning OpenAI preset) is therefore never
  blamed on the effort.
- **B4** status codes come from the HTTP response (`httpError`). A provider that reports
  the rejection *inside* an SSE error event instead (`providerMessage`, no HTTP status)
  still gets the text rules but not the status rule — noted as a known limit below.
- **B5** reuses one `PreviewWindow` per stream; the settled assistant message still takes
  the complete reasoning from `reasoningPreview` (`ChatViewModel.kt:776-779`), so dropped
  interim previews lose nothing.
