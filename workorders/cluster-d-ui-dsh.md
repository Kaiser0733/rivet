# Work order — Cluster D: UI alive-signal + honesty (worker: dsh)

Repo: ~/workspace/rivet-work — you are ON branch `stability-v1.1.0` (HEAD ef9f863; Clusters A, B and C are merged into this branch — line numbers in the audit have drifted, re-locate everything). Evidence: `AUDIT-2026-11-01-eni.md` (read each finding's section first), user reports in `BUGS-next-update.md` (bug #3 is the heart of this cluster).

## Rules
- No JDK/Android SDK here: nothing compiles or runs. Small careful diffs, tests per fix where a seam exists, CI is the gate.
- Local commits only, one per fix, plain messages. NEVER push.
- Files you own in this cluster: `ui/chat/ChatScreen.kt`, `chat/ChatViewModel.kt` (UI-state parts only), `chat/ChatUiState.kt` if it exists, the providers list screen (for the F28 follow-up), `app/build.gradle.kts` (version + icon wiring only). Do NOT touch the provider clients, runtime, or storage layers — Clusters A–C own those; if a fix seems to need them, stop and say so.
- User-facing strings: plain simple words, say what actually happened. Banned: leverage, seamless, unlock, empower, robust, cutting-edge, game-changer, supercharge, elevate, "AI-powered". Same rule in code comments and test names: no filler narration.

## Fixes, in order
1. **F26 — the chat must LOOK alive while a turn runs (bug #3, kaiser's explicit ask).** The reference is dsh's own chat UI: visible step/activity rows, thinking shown as its own block, a ticking elapsed line. Today `ChatScreen.kt`'s message row hides the activity row whenever streaming text is non-blank, and before the first token there is no signal at all. Required behaviour:
   - While a turn runs, ALWAYS show a live status line in the message area: the current activity ("Thinking", "Running <tool name>", "Writing") plus elapsed time that ticks every second, in the shape "Working · 12s" (words simple, no spinner-only states).
   - Thinking/reasoning content the provider sends renders as its own visible block above the answer text while streaming (it may collapse when the turn finishes; it must never be silently dropped — audit F26/F-Tech-5).
   - Word-by-word text streaming already exists (80 ms throttle) — do not rebuild it; make sure the status line and the streaming text can be visible at the same time.
2. **F27 — failed turns get a way back, and the real reason.** A failed turn shows an inline Retry action that re-runs the last prompt, and shows the provider's own error message with HTML/tags stripped and length capped, instead of only the generic failure line.
3. **F14 — no silently dead buttons.** While a command executes, Undo/Retry currently render enabled but do nothing (the command lane bypasses the turn mutex). Make the busy state honest: either gate them behind the same mutex so they work, or render them disabled with the reason visible while a command runs. Pick the smaller correct change and say which you picked.
4. **Follow-ups from earlier clusters (small, exact):**
   - F28: `ProviderListUiState.headerNotice` is populated but never rendered — render it on the providers list screen as a plain notice line.
   - F18: the user-facing checkpoint-unavailable sentence in `ChatViewModel.finish()` still gives the generic line — make it name the real limits (50,000 items / 1 GiB) and point at `.rivetignore`, matching what the model already hears.
5. **Annoyances sweep** (from the audit's verified annoyances list — do these six, skip anything that grows teeth and report it instead): copy button on code blocks + long-press copy on messages; the keyboard's action key sends when Enter-to-send is enabled; reasoning/provider chips display the current selection; the list stays pinned to the bottom on open when the user was already at the bottom; multiline input is trimmed before send; bottom padding so the last bubble clears the input bar.
6. **Release prep (final commit):** bump `versionName` to `1.1.0` and `versionCode` to `24` in `app/build.gradle.kts`. Launcher icon: replace with the mascot at `~/workspace/goals/rivet-zero-money-launch/files/rivet-logo-final.png` (generate the mipmap densities / adaptive icon from it — ImageMagick is fine). If icon tooling is unavailable, do the version bump, flag the icon as blocked in your report, and do not improvise binary assets.

## Report back
Per fix: commit hash, files changed, user-visible behaviour now, tests added. Flag anything inspection-only and any audit line drift that mattered.
