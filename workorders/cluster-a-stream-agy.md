# Work order — Cluster A: provider/stream layer (worker: agy, Gemini 4 Argon medium)

Repo: ~/workspace/rivet-work — you are ON branch `stability-v1.1.0`. Stay on it.
Full evidence for every finding: `AUDIT-2026-11-01-eni.md` in the repo root (read the cited sections before touching a file). Original user reports: `BUGS-next-update.md`.

## Rules
- This machine has NO JDK/Android SDK: you cannot run gradle or tests. Keep every diff small and syntactically careful; add or update unit tests for each fix — CI will run them later.
- Local commits only, one per fix, plain messages (e.g. "fix: progress deadline for stalled streams (F01)"). NEVER push. NEVER touch main.
- Files you own in this cluster: `provider/Http.kt`, `provider/OpenAiCompatibleClient.kt`, `provider/AnthropicClient.kt`, `provider/GeminiClient.kt`, `provider/ChatGptPlanClient.kt`, `provider/ProviderError.kt`, and for F05/F06 only the request-path part of `agent/AgentLoop.kt`. Do NOT edit ChatViewModel, workspace, or UI files — if a fix seems to need one, implement your side and leave a note in your report instead.
- User-facing strings: plain simple words, say what actually happened, never blame the provider for a Rivet limit. Banned words: leverage, seamless, unlock, empower, robust, cutting-edge, game-changer, supercharge, elevate, "AI-powered".
- If you hit a usage limit mid-work, just stop — Hermes will say "continue" after the reset and you resume where you left off. Leave the tree committed and clean at every stopping point.

## Fixes, in order
1. **F01 — progress deadline in the SSE reader** (`Http.kt` ~:238-261). Track the time of the last real `data:` payload delivered to `onEvent` (heartbeats/comment lines do NOT count). If the gap exceeds 120s, abort with `ProviderError.Timeout` (it already exists and already renders). Cover BOTH reader copies: `Http.kt` and the duplicated `responsesSse` in `ChatGptPlanClient.kt`.
2. **F02 — a finish signal beats the `[DONE]` sentinel, in all four clients.** Today a missing `[DONE]` throws `InvalidResponse("incomplete stream")` and the complete answer is discarded (`OpenAiCompatibleClient.kt:98`, `AnthropicClient.kt:269`, `GeminiClient.kt:204`, `ChatGptPlanClient.kt:213`). If the parser observed a terminal finish reason and accumulated text/tool calls exist, accept the response (treat stream end as clean EOF). A genuine truncation (no finish signal, stream died) gets its own honest error via `IncompleteGeneration`, not the parse-error message.
3. **F05 — never delete a half-written answer.** In `AgentLoop.kt` (~:124-150) the partial text is persisted only on cancellation. Persist non-blank streamed text before rethrowing ANY error from the request path.
4. **F06 — a length-cut tool call keeps the turn.** All three clients throw `IncompleteGeneration` on a length finish (OpenAI-compatible :169-173, Anthropic :271-274, Gemini :205-207) and the loop only recovers from ContextOverflow. On a length cut during a tool call: keep any valid text, and give the model a tool result telling it the output limit was hit and to write the change in smaller pieces, then continue the loop.
5. **F03+F04 — the 1 MiB caps.** `MAX_SSE_RESPONSE_BYTES` (`Http.kt:47`) and `MAX_PROVIDER_JSON_BODY_BYTES` (`Http.kt:49`) are too low and the `ResponseTooLarge` copy blames the provider. For agent turns: raise the streaming cap substantially (16 MiB) — F06's smaller-pieces flow is the real protection. For `listModels` in every client: parse the model list in a streaming fashion (or raise that endpoint's bound and cap the retained model count at a sane number, keeping id + limits). Rewrite the `ProviderError.kt:46` message to name Rivet as the limiter and give a real next step.
6. **F07 — Responses-API `error` events.** `ChatGptPlanClient.kt` `accept()` (~:166-208) drops top-level `{"type":"error"}` events, which later surface as a fake parse failure. Handle the event and map it through `providerMessage` like the other terminal events.

## Report back
Per fix: commit hash, files changed, what the user-visible behaviour is now, tests added/updated, and anything you could not verify statically. End with a list of any spots where the audit's line numbers had drifted from the code you found.
