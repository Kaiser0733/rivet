# Rivet next update — merged fix list (stability update)

Built 2026-11-01 from two sources:
1. **Kaiser's dogfooding session** — 6 bugs found on his own phone (`BUGS-next-update.md`).
2. **ENI full-repo audit** — 32 findings F01–F32 at commit `c0c49c8` (`AUDIT-2026-11-01-eni.md`).

**All 6 dogfooding bugs were independently CONFIRMED in the audit**, each with siblings: #1→F01 (+F02, F07), #2→F22 (+F23, F24), #3→F26, #4→F03 (+F04, F06), #5→F21 (+F17), #6→F11 (+F12, F18, F20). Our own dsh diagnosis of bug #1 matches F01's mechanism exactly — three reads, same answer.

The audit's core insight — the defects cluster in three design choices:
1. The stream reader only knows two states and guesses wrong about both (heartbeat = "progress", missing `[DONE]` = "parse error").
2. The error vocabulary handed to the model is narrower than the one the code produces — good explanations are written, then thrown away before the model sees them.
3. Every per-file/per-turn boundary is whole-tree or nothing — sync, snapshot, checkpoint, materialise all fail as a unit and all re-walk the whole project.

Nothing needs an architecture change. Phases below are ordered by user pain removed per effort.

## Phase 1 — the five small fixes that remove most of the pain
- **F01** Progress deadline in the SSE read loop: abort with `ProviderError.Timeout` when no real delta arrives within the budget, regardless of heartbeat bytes. (Known bug #1.)
- **F02** Accept a response when a finish signal was seen; missing `[DONE]` = clean EOF, not `InvalidResponse`. Truncation gets its own error. Applies to all four clients.
- **F05** Persist non-blank partial text before rethrowing ANY error from the request path (today only the Stop/cancel branch keeps it — a network error deletes the answer the user just watched arrive).
- **F08+F09** Add the missing codes (`patch`, `conflict`, `too_large`, `missing`, `binary`, `limit`, …) to `AgentToolError.actions` and the `deterministic` set — or carry `WorkspaceFailure.Reason.message` through. Kills blind model retries and the 200-turn burn on one uneditable file.
- **F22** Run the agent turn in a foreground service (the preview service is the model) so backgrounding stops killing runs. (Known bug #2.)

## Phase 2 — caps, sync and lifecycle hardening
- **F03** Raise/stream the 1 MiB response cap for agent turns; the error must name Rivet as the limiter and offer "write it in parts — continue?" (Known bug #4.)
- **F04** The model-list endpoint must not die on the same cap — stream it, keep id/limits, cap retained count.
- **F06** On a length-cut tool call, keep the turn and tell the model to write the change in smaller pieces instead of discarding everything.
- **F07** (POTENTIAL — needs a captured Responses-API `error` event) Handle the top-level `error` event in `ChatGptPlanClient` and surface the provider's real reason.
- **F10** Files over 1 MiB: allow line-range edits above the in-memory limit; at minimum return an explanation naming the limit.
- **F11** Mirror snapshot: skip-and-report unsafe entries (symlinks et al) per path instead of aborting the whole sync. (Known bug #6 — the `npm install` case.)
- **F12** Sync everything unconflicted; name the blocking path in the tool result.
- **F15** Failed post-Undo sync: mark the checkpoint unusable and give one explicit "project may be in either state" recovery instead of a half-applied Undo that repeats forever.
- **F18** Checkpoint scan must honour an ignore list (.gitignore) and the error must name the real limit (50k files / 1 GiB), not blame storage.
- **F20** (POTENTIAL — needs a force-stop experiment during materialise) Treat a partially deleted `previous` tree as reconcilable; add a visible "reset this project's runtime copy?" recovery.
- **F23** Persist a turn-in-progress marker; on restore show an "interrupted — retry?" row instead of a silently empty turn.
- **F24** (POTENTIAL) Check `exited.isCompleted` before declaring a command timeout after backgrounding.
- **F25** ChatGPT sign-in moves to ViewModel scope; its error survives rotation/config changes.

## Phase 3 — settings safety (small code, catastrophic failure modes)
- **F28** Keystore loss must not dead-end Settings: on decrypt failure, drop the unreadable header/config and rewrite the store so it can be edited again. (Today: only fix is clearing app data = losing all conversations.)
- **F29** (POTENTIAL) `ProviderStore.decode` failure must throw, never write back an empty list over the user's other providers.
- **F30** (POTENTIAL) Catch `RuntimeException` at SecretStore encrypt/decrypt entry points.
- **F31** (POTENTIAL) Guard provider delete/select writes like every sibling write.

## Phase 4 — performance on cheap phones + UI honesty
- **F13** Incremental `localSnapshot` (mtime+size before hashing) and diff-only `materialize` — today every read re-hashes the whole project and every command after an edit re-copies it.
- **F19** Checkpoints: content-addressed deltas or hard links instead of a full zip per turn (up to 3 retained = up to 4 copies of the project with the mirror).
- **F32** Memoise SAF `children()`/documentId per operation — deep paths currently re-query every directory level per file op.
- **F16** Command output capture: 8 KB head/tail drops the middle where compiler errors live — widen the window or keep error/fail/exception lines first.
- **F17** Exit 126: write a diagnostic to stderr in the JNI child for setup failures; keep a distinct code for Rivet-internal failures vs "not executable". (Pairs with known bug #5.)
- **F21** One paragraph in the system prompt: actual PATH/toolchain, no package manager, downloaded binaries can't execute, size ceilings. (Known bug #5.)
- **F26** Render an alive signal (spinner or ticking elapsed time) whenever a turn is streaming — unconditionally, not suppressed by streamingText/liveOperation. This is known bug #3; design reference = kaiser's dsh chat (visible steps + "working for Ns" ticker). ChatGPT/Claude-style word-by-word text already exists (80 ms throttle) — the missing piece is the alive signal when text is NOT arriving.
- **F27** Show a sanitised, length-capped provider error message and give every failed turn a Retry action.
- **F14** Runtime mutex: hold it only for mirror-touching phases, not across command execution; disable Undo/Retry/Discard visibly while held.

## Annoyances sweep (from the audit — cheap, daily)
20s timeout on model lists · no Retry on failed turns · "No usage reported" shown while loading · decrypted headers render unmasked · typing an API key clears the fetched model list · provider editor titled "Change model" · no Cancel during update Verify/Save · cancelling the save picker deletes the verified APK · no resume on update downloads · stuck `pickerOpen` disables Save update · no Stop for a process stuck in Stopping · directory delete hashes up to 1 GB first · `list_directory` capped at 200 with no pagination · `read_file` chunks at 8 KB · `search_files` literal/case-sensitive only · Undo limited to 3 turns, undocumented · unexpected tool exceptions report bare "tool_failed".

## Before building — device experiments for the POTENTIAL findings
F07 (capture a Responses `error` event), F20 (force-stop mid-materialise), F24 (background a long command past its timeout), F28/F29/F30/F31 (keystore/store fault injection). Each is named in the audit with its experiment. Confirm or kill them before writing fixes.

## Verified clean (don't re-audit)
Updater (signer/version verified, allowlisted redirects), conversation persistence (real transactions, invariants), approval policy (no bypass), agent-loop structure, path validation. Detail in the audit's Clean section.
