# Device-fix round 3 — work order for hb (dsh, DeepSeek V4.1 Flash 1017, reasoning MAX)

Branch: `stability-v1.1.0` only (HEAD f5018ba). Source: kaiser's device re-test of
APK #2 (release-signed, CI green) on 2026-11-01 evening. Screenshots in
`~/workspace/user/files/Screenshot_20261101_204756_Rivet_0_d14n.jpg` (thinking block)
and `~/workspace/user/files/1000210693_2_uehr.jpg` (ultra error banner).

## Order of work (kaiser's instruction, do not skip)

1. **Skills first.** Read and use the skills in `~/.dsh/skills/` before touching code —
   above all `systematic-debugging` (root cause before any fix), `rivet`,
   `test-driven-development`, `verification-before-completion`, `receipts`.
2. **Study dsh's own chat UI before designing anything.** The user wants Rivet's
   thinking/chat behaviour modelled on dsh's, not invented. Grep the installed
   packages under `~/workspace/dsh-cli/node_modules/@deepseek-ai/` — start with
   `dsh-client-ui-chat`, `dsh-client-ui-conversation`, `dsh-chunked-list` — for how
   dsh renders reasoning/thinking (collapse behaviour) and long streaming text.
3. **Diagnosis before code.** Write `workorders/round3-diagnosis.md`: for each bug
   below, the root cause with file/line evidence, the fix you chose, and why it is
   the smallest correct fix. Only then edit code.
4. Commit per bug, small commits. Do NOT push — Hermes reviews, then pushes and
   runs CI. Copy law applies to every user-facing string (no banned AI words).

## Bugs

### B1 (MAJOR) History will not open while a turn is running
While the model is working in a chat, tapping the history icon does nothing — the
history window never opens, so the user cannot switch chats mid-turn. Find the root
cause (check: nav/history click handling gated on streaming state, an overlay or
invisible layer eating taps, drawer state, main-thread saturation from streaming
recomposition — prove which). Fix so history opens and the user can switch chats
while a turn runs, WITHOUT cancelling the running turn (the F22 foreground service
keeps it alive; leaving the chat must not kill it, and coming back must show it
still streaming).

### B2 Thinking block cannot be collapsed while streaming
The thinking content renders fully expanded during streaming with no way to fold
it away (screenshot 1). After the turn it collapses to a line; during the turn it
does not. Match dsh: tapping the Thinking header toggles collapse/expand, both
during streaming and after the turn finishes.

### B3 The seconds counter on the thinking block freezes at 0
Correction from kaiser: the timer is NOT dead in general — the live status line
ticks fine during normal activity. It dies when the model's reasoning content
starts streaming: the thinking block shows "Thinking · 0s" and the 0 never moves.
Kaiser's decision: remove that frozen seconds counter from the thinking block.
Do not ship a counter that sits at 0. (If the same elapsed display exists on the
activity/status line and ticks correctly there, leave that one alone — verify
which surface freezes and say so in the diagnosis.)

### B4 Ultra rejection flow did not fire (screenshot 2)
Provider: OpenAI-compatible · DeepSeek-V4.1-Flash-1017 (River). User set reasoning
to **ultra** and sent a message. Provider answered: "Invalid inference request.
Check the model's supported parameters and input limits." Rivet showed the generic
banner "The provider said: Invalid inference request..." with plain Retry/Dismiss.
The Fix 3 flow (commit 16d98e7) never fired: no "this model does not support that
reasoning effort", no Recommended step-down (ultra→max), no tap-to-resend.
Find why the detection missed this error shape and fix it so this class of
provider rejection — a 400-class parameter rejection received while a non-default
reasoning effort was sent — routes into the designed flow. Guard: unrelated
invalid requests (e.g. oversized input) must NOT be misread as effort rejections;
in the diagnosis, state how you distinguish them. Add tests using this exact
error text.

### B5 Chat lags hard when the thinking chain gets long
As reasoning text grows, the chat UI lags (scroll and streaming both). Diagnose
the rendering cost (full-chain recomposition/re-parse per delta is the prime
suspect; check what dsh does for long reasoning — chunked rendering exists in its
tree). Optimise so a long thinking chain streams smoothly: coalesce/throttle
state updates, avoid re-rendering or re-parsing the whole chain per delta, cap or
lazily render the visible thinking text. Behaviour and appearance otherwise
unchanged.

### B6 (added 21:14 IST, after hb's run had started — Hermes sends this as the
follow-up turn in the same session) App icon: undo the zoom
Kaiser's device verdict on the Fix 5 icon (commit f8722ab, tight face crop): too
zoomed, does not look good. Revert the launcher icon to how it was BEFORE that
crop — the original full-logo framing from v1.0.0 (whole canvas, ring and
margins included). Restore those icon assets exactly; one commit; no other
icon changes, no new crop experiment.

## Constraints
- Reasoning stays session-only (not persisted) — accepted limitation, do not change.
- No storage-format changes. No unrelated refactors.
- Tests: extend unit tests where the repo supports it (rejection detection for B4
  is mandatory; state/logic tests for B1/B3 where feasible). State plainly what
  has no test coverage and needs the device check.
- Finish with a summary: per bug — root cause, fix commit, test evidence, and
  anything still unverified that the device re-test must confirm.
