# Work order — device-test fix round 2, Argon pass (branch stability-v1.1.0, HEAD d627ca3 + Argon test fixes)

Source: kaiser's real-device test of the CI-green v1.1.0 build, 2026-11-01 (screenshots in ~/workspace/user/files/Screenshot_20261101_131903*, _132222*, _132847*). CI is green (674 tests) — all three items below are runtime behaviour CI did not catch. The audit contract still applies: fix code when behaviour is wrong, never weaken a test to go green.

## Rules
- Local commits only, one per fix, plain messages. NEVER push.
- User-facing strings: plain simple words, name the real number/reason when one exists. Banned: leverage, seamless, unlock, empower, robust, cutting-edge, game-changer, supercharge, elevate, "AI-powered". No filler narration in comments/test names.

## Fix 1 — conversations die with "couldn't make room" far below the model's real window (the session-killer)
Device evidence: a Minecraft-clone session on River (OpenAI-compatible, DeepSeek-V4.1-Flash-1017, real window ~1M) died mid-task with `AgentStopReason.ContextUnavailable` (copy at ChatViewModel.kt:957) after the model created + repeatedly re-read one large HTML file. Diagnose before editing:
- What `knownInputLimitTokens` does `ContextBudget.assess` resolve for this provider/model, and from where?
- KAISER'S DESIGN for the window (decided 2026-11-01, modelled on the dsh settings screen — per-model fields on the provider): the provider editor gains, per model, a **Context window** field and a **Max output tokens** field, both manually editable. Resolution order for the context window: (1) the user's manual value when set; (2) AUTO-DETECT — Rivet detects the model's window itself from whatever the provider exposes (model-list metadata where it carries a context length); (3) if detection fails or finds nothing, DEFAULT **262144 (256K)**. Auto-detect runs when a provider/model is added or refreshed, its result is stored on the model config, and the user can always override it manually afterwards. Max output tokens: same pattern — manual field; when unset, send no explicit cap (the provider's own default rules).
- Why did reduction fail? The transcript holds several full copies of one huge file (create + read-backs). Reduction must stub oversized historical tool results (keep the fact + size, drop the body) before it ever declares ContextUnavailable; ContextUnavailable is only honest when even the minimum request cannot fit the RESOLVED window.
- Make the failure copy honest if it can still happen: name the limit it hit.
Tests: resolution order (manual beats detected beats 256K default); detection parsing from a model-list payload that carries a context length; a transcript with repeated large tool results reduces instead of dying.

## Fix 2 — chat titles never stick
Device evidence: new conversations stay "New conversation" in History; a title that does appear reverts after closing and reopening the app. (Lead, not proof: the one flaky CI failure in round 4 was ConversationTitleLifecycleTest timing out at 5s.) Diagnose the whole path — when generation triggers, what it awaits, where the title persists (CodingSessions), and what overwrites it on load — then fix so a generated title appears without a restart and survives one. Tests must cover: title generated for a normal first exchange; title still there after a save/load cycle.

## Fix 3 — reasoning effort: universal selector + honest unsupported-level flow + thinking feed (kaiser's spec, verbatim intent; scope CONFIRMED by kaiser 2026-11-01: ships in v1.1.0 with the full off→ultra ladder)
Device evidence: on the River (OpenAI-compatible) provider there is NO reasoning-effort selector, and no thinking content ever reaches the Cluster E thinking block.
- The reasoning-effort selector must be available for EVERY provider, including custom OpenAI-compatible ones. Levels, in order: off, low, medium, high, xhigh, max, ultra.
- The chosen level is sent with the request in whatever form the provider accepts (reasoning_effort string for OpenAI-compatible; the existing per-provider mappings for Anthropic/Gemini/ChatGPT-plan stay). Known reality to design around: River REJECTS "medium" for DeepSeek-V4.1-Flash-1017 (verified 2026-11-01) — the flow below exists precisely for that.
- If the provider rejects the chosen level: show an error that says the model does not support that effort, offering (a) the other levels to switch to, and (b) one RECOMMENDED level = the next step DOWN the ladder from the failed one (ultra→max, xhigh→max, max→high, high→medium, medium→low, low→off). Tapping the recommendation switches the effort and resends the SAME user message automatically — the user never retypes it. Tapping another level does the same with that level.
- With a level that produces reasoning, the reasoning stream must reach the thinking block (Cluster E plumbing already exists — verify the OpenAI-compatible path end to end once effort is actually sent; DeepSeek returns reasoning_content when reasoning is on).
Scope guard: keep the existing selector UI patterns from v1.0.0 (ChatGPT-plan/Gemini) — this extends them to all providers and adds the rejection flow; do not redesign the chat screen.

## Report back
Per fix: commit hash, root cause in plain words, files changed, behaviour now, tests added. Flag anything you could not verify statically.
