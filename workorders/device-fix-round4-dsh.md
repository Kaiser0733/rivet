# Round 4 — final-fix round: self-audit, timeout bug, auto-compaction, sweep

Branch `stability-v1.1.0` (HEAD 3641fb9). Source: kaiser's APK #3 device test,
2026-11-01 ~22:04 IST. His orders, in his priority: **read code instead of
guessing; find the bugs your own fixes introduced and fix them; then the work
below.** This round produces the final 1.1.0 candidate — completeness matters
more than speed.

## Rules for this round (non-negotiable)

1. Skills first: `systematic-debugging`, `receipts`,
   `verification-before-completion`, `rivet`, `test-driven-development`.
2. **No guessing.** Every claim in the diagnosis doc carries a file:line or a
   commit hash. If you cannot prove a mechanism from code, write "unproven"
   and say what would prove it.
3. Write `workorders/round4-diagnosis.md` BEFORE editing code: Phase 1 verdict,
   Phase 2 table, Phase 4 candidate list. Then implement.
4. Commit per fix, small commits. Do NOT push. Copy law on all user-facing text.

## Phase 1 — "The provider took too long to respond" on a trivial prompt

Evidence: fresh conversation, user sent "Hey", provider OpenAI-compatible ·
DeepSeek-V4.1-Flash-1017 (River). Banner: "The provider took too long to
respond." (Retry / Dismiss). Screenshot
`~/workspace/user/files/Screenshot_20261101_220405_Rivet_3_p4k2.jpg`.

Coordinates (Hermes grepped, verify yourself): the banner text is
`ProviderError.Timeout` (provider/ProviderError.kt:53). Producers:
`provider/Http.kt:228` (SocketTimeoutException mapping) and `Http.kt:311`
(a Timeout thrown inside the streaming read path — read what watchdog that is,
its threshold, and when its clock starts). Client timeouts at Http.kt:27-42
(connect 15s, read 300s for SSE, a 20s read-timeout variant).

Questions your diagnosis must answer from code + git history:
- Which producer fired for a first-message "Hey" that never streamed?
- Did ANY commit from rounds 2-3 (yours: Fix 1 context prep `8dfa64b`, Fix 3
  reasoning wiring `16d98e7`, the CI fixes, B4 `e42d2d5` in Http.kt itself, B5
  and its correction `3641fb9`) touch this path, change what the request sends,
  change when the timeout clock starts, or shorten any threshold? Blame the
  region; name commits.
- What does the app send now for this provider that it did not send in v1.0.0
  (reasoning fields in particular — the user's saved effort for this model may
  be a high level from his round-2 test; trace what that does to the request
  and to provider time-to-first-byte vs the watchdog)?
Fix the confirmed cause at the correct layer. If the watchdog itself is sound
but too tight for a slow first byte at high reasoning effort, say so and fix
the threshold/arming, with the reasoning in the diagnosis.

## Phase 1b — the ultra recommendation flow STILL does not fire on device

Added 22:10 IST, after this round started — kaiser's new device evidence on
APK #3: he re-tested the reasoning recommendation (DeepSeek at ultra) and the
designed flow STILL does not appear.

**Screenshot received 22:12 IST** —
`~/workspace/user/files/Screenshot_20261101_221227_Rivet_4_uw6u.jpg`. The user
sent "How are you?" and the banner is, word for word:
**"The provider said: Invalid inference request. Check the model's supported
parameters and input limits."** with plain Retry / Dismiss — byte-identical
to the round-2 failure your `e42d2d5` was built to catch. So on the real
device path, your classifier returned false (or the notice branch was never
reached), even though the unit tests pass. One of your branch's conditions
fails in production: the status is not 400/422 where you read it, the error
arrives by a different shape (SSE error event, `providerMessage`, no status —
the limit you flagged yourself), `effortWasSent` computes false, or the
failure never reaches the branch in `ChatViewModel` that calls the
classifier. Find WHICH, from code, and prove it.

This puts `e42d2d5` (B4) back in doubt. Static code reading is NOT enough to
clear it — reproduce the failing condition in a test at the layer where it
actually occurs (client/stream parsing if that is where the shape is lost),
then fix so the designed flow (unsupported-effort notice + Recommended
step-down + tap-to-resend) is what the user gets. Also connect Phase 1: the
same provider + saved config produced a Timeout on "Hey" minutes earlier —
state whether the two failures share a cause or not, with evidence.

## Phase 2 — audit your own rounds 2-3 commits for regressions

Go commit by commit over everything you authored that shipped in APK #2/#3:
`8dfa64b`, `1b2d8bf`, `16d98e7`, `a4b4f6e`, `f6d7f93`, `0d4e094`, `7e1511d`,
`6918b8b`, `f5018ba`, `2764445`, `e8764f1`, `be6bf14`, `fc3cd4a`, `e42d2d5`,
`afac18c`, `3641fb9` (skip the icon restore `1426120` — bytes verified).
For each: the user-visible behaviour it changed, one plausible regression it
could have caused, and your verdict from code/tests (in / out / unproven).
Fix every confirmed regression. Kaiser believes fixing introduced new bugs —
prove him wrong per commit, or fix what you find.

## Phase 3 — auto-compaction, dsh-style

Study first: `~/workspace/dsh-cli/node_modules/@deepseek-ai/dsh-compaction`,
`dsh-compaction-basic`, `dsh-compaction-tool-result-pruner`,
`dsh-command-compact` — how dsh decides when to compact, what it keeps
verbatim, what it summarises, how it keeps the turn alive.
Then rework Rivet's context handling (`chat/ContextPreparation.kt` +
`ContextBudget`) so a normal long conversation **compacts and continues**
instead of dying with `ContextUnavailable`:
- Keep the existing cheap steps first (prune old tool bodies, stub oversized
  results).
- Compact with headroom: trigger the summary step early enough that the
  compacted request fits with room to spare, and the SAME user turn then
  proceeds on the compacted context. No dead stop in the normal path.
- The "couldn't make room" error may remain only as a true last resort
  (repeated compaction failure), and its copy must say the chat was compacted
  and still did not fit — not imply nothing was tried.
- Guard against loops: bounded attempts per turn.
Tests: a long conversation crossing the threshold still sends its turn with a
summary in place of old history; a failing summary does not silently pass or
loop forever; the recent tail survives compaction verbatim.

## Phase 4 — sweep: small change, major improvement

From the code, `AUDIT-2026-11-01-eni.md` leftovers, and `UPDATE-FIX-LIST.md`,
list candidates in the diagnosis doc with evidence and a size/risk note.
Fix the small-and-safe ones in this round (each its own commit). Do NOT build
the structural ones — known examples: live mid-turn conversation switching
(storage is single-active-pointer), reasoning surviving an app restart
(session-only today) — flag each with a one-line cost note for kaiser's call.

## Finish
Summary per phase: verdicts with evidence, fix commits, tests added, and the
list of anything still unverified that the device test must confirm.
