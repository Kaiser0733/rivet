# Round 4 diagnosis — written before any code change

Branch `stability-v1.1.0`, base HEAD `3641fb9`. Method: read the code, blame the
history, and prefer "unproven" over a guess. Every claim below carries a
`file:line` or a commit hash. Claims I could not prove from code are marked
**unproven**, with the evidence that would settle them.

Limits of this environment (unchanged since round 3): no JDK, no Android SDK, and
the sandbox refuses the JVM's TCP connections, so nothing here was executed
locally. Tests are written and reasoned through against the code; CI is the first
execution. That is stated again in the summary.

---

## Phase 1 — "The provider took too long to respond" on "Hey"

### What produces that banner
`ProviderError.kt:94` — `is Timeout -> "The provider took too long to respond."`
Only `ProviderError.Timeout` produces it. Two producers exist:

| Producer | Site | Fires when | Wall time |
|---|---|---|---|
| A — socket | `Http.kt:228` (`networkError`, `SocketTimeoutException -> Timeout()`) | no bytes at all from the socket | OkHttp `readTimeout` = 300 s (`Http.kt:28`) |
| B — progress | `Http.kt:311` (`throw ProviderError.Timeout()`) | lines keep arriving but no `data:` payload for `progressDeadlineMs` | 120 s (`Http.kt:48` `SSE_PROGRESS_DEADLINE_MS = 120_000L`) |

Both texts are identical, and `ChatViewModel.kt:920` logs only
`e.javaClass.simpleName`, i.e. `Timeout` for both. **Which producer fired is
unproven.** What would prove it: a device log line that distinguishes them, or the
elapsed time from send to banner (≈120 s → B, ≈300 s → A).

### The arming defect that is provable (independent of which one fired)
`Http.kt:299` arms the progress clock when the response *headers* arrive
(`lastProgressAt = clockMillis()`), and only refreshes it on a `data:` payload
(`Http.kt:305`). The 120 s budget, written for F01's "stream that keeps sending
heartbeats after it died" (`AUDIT-2026-11-01-eni.md` F01), therefore also governs
the *pre-first-token* phase — the model's thinking time, which is exactly the
phase a reasoning model is allowed to spend silently. Producer B is only reachable
when lines arrive (the check sits after `readUtf8LineStrict`, `Http.kt:304-308`);
a fully silent socket is governed by producer A.

Conclusion: the watchdog is sound for a mid-stream stall and **too tight for the
first token at a high reasoning effort**. That is a defect of arming, not of the
120 s figure.

### Did my rounds 2-3 commits touch this path?
`git blame -L 24,50 provider/Http.kt` → every timeout line (`connectTimeout(15s)`
`:27`, `readTimeout(300s)` `:28`, `writeTimeout(30s)` `:29`, `quick()`'s `20s`
`:41-42`) is `a6e68fe` (baseline). `SSE_PROGRESS_DEADLINE_MS` is `a31f712`
("fix: progress deadline for stalled streams (F01)") — not mine. `git log -S`
returns no round-2/3 commit for any of them. My `e42d2d5` did touch `Http.kt`, but
its diff is only `ProviderMessage`'s new `statusCode` and the classifier
(`Http.kt:122-123`, `:186-215`) — no timeout, no clock, no request field.
**No commit of mine changed a timeout or a threshold.**

### What Rivet now sends that v1.0.0 did not
`16d98e7` rewrote `reasoningEffort` (`OpenAiCompatibleClient.kt:219-231`):

```
 before: type == OpenRouter -> wireEffort
         type == OpenAi && supports -> wireEffort (Max -> null)
         else -> null                      // custom endpoints got nothing
 after:  type == OpenAi && !supports -> null
         else -> wireEffort                // custom endpoints now get a value
```

