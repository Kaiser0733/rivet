# Work order — Cluster E: visible thinking (worker: agy, Gemini 4 Argon medium)

Repo: ~/workspace/rivet-work — you are ON branch `stability-v1.1.0` (HEAD 98a995b; Clusters A–D landed). Context: `BUGS-next-update.md` bug #3 and `AUDIT-2026-11-01-eni.md` F26. The UI worker completed the ticking live status line (`chat/LiveStatus.kt`) but stopped at this layer — correctly: it needs provider surgery, which is yours.

## The problem (verified by the UI worker, re-verify before editing)
`ProviderClient.streamAgent(request, onDelta: (String) -> Unit)` only ever passes answer text. Reasoning is dropped or leaked:
- Anthropic parses `thinking_delta` but keeps it in request-echo blocks (`AnthropicClient.kt` ~:262) — never surfaced.
- The OpenAI-compatible client reads only `delta["content"]`; `reasoning_content` is dropped.
- Gemini thought parts (`part["text"]` with `thought: true`) are appended to the ANSWER text — thinking leaks into replies. That is a correctness bug, fix it as part of this.
- ChatGPT plan client (Responses API): check what reasoning events it emits; surface what's there, no-op gracefully if none.

## Rules
- No JDK/Android SDK here: nothing compiles or runs. Small careful diffs, tests per change, CI is the gate.
- Local commits only (one per logical step), plain messages. NEVER push.
- You own the full vertical for this feature: `provider/*` clients + `ProviderClient`, `chat/ChatViewModel.kt` + UI state, `ui/chat/ChatScreen.kt` (rendering only — do not restyle; the LiveStatus line from Cluster D stays as it is). Do not touch runtime/storage/agent layers.
- User-facing strings: plain simple words. Banned: leverage, seamless, unlock, empower, robust, cutting-edge, game-changer, supercharge, elevate, "AI-powered". No filler narration in comments or test names.
- If a usage limit stops you: leave the tree committed and clean, say exactly where you stopped.

## Build, in order
1. **Reasoning channel:** extend the streaming contract with a reasoning callback (default no-op so existing call sites keep working). Name it plainly (`onReasoning`).
2. **Clients:** Anthropic emits thinking text via the new channel; OpenAI-compatible emits `reasoning_content` (and `reasoning` if a payload carries it); Gemini routes thought parts to reasoning and NEVER to answer text; ChatGPT plan client surfaces reasoning events if present.
3. **State:** ChatViewModel accumulates streaming reasoning separately from streaming answer text; when the turn finishes, the reasoning stays attached to that assistant message (persisted with the transcript if the model allows; if persistence needs a schema change beyond a defaulted field, keep it session-only and say so in the report).
4. **Render:** while streaming, the thinking block shows above the answer text in its own visually distinct block (plain label "Thinking"); when the turn finishes it collapses to a single tappable line. It must never render as part of the answer bubble.
5. **Tests:** parsing tests per client (thinking separated from content; Gemini thought part not in answer text), a ViewModel test that reasoning accumulates separately and survives turn completion.

## Report back
Per step: commit hash, files changed, behaviour now, tests added, anything not verifiable statically, any audit line drift that mattered.
