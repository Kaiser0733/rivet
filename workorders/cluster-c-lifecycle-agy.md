# Work order — Cluster C: lifecycle (worker: agy, Gemini 4 Argon medium)

Repo: ~/workspace/rivet-work — you are ON branch `stability-v1.1.0` (HEAD d16add6). Full evidence: `AUDIT-2026-11-01-eni.md` in the repo root (read each finding's section before editing). User reports: `BUGS-next-update.md` (bug #2 is F22).

## Rules
- No JDK/Android SDK on this machine: nothing compiles or runs here. Small, careful diffs; unit tests per fix; CI is the gate.
- Local commits only, one per fix, plain messages. NEVER push, NEVER touch main.
- Files you own in this cluster: `chat/ChatViewModel.kt` (turn lifecycle parts), `runtime/ManagedProcesses.kt`, `runtime/PersistentProcessService.kt`, `runtime/CommandProcess.kt`, `runtime/RuntimeController.kt` (foreground-service wiring only), `data/CodingSessions.kt` (or wherever session persistence lives — confirm from the audit's F23 evidence), `ui/provider/ProviderEditor.kt` + its view model (F25 only). The chat screen's visual surface belongs to a later UI pass — for F23 do the state/persistence and the minimal row needed to show it; do not restyle anything.
- User-facing strings: plain simple words, say what actually happened. Banned words: leverage, seamless, unlock, empower, robust, cutting-edge, game-changer, supercharge, elevate, "AI-powered".
- If a usage limit stops you: leave the tree committed and clean, say exactly where you stopped.

## Fixes, in order
1. **F22 — a turn must survive backgrounding (the big one).** Today the agent turn runs in `viewModelScope` with no foreground service or wake lock; the only `startForeground` in the codebase serves previews (`RuntimeController.kt` → `ManagedProcesses.ensureForegroundService` → `PersistentProcessService`). Run the agent turn under a foreground service, reusing the existing preview service machinery as the model: service starts when a turn starts, shows an ongoing notification ("Rivet is working — tap to return"), and stops when the turn ends (completed, failed, or stopped). Handle the Android 12+ constraints the existing service already deals with. If the platform still kills the turn, F05 (already landed) keeps the partial text — your job is to make the kill rare, and never silent.
2. **F23 — a dead turn must not look like a normal empty turn.** `CodingSessions.recoverInterrupted` only synthesises interrupted results when the last message has a pending tool call; death before the first assistant message leaves a bare user prompt. Persist a turn-in-progress marker when a turn starts, clear it when the turn completes, and on restore render a simple "This turn was interrupted — Retry?" row (state + minimal UI only) when the marker survives without a completed turn.
3. **F25 — rotation must not silently kill a ChatGPT sign-in.** `ProviderEditor.kt` launches sign-in in `rememberCoroutineScope()` and cancels the session in `DisposableEffect.onDispose`; `signingIn`/`signInError` are plain `remember`. Move the sign-in flow into the provider editor's ViewModel scope and hold its state (including the error) where a configuration change cannot destroy it.
4. **F24 — a frozen process must not fake a command timeout.** `CommandProcess.kt` (~:80) measures wall-clock across `withTimeoutOrNull` while `waitFor` runs on its own thread; after backgrounding, the timeout branch can win over an already-completed exit. Before treating the timeout branch as real, check `exited.isCompleted` and report the real exit if the process already finished.

## Report back
Per fix: commit hash, files changed, user-visible behaviour now, tests added/updated, anything not verifiable statically. Flag any place the audit's line numbers drifted (Clusters A and B are already merged into this branch — cite current line numbers).
