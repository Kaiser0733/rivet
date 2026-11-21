# Manual compaction from the slash panel

Branch `manual-compact` starts at `c0f9dfc2` (redesign head). The slash
panel's Compact row is now a real, user-triggered compaction of the current
session, usable when no run is active. It runs the same preparation and
checkpoint machinery the automatic flow uses — `ContextPreparation.prepare`
into `CodingSessions.compact`, with the same six-section checkpoint, the
same merge-don't-re-summarise behaviour and the goal pinned verbatim — and
leaves the same persisted compaction notice row. No second, parallel
compaction implementation was added.

## Changes by item

| Item | Commits | What changed |
| --- | --- | --- |
| Trigger decision | `8cb92994` | `chat/ManualCompaction.kt` adds the pure seam: `CompactTrigger { Fire, RunActive, NothingToCompact }`, `compactTrigger(streaming, hasMessages)` and `compactRowEnabled(streaming)`. A running reply owns the transcript and already compacts inside its turn; an empty session is a plain no-op. `chat/ManualCompactionTest.kt` covers all three outcomes on the JVM. |
| Manual compaction | `8845b967` | `chat/ChatViewModel.kt` adds `compactHistory()` (+121 lines, nothing else in the file changed). It calls `ContextPreparation.prepare(activeMessages, force = true, reason = "manual")` with the request shape the next turn will send (same `systemInstruction`, the same tool-definition filter, local wire and vision preparation), so the estimate and budget match the automatic path. The compacted projection is committed by the existing `ContextStore.compact`; the marker row is `InternalContext.compactionNotice(workspaceId)`, persisted with `CodingSessions.save(..., expectedSessionId)` bound to the current session — the same pattern `recordTerminalRestoreNotice` uses — and appended to the visible transcript. `activeSummary` and the context estimate refresh afterwards. While it runs, `streaming = true` with activity "Compacting…" reuses the turn-busy pattern: send, session switch, project switch, undo and retry all refuse, and the composer's Stop cancels it through `sendJob`. Six Robolectric tests in `chat/ChatViewModelTest.kt` cover the summarise, fresh-session no-op, nothing-worth-compacting no-op, run-active refusal, failure and cancellation behaviours. |
| Compact row | `70157a75` | `ui/chat/SlashMenu.kt`: `SlashRow.enabled` is now set at construction; `slashRows` takes `compactEnabled` and the Compact row is enabled by default with dsh's description "Compact older conversation history". `ui/chat/SkillCommandSheet.kt`: the "Runs automatically" copy is gone; a disabled Compact row (run active) exposes "Wait for the reply to finish" to accessibility, the same unavailable-row pattern agent-only skills use. `ui/chat/ChatScreen.kt`: the panel wires `SlashAction.Compact -> onCompact()`, `InputBar` passes `compactEnabled = compactRowEnabled(streaming)`, and `ChatScreen` binds `onCompact = chatViewModel::compactHistory`. `ui/chat/SkillCommandSheetTest.kt` flips the old disabled assertion and adds the run-active row test. `scripts/test_manual_compact.py` is the source-level JiTTest for the whole wiring. |

Paths without a prefix are relative to `app/src/main/java/com/kaiser/rivet/`
(tests under `app/src/test/java/com/kaiser/rivet/`).

## Edge cases

- Run active: the row is disabled with a plain state description; a tap that
  races a starting reply gets the notice "Cannot compact while a reply is
  running." and nothing else happens.
- Fresh/empty session: "Nothing to compact yet." before any provider or
  storage call. A short session with content goes through the real
  preparation; when it returns the transcript unchanged the same plain
  notice appears — no fake success, no crash, no provider call.
- Failure inside preparation: the existing error copy "Rivet could not
  shorten this session's context. The conversation was kept." surfaces; the
  session is untouched (the store only writes on a successful commit, and
  `prepare` records its own diagnostics). Provider-configuration failures
  use the existing `providerSnapshot`/`failBeforeStart` path.
- Stop during compaction: a committed compaction stays durable; the view
  model resynchronises from the store. A cancelled summariser leaves the
  session exactly as it was.
