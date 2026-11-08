# Rivet full-repo audit — prompt for the AI team (given to kaiser 2026-11-01)

You are auditing Rivet, an open-source AI coding agent that runs entirely on Android phones (Kotlin, Jetpack Compose). Users connect their own AI providers (OpenAI-compatible APIs, OpenRouter, Anthropic, Gemini, ChatGPT-plan sign-in) and Rivet reads, writes and edits project files, runs shell commands in a mirrored workspace, and syncs changes back — with an Undo/checkpoint system. The audience is developers using it for real projects on real phones, often on slow networks and cheap devices.

MISSION: a read-only audit of the WHOLE repository. Find every bug, potential bug, error path, and user-facing annoyance you can substantiate. This audit feeds a stability update, so user pain matters more than code elegance. DO NOT change any code. DO NOT fix anything. Report only.

AUDIT THESE AREAS:
1. Provider streaming (all clients, not just the OpenAI-compatible one): stalls, dropped or half-open streams, missing finish signals, heartbeat handling, timeouts of every kind, token-limit cuts, response-size caps, malformed chunks, silent catches. For every network read: what happens if it never ends, ends early, or ends weird?
2. The agent loop: runaway or dead-end loops, retries that can never succeed, tool-call parsing failures, exact-match edit failures and their recovery, context growth on long tasks, what the model is told about its environment and limits.
3. Commands + mirror workspace: how commands run, what the sandbox can and cannot execute, the mirror sync back to the real project (large trees, symlinks, binaries, partial failures), checkpoints/Undo correctness — anywhere the app could lose, duplicate, or strand a user's files.
4. Android lifecycle: backgrounding mid-run, process death, rotation/re-entry, network switches (Wi-Fi to mobile), low memory, storage full. What state survives, what silently dies?
5. UI honesty: every place the screen can claim one thing while the app does another — spinners with nothing behind them, progress that hides during the longest work, errors that blame the wrong party, dead-end errors with no real recovery, states a user cannot distinguish (working vs stuck).
6. Annoyances: extra taps, lost drafts, settings that don't stick, messages that say nothing ("something went wrong"), anything a user would hit daily and hate quietly.

KNOWN BUGS — verify each one in the code, then hunt for SIBLINGS of the same failure class in other files and other provider clients. Do not just re-report these six:
1. A streamed reply can hang forever mid-word with no error: the SSE reader has no progress deadline, and every truncation detector only fires when the stream ends. (Http.kt, OpenAiCompatibleClient.kt)
2. Switching apps mid-run kills the run with a network error — nothing keeps a run alive in the background.
3. During long writes the user cannot tell working from stuck; the activity row hides while text streams, and there is no visible thinking state or word-by-word feel.
4. A hard 1 MB cap on a streamed response kills big-file work with "The provider sent more data than Rivet can safely process" — Rivet's own limit blaming the provider, with no working recovery.
5. The model is never told the sandbox cannot execute downloaded binaries (a fetched Node binary dies with exit 126), so it retries doomed download strategies and pollutes the project with junk folders.
6. The mirror-to-project sync fails as a whole when one subtree is unsafe or huge (an extracted runtime tree), stranding the project in a pending-changes state.

METHOD: trace real call paths, don't skim. For every claim, open the code that proves it. Check every provider client with the same questions, not just the first one you find. Prefer one deep verified finding over five guesses.

REPORT FORMAT — one entry per finding:
- Title (one line, the user-visible problem, not the code smell)
- Severity: data-loss / hang / crash / wrong-result / annoyance
- Status: CONFIRMED (code path traced end to end) or POTENTIAL (plausible, name what would confirm it)
- Evidence: file + line numbers
- Trigger: the exact conditions that produce it
- What the user sees: the literal symptom on screen
- Fix shape: the smallest change that kills it (one or two lines, no code)

RULES: no invented findings — if you cannot point at the code, it does not go in. No style nits, naming opinions, or refactor wishes dressed up as bugs. Deduplicate ruthlessly. End with a top-10 list ranked by user pain, and a separate short list of the annoyances — small, daily, fixable — that a bug hunt usually misses.