with `wireEffort` now mapping `Max -> "max"` (`:239`) and `Ultra -> "ultra"`
(`:240`), and the field is written at `OpenAiCompatibleClient.kt:88-90`. `f6d7f93`
rewrote the test that guarded the old behaviour into its opposite
(`genericProviderOmitsReasoningEffort` → `genericProviderSendsReasoningEffort`,
`OpenAiCompatibleClientTest.kt:339-353`).

So for River with a saved non-default level, **every** request now carries
`reasoning_effort: "ultra"` (or `"max"`, `"xhigh"`) where v1.0.0 carried nothing.
The other body fields are `model`, `stream`, `messages`, `tools`, and
`max_completion_tokens` only when a manual output limit is saved
(`OpenAiCompatibleClient.kt:63-91`, `ProviderConfig.kt:131-134`). Whether Kaiser
has a manual output limit saved for this model is **unproven**; if he has, that is
a second non-baseline field.

**Phase 1 verdict:** the failure is a first-byte wait longer than the watchdog,
and the only request change since v1.0.0 that can lengthen it is the effort field
added by `16d98e7`. The provider's actual latency is not observable from here
(**unproven**): a slow gateway would produce the same banner. That makes Phase 1
and Phase 1b the same root cause — Rivet began sending an effort this endpoint is
not known to accept — with two different symptoms.

---

## Phase 1b — the designed effort flow still does not fire on device

Device evidence: banner **"The provider said: Invalid inference request. Check the
model's supported parameters and input limits."**

### Which branch produced that banner
`ProviderMessageText.kt:17` builds "The provider said: …"; its only caller is
`ChatViewModel.kt:934`, which is reached **only if the effort branch at
`ChatViewModel.kt:925` was false**. Had it been true, the banner would have been
`UNSUPPORTED_REASONING_ERROR` (`ChatViewModel.kt:928`) and the Recommended row
would be present. So on device the classifier returned false, or the branch was
skipped.

### The branch's three inputs, checked one by one
- `recommended = recommendedFallbackReasoning(requested)` — non-null unless
  `requested` is `Default` (`ProviderConfig.kt:37-45`). `requested` cannot be
  `Default`: with `Default`, `reasoningEffort` returns null
  (`OpenAiCompatibleClient.kt:224`) and the body carries no effort field at all,
  leaving nothing Rivet-added for the gateway to refuse. **Eliminated.**
- `effortSent = offeredReasoning(snapshot.config).contains(requested)` — for
  `OpenAiCompatible` this is `else -> ALL_REASONING_LEVELS`
  (`ProviderConfig.kt:233`), so true. **Eliminated.**