- If only the marker row's save fails after a committed compaction, the view
  model resynchronises from the store and surfaces the existing storage
  error copy (or `CONTEXT_LIMIT_ERROR` for a limit failure).

## Verification — real results

- `python3 -m unittest discover -s scripts -p 'test_*.py'` on the branch:
  **67 tests, all passed** (61 pre-existing + 6 new in
  `test_manual_compact.py`). The same new file run against a `c0f9dfc2`
  tree: **6/6 red** (3 assertion failures, 3 errors from the missing
  `compactHistory` path).
- Isolated Kotlin/JUnit harness (JDK 17.0.13, embeddable kotlinc 2.0.21,
  JUnit 4.13.2), compiling the production slices `agent/Skill.kt`,
  `ui/chat/SlashMenu.kt`, `chat/ManualCompaction.kt` with
  `SkillCommandSheetTest` and `ManualCompactionTest`: **9 tests, all
  passed**. Red on the parent: `ManualCompactionTest` does not compile
  against `c0f9dfc2` (the seam does not exist — unresolved references), and
  the flipped `builtInsKeepTheirOrderAndFilterByToken` assertion fails at
  runtime against the parent's `SlashMenu.kt` (Compact disabled there);
  the other four row tests pass on both trees.
- The six new Robolectric tests and the updated row tests are part of
  `testDebugUnitTest`; they were **not executed here** (see gaps). They
  follow the neighbouring compaction tests' fixtures; the summary test's
  goal size was checked against the real summary budget: the no-workspace
  tool set measures 30 definitions / 15,438 bytes ≈ 5,146 tokens (extracted
  production definitions, measured with the `ContextBudget` formula), which
  leaves the 2,010-byte pinned goal comfortably inside the budget.
- Parse/profile checks: `ChatViewModel.kt`, `ChatViewModelTest.kt`,
  `ChatScreen.kt` and `SkillCommandSheet.kt` were compiled with the
  embeddable compiler without the Android classpath and their diagnostics
  diffed against the parent files. No parse errors; every new diagnostic is
  an unresolved Android/classpath reference inside the inserted regions
  (the parent shows the same classes of error), and the seam references
  resolve when their definitions are included. This is a syntax and
  reference-shape check, not a type check.
- `git diff --check c0f9dfc2..HEAD` passed. The code diff against the base
  (excluding this report) is 9 files, +472/−9; `ChatViewModel.kt` changes
  are purely additive. The app build files, manifest, gradle wrapper and
  updater code are byte-identical to the base. No version change, no new
  dependencies, no APK, no merge, no PR, no tag.

## Honest gaps

- **The Gradle/Android suite did not run in this environment.** There is no
  Android SDK or NDK here (`ANDROID_HOME` unset, no `local.properties`, no
  SDK directory), and the Gradle 8.13 daemon fails to start at all: three
  attempts at `:app:testDebugUnitTest` (including `--no-daemon` and a
  reduced heap) each ended with "Could not receive a message from the
  daemon" before configuration, with ~700 MB of RAM available. The six new
  Robolectric tests, the updated `SkillCommandSheetTest`, and the rest of
  the unit suite need CI ("Android Build") as their gate.
- The Kotlin harness used compiler 2.0.21 while the repo pins 2.1.10; it
  compiled only the pure slices, which use no 2.1-specific language
  features. The Compose files (`ChatScreen.kt`, `SkillCommandSheet.kt`)
  were parse/profile-checked only — no full Compose compilation was
  attempted here.
- No on-device check of the tap flow, the notice row's rendering, the
  disabled row's accessibility description, or Stop during a compaction.
- Typing `/compact` and pressing Send still shows "Unknown command", as it
  does for the other built-in rows (`/yolo`, `/feedback`); the brief's
  trigger is the row tap, and `send()` was left unchanged.
- Manual compaction takes no run checkpoint: it changes only the transcript,
  never project files, matching `recordTerminalRestoreNotice`. The
  automatic in-run compaction keeps riding its turn's checkpoint; that
  path is untouched.
- The tool-size measurement used kotlinx-serialization-json 1.6.2 (repo:
  1.7.3); `JsonObject.toString()` output is equivalent for the estimate.