- Inside `isUnsupportedReasoningError` (`Http.kt:161-192`): the text names no
  reasoning field and no wire value, so `mentionsReasoning` (`:170-181`) and the
  wire test (`:183-184`) are both false; `looksLikeParameterRejection` **is true**
  for this text (`PARAMETER_REJECTION` "invalid inference request" `:196-199`;
  nothing from `SIZE_REJECTION` `:202-205` — note "input limits" is not "context
  length" or "token count" — and nothing from `OTHER_FIELD_REJECTION` `:208-213`).
  So the returned value is exactly `status in setOf(400, 422)` (`:186`). It was
  false.

### Where the status is lost
A `ProviderMessage` carrying that text can be built in two places:
- `httpError` (`Http.kt:122-123`) — from a non-2xx response, with the numeric
  code. Only 400/413/422 reach `ProviderMessage` with a status (`Http.kt:112-124`);
  401/403/404/429/5xx map to other errors. A 400 or 422 here would have fired the
  flow, which it did not.
- `providerMessage` (`Http.kt:133-138`) — from an **error event inside the
  stream**, `OpenAiCompatibleClient.kt:148-152`:
  `throw providerMessage(message, error.obj()?.get("code")?.str())` →
  `ProviderError.ProviderMessage(message)`, **no status at all**.

**Phase 1b verdict (proven by elimination):** the refusal reached the app with a
status that is not 400/422 — either absent (stream error event) or a 4xx other
than 400/422 (the only other shape `httpError` can produce with a body, e.g. 413).
My `e42d2d5` rule required `status in setOf(400, 422)`, so it returned false on
exactly the shape the device delivers. The unit tests passed because they all
constructed `ProviderMessage(text, 400)` — a shape this endpoint does not use.
Which of the two remaining shapes River sends is **unproven**; both are fixed by
the same change, and a test at the stream-parsing layer
(`OpenAiCompatibleClientTest`) pins the shape that is reachable in code.

**Ranked catalogue of banners a failed ultra send can produce** (from code):
1. "The provider said: <text>" — `ProviderMessage`, any shape (`ProviderMessageText.kt:17`). **This is what the device shows.**
2. "The model's context is full…" — `ContextOverflow` (`ProviderError.kt:55`).
3. "Provider server error (HTTP 5xx)" — `Server` (`ProviderError.kt:71`).
4. "Rate limited by the provider…" — `RateLimited` (`ProviderError.kt:50`).
5. "The provider took too long to respond." — `Timeout` (`ProviderError.kt:94`).
6. "This model does not support that reasoning effort…" — the designed flow,
   `UNSUPPORTED_REASONING_ERROR` (`ChatViewModel.kt:928`). Never seen on device.
7. "The provider returned a response Rivet could not parse." — `InvalidResponse`
   (`ProviderError.kt:95`).

**Our own defect, in plain words:** round 2 taught Rivet to send Rivet-invented
effort names (`"max"`, `"ultra"`) to custom endpoints — `16d98e7` + `f6d7f93` —
and made the recovery depend on noticing the refusal. The notice required an HTTP
status the endpoint does not send (`e42d2d5`). Both halves are mine.

---

## Phase 2 — audit of the commits I authored for APK #2/#3

Verdicts from reading the diff, the current code and the tests. "IN" = no
regression found (what I checked is named); "OUT" = confirmed regression.

| Commit | User-visible change | One plausible regression | Verdict |
|---|---|---|---|
| `8dfa64b` | per-model context window and max output settings | adds `max_completion_tokens` to the request when a manual limit is saved, which a gateway can refuse | IN — only when the user saves one (`ProviderConfig.kt:131-134`); cannot alter the effort path |
| `1b2d8bf` | auto-title survives a blocked or retried turn | a title call competing with the turn / affecting the context estimate | IN — title path is isolated (`ConversationTitleLifecycle`), no request-shape change |
| `16d98e7` | every provider offers the full ladder; a refusal is recoverable | **sends `reasoning_effort: "max"/"ultra"` to custom endpoints** | **OUT** — `OpenAiCompatibleClient.kt:219-231`; Phase 1 + 1b |
| `a4b4f6e` | long-press copy menu | none: Compose menu only | IN — `ChatScreen.kt` MessageRow only |
| `f6d7f93` | test now expects the effort to be sent to custom endpoints | locks in the regression above | **OUT** (part of the same defect) |
| `0d4e094` | OpenRouter gets `xhigh` | affects non-OpenRouter types | IN — guarded by `type == OpenRouter` (`OpenAiCompatibleClient.kt:228-229`) |
| `7e1511d` | quoted JSON title key accepted | title parsing only | IN — title path |
| `6918b8b` | aggressive stubbing only when a summary carries information | fewer prunes → more overflow pressure | IN as a bug fix, but it removes savings: interacts with Phase 3, see below |
| `f5018ba` | appearance store test waits for close | test-only | IN — no production code |
| `2764445` | history opens mid-turn; a picked chat is queued | a queued switch firing unexpectedly | IN — applied only in the turn `finally` with a generation guard (`ChatViewModel.kt`) |
| `e8764f1` | queued switch survives a stop | none found | IN |
| `be6bf14` | thinking row collapses while streaming | an expansion state leaking across turns | IN — keyed `rememberSaveable(id)` |
| `fc3cd4a` | no clock on the reasoning line | clock lost on surfaces that need it | IN — `liveStatusShowsClock` keeps it for writing/tool/approval |
| `e42d2d5` | effort refusal recognised from a generic message | **requires status 400/422** | **OUT** — Phase 1b |
| `afac18c` | reasoning preview published on a window | trailing edge dropped → stale preview (CI red) | OUT at the time, **fixed by `3641fb9`** — now IN |
| `3641fb9` | reasoning published per delta + stream-end flush | extra publications | IN |

Confirmed regressions to fix in this round: `16d98e7`/`f6d7f93` (what is sent) and
`e42d2d5` (how the refusal is noticed).

---

## Phase 3 — compaction: Rivet vs dsh

Read from the installed dsh packages (all unminified ESM, cited in full in the
round summary). The four decisions worth copying:

1. **Trigger with headroom.** `dsh-compaction-basic/lib/index.js:132`
   `thresholdTokens = min(contextWindow * 0.8, contextWindow - reservedOutput - headroomTokens)`,
   `headroomTokens` 65 536 (`:63`), checked before every model request
   (`:839`, `:941`).
2. **Cheap steps first, summarise only if still over** (`:941-946`: prune, then
   re-measure, and return without an LLM call if pruning sufficed).
3. **Keep a recent tail verbatim** (`retainTokens ≈ 16%`, `:133`) and never split a
   tool call from its result (`:424-427`).
4. **The turn continues** — compaction runs in-turn (`:973`), and a failed
   summarise is swallowed so the turn proceeds (`:843-851`); bounded attempts
   (`compactionRetries` 1, `:82`; `maxOverflowRetries` 1, `:83`).

Rivet as built:
- The pre-request hook already exists: `AgentLoop.kt:105`
  `compactContext(messages.toList(), false)` before every model call, with the
  overflow retry at `AgentLoop.kt:126-138`.
- The cheap steps exist: `ContextPreparation.kt:96-118` (prune, then progressively
  aggressive stub).
- **The defect: no headroom.** The reduction trigger is the hard ceiling itself —
  `ContextBudget.kt:57-67` sets `allowedInputTokens = window - reserve` and
  `needsReduction = inputTokens >= allowedInputTokens` (`:22`), used at
  `ContextPreparation.kt:81`; the plan then targets that same ceiling
  (`ContextPreparation.kt:83-84`) and demands the result be strictly below it
  (`:152-154`). A compaction that lands at the ceiling is a correct-but-useless
  compaction: the next request re-triggers it, and any estimation slack lands in
  `noHeadroom` → `context_headroom_unavailable` (`:168`) → `AgentLoop.kt:112`
  → `ContextUnavailable` → the dead stop at `ChatViewModel.kt:1080`.
- So the normal path can dead-stop on a long conversation even though the
  summariser works, and the copy ("couldn't make room within the N-token context
  window") tells the user nothing was tried.

**Phase 3 plan** (one commit): give `ContextAssessment` a trigger and a target
below the ceiling, mirroring dsh's two margins; make `needsReduction` use the
trigger; make the plan target the lower value and require the result below the
trigger; bound the number of summariser calls per turn; and change the last-resort
copy to say the chat was compacted and still did not fit.

---

## Phase 4 — sweep candidates (evidence, size, risk)

From code and `AUDIT-2026-11-01-eni.md` / `UPDATE-FIX-LIST.md`:

| Candidate | Evidence | Size | Risk | Action |
|---|---|---|---|---|
| Distinguish the two timeout producers | `Http.kt:228` and `:311` produce one identical error; `ChatViewModel.kt:920` logs only the class | tiny | none | **fix now** (also unblocks the Phase 1 question on the next device run) |
| Phase-aware watchdog arming | `Http.kt:299` arms the mid-stream budget before the first token | small | low | **fix now** |
| F05 — keep streamed partial text when a turn fails | audit F05; `ChatViewModel.kt:919-941` replaces the bubble with the banner; only the cancel branch keeps partial text | medium | medium | flag |
| F02 — missing `[DONE]` treated as a parse error | already partly fixed: `OpenAiCompatibleClient.kt:200-202` throws `IncompleteGeneration`, not `InvalidResponse` | — | — | no action, verify in summary |
| F23 — interrupted-turn marker on restore | audit F23 | medium | medium | flag |
| Live mid-turn conversation switching | storage is a single active pointer (`CodingSessions.activeId`), the reason for round-3's queue | large | high | flag (structural, kaiser's call) |
| Reasoning survives an app restart | reasoning is session-only by design (`ConsistentChat`/state only) | large | medium | flag (structural) |

Structural items are not built this round, per the order.

---

## Post-implementation notes (written after the fixes, for the record)

**Fixes shipped this round (one commit each):**
- `e2d6804` — Phase 1b: the effort-refusal rule now accepts the status-less shape that
  an in-stream refusal has (`Http.kt`, `clientSide = status == null || status in
  {400, 422}`), with the size/other-field guards unchanged. Tests: the client-layer
  shape test (`OpenAiCompatibleClientTest.streamErrorKeepsTheProviderTextAndCarriesNoHttpStatus`),
  the classifier test for the in-stream shape, and a view-model test that the ladder
  comes back with Max recommended from that exact error.
- `0884658` — Phase 1: `ProviderError.Timeout` records which watchdog fired
  (`"socket"` / `"progress"`) and the turn logs it, so the next device run settles the
  one question this round could not.
- `48bba1e` — Phase 3: compaction trigger and target sit below the ceiling
  (`ContextBudget.assess` → `compactAtTokens`/`compactToTokens`), the plan must land
  below the trigger, summariser calls are bounded per turn, and the last-resort copy
  says the chat was compacted and still did not fit.
- `a1495d0` — the thinking state shows movement: the status line ticks again and the
  collapsed row previews the line being written.

**Two recommendations I checked and did NOT take:**

1. *"Restore `afac18c`'s reasoning `PreviewWindow`."* It would re-break
   `ChatViewModelTest.streamingReasoningAccumulatesSeparatelyAndSurvivesTurnCompletion`,
   which failed CI run 41323924314 for exactly that reason: a burst publishes only its
   first delta and the rest stays invisible until the stream ends (`3641fb9`). The
   rendering cost is real, but the cure is to make the per-delta work cheap (an
   incremental preview and a bucketed scroll key), not to stop publishing. Flagged.
2. *"`changeSession` bumps the generation counter before it joins, so the turn loses its
   Undo registration."* The checkpoint registration
   (`ChatViewModel.kt:951-964`) runs in the inner `finally`, which executes **before**
   the outer `finally` that clears `streaming` (`ChatViewModel.kt:993-996`), so the
   `changeSession` guard (`:1355`) still blocks on the success path. The window is
   reachable only on the failure/cancel paths, where `stableFailure`/the cancel branch
   clear `streaming` before the inner `finally` runs. Whether that loses anything the
   user notices is **unproven** (the Undo pointer may be restored with the session), so
   it is flagged rather than reordered under time pressure.

**Deliberate test-contract changes (not weakened assertions):** `LiveStatusTest` no
longer asserts that the thinking line has no clock, and `ThinkingPreviewTest` now
expects a tail preview before the first line ends. Both document behaviour chosen in
this round: `fc3cd4a` had removed the only moving element on screen during a long
provider wait, which is the same signature as a hung stream.

**Explicitly not done:** adding "input limits" to `SIZE_REJECTION`. It appears in the
classifier's motivating text ("…supported parameters and input limits"), so adding it
would disable the very detection this round exists to fix. Residual risk stays: a
size-driven 400 whose text contains none of the existing size phrases can be read as
an effort refusal; the ladder ends at Default (which sends no effort field), so the
user still reaches a request with no effort in it.

**Still open, with evidence (flagged, not built):** the `MAX_ERROR_BODY_BYTES` unit
mismatch (`Http.kt:53` bytes vs `take(.../4)` characters); `capacitySource` computed
but never gating the planning target (a 262,144 default is assumed for providers with
no detected window); `PreviewWindow.reset()` without a production caller; the title
dead-end after one failed title request; and the two structural items in the sweep
table.

---

## CI run 41329425382 — four failures, diagnosed before fixing

### (1) `ReasoningEffortRejectionTest.onlyClientSideRejectionsCarryTheBlame`
That test was written in round 3 (`e42d2d5`) when a status-less `ProviderMessage` was
by definition not a refusal — the rule was `status in setOf(400, 422)`
(`Http.kt:186` then). `e2d6804` deliberately reversed exactly that case because the
device path delivers the refusal with **no** status (`Http.kt:133-138` ←
`OpenAiCompatibleClient.kt:148-152`). So the third assertion in that test
(`riverRejection()` built with no code → false) no longer describes the intended
behaviour: the *case changed meaning*, and the new meaning is the fix.

The boundary the new rule must pin is not "has a status" but "reads like a parameter
rejection":
- status-less **refusal** text → counts (this is what the device needed);
- status-less **non-refusal** text (e.g. "upstream connect error") → still does not;
- 429 / 5xx → still does not, whatever the text.
The test is adjusted to pin that boundary rather than the old one.

### (2) and (3) `ContextPreparationTest` — `context_headroom_unavailable` on a 4096-token window
Real design flaw in `48bba1e`, not test noise. With `limit = 4096` (Gemini, reserve 0):
`allowedInputTokens = 4096`, `headroom = max(4096/8, 1024) = 1024`,
`compactAtTokens = 3072`, `compactToTokens = 2048` (`ContextBudget.kt:57-70`). The plan
then targets 2048 (`ContextPreparation.kt:83-84`) while the request's own floor —
system prompt + the newest message + the summary envelope + 256 — is already ≈ 2330
(`ContextPreparation.kt:85-92`). `roomAfterMinimum = target − minimum − envelope − 256`
goes negative, `summaryBudgetTokens` is clamped to 0, `targetBytes` to 0, so
`plan == null` (`:123`) and with no prior summary and no tool results to stub
(`:136-145`) the code reaches `noHeadroom` → `context_headroom_unavailable`.
Two margins were subtracted from a window that only has room for one. The old code
targeted the ceiling (4096), the plan fit, and compaction succeeded — which is why
these pre-existing tests passed before this round.

Fix at the design level, not by relaxing the tests: the margin a compaction takes must
come **out of the room a compaction can actually free**. Cap it at half that room:

```
floor   = minimumTokens + summaryEnvelope + 256
usable  = ceiling - floor
margin  = min(ceiling - idealTarget, usable / 2)     // two margins on a normal window
target  = ceiling - margin                            // still a full margin below the ceiling
```

On a 246 k window `usable/2` is huge, so `margin` is unchanged and the early trigger
plus real headroom are kept. On a 4096 window `usable ≈ 1766`, so `margin ≈ 883` and
`target ≈ 3213`: the summary keeps a budget, the plan fits, and the compaction lands
below the trigger anyway. Acceptance moves from "below the ceiling" to "below the
target", so a normal window still rejects a compaction that frees nothing.

### (4) `ChatViewModelTest.failedSummaryKeepsFullOriginalHistory` — 5 s timeout
Not a stall in the turn, and not the bounded-attempt flow: the turn ends quickly with
`ContextUnavailable` and the test's predicate never matches. The predicate is
`it.error?.contains("make room") == true` (`ChatViewModelTest.kt:909`), and `48bba1e`
replaced that copy — the work order required the last-resort message to say the chat
was compacted and still did not fit. The turn's behaviour is unchanged (full history
kept, no summary saved, one provider call); only the sentence the test greps for moved.
Fix: point the predicate at the new wording. The 5 s timeout is a symptom of waiting for
text that no longer exists, and it is **unproven** that anything hangs — the timeout is
in the test's own `withTimeout`, not in the turn.
