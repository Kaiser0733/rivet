# Hydrogen Whole-App Audit Report

- **Target worktree**: `/home/hatch/workspace/rivet-hydrogen-audit`
- **Branch**: `audit/hydrogen-whole-app`
- **Baseline commit**: `420c61b8` (`Bump to 1.4.5 / versionCode 34 for the batch test APK`)
- **Constraints honored**: No version changes (`versionCode` 34 / `versionName` `1.4.5`), no new features or UI redesigns, no dependency changes, self-updater (`updates/`, `UpdatesSection.kt`) untouched, recent surface batch preserved unless a concrete bug is fixed cleanly without regressing its behavior, no push / merge / APK build.
- **Verification environment note**: Gradle cannot run in this VM; pure-Kotlin JiTTests are compiled and executed via `/home/hatch/tmp/jdk-17.0.13+11/bin/java` + `K2JVMCompiler` (`kotlin-compiler-embeddable-2.0.21.jar`) + `junit-4.13.2.jar` (+ `kotlinx-coroutines-core-jvm-1.6.4.jar`, `kotlinx-serialization-json-jvm-1.6.2.jar`, `jsoup-1.15.3.jar` where applicable), and modified Android/Compose `.kt` files are checked structurally with `python3 round3/kt-checks.py balance` / `symbols`. Fixes requiring Android framework, C++ NDK, OkHttp/MockWebServer, JGit, or Compose UI runtime also ship JiTTests in `app/src/test` and are marked `NEEDS-DEVICE-PROOF` until executed in CI/device Gradle test run.

---

## Audit Coverage

- Audited all **230** Kotlin/C++ source files across `app/src/main` and all **183** test files across `app/src/test` (`agent`, `browser`, `chat`, `local`+`cpp`, `provider`, `runtime`+`processes`+`terminal`, `storage`, `ui`, `workspace`). `updates/` and `UpdatesSection.kt` inspected for boundary awareness only and left untouched.

---

## Findings Summary by Severity

- **(a) Crashes / Data Loss / Corruption**: `H1`–`H15` (15 findings)
- **(b) Logic Flaws**: `H16`–`H38` (23 findings)
- **(c) Race / Lifecycle / Resource Leaks**: `H39`–`H45` (7 findings)
- **(d) Security-by-Structure Holes**: `H46`–`H51` (6 findings)
- **(e) Dead / Unreachable Misleading Code**: `H52` (1 grouped cleanup finding)

---

## Ranked Findings & Plan

### Severity (a) — Crashes / Data Loss / Corruption

#### H1 — Native JNI UAF/Double-Free, Modified-UTF-8 `NewStringUTF` crash, null grammar sampler, and KV-exhausting prompt truncation
- **Status**: `NEEDS-DEVICE-PROOF` (Kotlin `LlamaModel` close/handle-zeroing JiTTest `LlamaBridgeTest` RED->GREEN on JVM; `LocalModelManager.kt` lock guards verified via `kt-checks.py balance`; C++ NDK `rivet_llama.cpp` requires NDK/device run)
- **Files**:
  - `app/src/main/cpp/rivet_llama.cpp:111-125, 174-205, 222-275`
  - `app/src/main/java/com/kaiser/rivet/local/LlamaBridge.kt:1-65`
  - `app/src/main/java/com/kaiser/rivet/local/LocalModelManager.kt:118-132, 194-225`
- **Issue**:
  1. `nativeFreeModel` deletes `RivetModel*` while `LlamaModel.handle` is never zeroed on `close()`, and `nativeStop` / `nativeTokenize` dereference raw handles without lifetime synchronization (`LocalModelManager.stopGeneration` / `remove` / `tokenCount` read `active` or invoke native calls outside `nativeLock`).
  2. `env->NewStringUTF(text.c_str())` crashes ART CheckJNI on invalid Modified-UTF-8 (split multi-byte BPE pieces or 4-byte emoji/supplementary plane codepoints), `piece[512]` drops long tokens (`piece_len <= 0`), and `env->ExceptionClear()` swallows JVM exceptions inside token callbacks.
  3. `llama_sampler_init_grammar` return value is not null-checked before `llama_sampler_chain_add`.
  4. `n_tokens > state->n_ctx` erases the beginning of the prompt (system prompt) and leaves `0` KV slots for generation (`n_ctx - n_tokens == 0`).
- **Fix**: Guard handle lifecycle (zero `LlamaModel.handle` atomically on `close()`, hold `nativeLock` consistently in `LocalModelManager`), emit UTF-16 (`NewString`) / valid UTF-8 chunks with dynamic piece buffer, null-check `llama_sampler_init_grammar`, preserve/throw on JVM exception, and reserve generation room when `n_tokens >= n_ctx`.
- **JiTTest**: Pure-Kotlin JiTTest for `LocalModelManager` / `LlamaBridge.close()` idempotence/handle zeroing where testable; full JNI/NDK test marked `NEEDS-DEVICE-PROOF`.

#### H2 — `ModelDownloadService.onStartCommand` triggers `ForegroundServiceDidNotStartInTimeException`
- **Status**: `NEEDS-DEVICE-PROOF` (Robolectric `ModelDownloadServiceTest` added; `ModelDownloadService.kt` + `ModelDownloadServiceTest.kt` verified with `kt-checks.py balance`; requires Android/Gradle Robolectric run)
- **Files**: `app/src/main/java/com/kaiser/rivet/local/ModelDownloadService.kt:47-82`
- **Issue**: When started via `startForegroundService()` and `LocalRuntime.downloads == null` or `active == null`, `onStartCommand` calls `stopSelf()` and returns `START_NOT_STICKY` without calling `startForegroundCompat(...)`, crashing on Android 8+.
- **Fix**: Always call `startForegroundCompat(...)` with a minimal status notification before `stopSelf()` when `onStartCommand` bails early.
- **JiTTest**: Robolectric `ModelDownloadService` JiTTest verifying `startForeground` is invoked before `stopSelf()` when no active job exists (`NEEDS-DEVICE-PROOF` in VM without Gradle/Robolectric).

#### H3 — `TurnCheckpoint.stageUndo` deletes `.gitignore`/`.rivetignore` trees on SAF sync and crashes on valid POSIX filenames
- **Status**: `NEEDS-DEVICE-PROOF` (`TurnCheckpointTest` JiTTests added for ignored trees + trailing-dot POSIX filenames; `TurnCheckpoint.kt` & `TurnCheckpointTest.kt` pass `kt-checks.py balance`; requires Gradle `kotlinx-serialization` plugin + `kotlinx-coroutines-test` to execute)
- **Files**: `app/src/main/java/com/kaiser/rivet/runtime/TurnCheckpoint.kt:141-172, 256-259`
- **Issue**:
  1. `TurnCheckpoint.scan` skips `.gitignore`/`.rivetignore` directories, so `before.zip` omits them; `stageUndo` builds `stage` only from `before.zip`, then `WorkspaceMirror.installCheckpoint` + `sync()` permanently deletes all ignored trees (e.g. `node_modules/`, `dist/`) from worktree and SAF.
  2. `stageUndo` validates zip entries with `WorkspacePath.parse`, crashing on valid POSIX names (e.g. `foo.`).
- **Fix**: Preserve ignored non-symlink directories from `current` when staging `stageUndo` (and skip ignored entries consistently on `stage` scan vs `current` if excluded), and replace `WorkspacePath.parse` in `stageUndo` with explicit relative path traversal validation.
- **JiTTest**: Pure-Kotlin JVM JiTTest (`TurnCheckpointTest`) creating a `.gitignore`d dir (`node_modules/pkg.txt`) and a POSIX filename (`foo.`) before `begin()`/`finish()`/`stageUndo()`.

#### H4 — `WorkspaceMirror.localSnapshot` crashes `prepare()`/`sync()` on SAF-invalid POSIX filenames
- **Status**: `NEEDS-DEVICE-PROOF` (`WorkspaceMirrorTest.safInvalidPosixFilenamesAreSkippedWithoutCrashingSyncOrPrepare` added; `WorkspaceMirror.kt` & `WorkspaceMirrorTest.kt` pass `kt-checks.py balance`; requires Android/Gradle test run for SAF `SafWorkspace`/`Context` dependencies)
- **Files**: `app/src/main/java/com/kaiser/rivet/runtime/WorkspaceMirror.kt:539`
- **Issue**: `relative.child(child.fileName.toString())` throws unhandled `WorkspaceFailure(INVALID_PATH)` on names ending in `.`, blank names, `\`, or depth > 64, bricking `runCommand`/`openTerminal`.
- **Fix**: Catch `WorkspaceFailure` on child name construction in `localSnapshot` and record the relative path in `skipped` (matching symlinks/special files).
- **JiTTest**: Robolectric JiTTest in `WorkspaceMirrorTest` (`NEEDS-DEVICE-PROOF` offline).

#### H5 — `RunCheckpointStore.stageRestore` fails on new directories, file↔directory replacements, and gitignored files
- **Status**: `NEEDS-DEVICE-PROOF` (`RunCheckpointStoreTest` JiTTests added for deepest-first dir deletion, file↔dir swaps, and gitignored file delta fallback; `RunCheckpointStore.kt` & `RunCheckpointStoreTest.kt` pass `kt-checks.py balance`; requires Gradle JGit + `kotlinx-serialization` plugin to execute)
- **Files**: `app/src/main/java/com/kaiser/rivet/runtime/RunCheckpointStore.kt:129-140, 265-269, 329-358`
- **Issue**:
  1. Ascending loop calls `target.delete()` on non-empty added directories (leaving empty dirs behind), crashes with `FileNotFoundException` when a file was replaced by a directory, and deletes without recreating empty directories replaced by files.
  2. `RunCheckpointStore.scan` includes gitignored files outside the 6 hardcoded top-level names while `GitCheckpoint.commit` omits them via `git add .`, throwing `CheckpointFailure("corrupt")` on restore.
- **Fix**: Remove added/type-changed paths deepest-first via `removeTree`, recreate `before.directory == true` dirs via `mkdirs()`, and store delta copies (or avoid `.gitignore` exclusion) for gitignored files in git-backed checkpoints.
- **JiTTest**: Pure-Kotlin JVM JiTTest for non-git `stageRestore` (new dirs, file↔dir swap, empty dir); JGit test in `RunCheckpointStoreTest`.

#### H6 — Cancelled/paused `ChatViewModel.startTurn` runs suspending cleanup in `finally` without `NonCancellable`
- **Status**: `NEEDS-DEVICE-PROOF` (`ChatViewModelTest.pauseCurrentRunPersistsPausedRecoveryAndReleasesTurnEvenWhenCancelled` added; `ChatViewModel.kt` & `ChatViewModelTest.kt` pass `kt-checks.py balance`; requires Gradle Robolectric test run)
- **Files**: `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt:1045-1063, 1867-1889, 1975-2029`
- **Issue**: On pause/cancel, `sendJob` is already cancelled when `finally` runs: suspending calls (`finishRunCheckpoint`, `runJournal.save`, `sessionStore.markInterrupted`, `finishRun`) throw `CancellationException`, losing paused run recovery state, skipping `runtimeController.finishTurn(turnToken)` (leaking `PersistentProcessService` foreground state) and `queuedSessionId` switch, and overwriting the `budgetPaused` notice at line `1886`.
- **Fix**: Wrap `finally` (`1980-2029`) in `withContext(NonCancellable)` and preserve `budgetPaused` notice at line `1886`.
- **JiTTest**: Robolectric `ChatViewModelTest` JiTTest (plus pure-Kotlin turn-finalization test if extracted).

#### H7 — `persistAcceptedTail` persists preflight-refused prompts and retains over-limit prompts in memory
- **Status**: `NEEDS-DEVICE-PROOF` (`ChatViewModelTest` JiTTests added for AccessLost refusal and pre-persist `AgentSessionLimitException` rollback; `ChatViewModel.kt` & `ChatViewModelTest.kt` pass `kt-checks.py balance`; requires Gradle Robolectric test run)
- **Files**: `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt:745-760, 1329-1377, 1511-1536, 1976-1979, 2236-2254`
- **Issue**: When `startTurn` returns early on preflight refusal (`AccessLost`, `Mismatch`, missing provider, blocked local protocol) or `AgentSessionLimitException`, `finally` calls `persistAcceptedTail(accepted)`, writing refused prompts into SQLite with `interrupted = true` or leaving over-limit messages in `_uiState.messages`.
- **Fix**: Only call `persistAcceptedTail` when cancellation occurred before initial persistence after preflight passed, and roll back the optimistic `accepted` message from `_uiState.messages` on preflight/limit refusal.
- **JiTTest**: Robolectric `ChatViewModelTest` JiTTest (`NEEDS-DEVICE-PROOF` offline).

#### H8 — `GoalMemoryContext`, `ProjectContext`, and `SkillContext` can exceed `InternalContext` caps or fail `valid()`, crashing `AgentLoop`
- **Status**: `NEEDS-DEVICE-PROOF` (JiTTests added in `GoalMemoryTest`, `InternalContextTest`, `SkillContextTest`; all modified files pass `kt-checks.py balance`; requires Gradle `kotlinx-serialization` compiler plugin to run `@Serializable` models)
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/agent/GoalMemory.kt:256-281`
  - `app/src/main/java/com/kaiser/rivet/agent/InternalContext.kt:35-38, 214-218`
  - `app/src/main/java/com/kaiser/rivet/agent/SkillContext.kt:66-68`
- **Issue**:
  1. `GoalMemoryContext.content` (`258`) permits 200*300 chars (> 32KB `MAX_CONTENT_BYTES` on ASCII/UTF-8), triggering `require(valid)` crash at `AgentLoop.kt:101`.
  2. `GoalMemoryContext.updates(..., workspace = null, ...)` emits `kind = "goal"` with `workspaceId = null`, failing `InternalContext.valid()` line `37`.
  3. `InternalContext` redaction can expand an 8KiB `AGENTS.md` past `PER_FILE_BYTES`.
  4. `SkillContext` multi-byte UTF-8 slice producing `U+FFFD` can exceed `MAX_CONTENT_BYTES`.
- **Fix**: Enforce UTF-8 byte capping in `GoalMemoryContext`, `ProjectContext`, and `SkillContext` using exact UTF-8 byte limits, and skip/reject `GoalMemoryContext.updates` when `workspaceId.isNullOrBlank()`.
- **JiTTest**: Pure-Kotlin JVM JiTTest verifying `InternalContext.valid()` on all four edge cases.

#### H9 — `DirectoryRunStorage` writes `run.json` non-atomically on caller thread and lacks `run.id` path guard
- **Status**: `NEEDS-DEVICE-PROOF` (`RunJournalTest.rejectsPathTraversalInRunIdAndWritesAtomicallyWithoutLeavingTempFiles` added; `RunJournal.kt` & `RunJournalTest.kt` pass `kt-checks.py balance`; requires Gradle `kotlinx-serialization` compiler plugin to run)
- **Files**: `app/src/main/java/com/kaiser/rivet/agent/RunJournal.kt:128-142`
- **Issue**: `DirectoryRunStorage.save` writes directly to `run.json` without temp+atomic rename, runs file I/O on caller dispatcher without `Dispatchers.IO`, and uses unvalidated `run.id` in `File(...)`.
- **Fix**: Validate safe `run.id` (no `/`, `\`, `..`), run under `withContext(Dispatchers.IO)`, and write to a temp file followed by atomic replace/rename.
- **JiTTest**: Pure-Kotlin JVM JiTTest (`RunJournalTest`).

#### H10 — Uncaught `jsonPrimitive` exception in `ChatGptAuth` on non-primitive `expires_in` / `exp`
- **Status**: `NEEDS-DEVICE-PROOF` (`ChatGptPkceTest` + `ChatGptIdTokenTest` JiTTests added; `ChatGptAuth.kt`, `ChatGptPkceTest.kt`, `ChatGptIdTokenTest.kt` pass `kt-checks.py balance`; requires Gradle OkHttp dependency to execute)
- **Files**: `app/src/main/java/com/kaiser/rivet/provider/ChatGptAuth.kt:188, 342`
- **Issue**: `obj["expires_in"]?.jsonPrimitive` and `claims["exp"]?.jsonPrimitive` throw `IllegalArgumentException` if JSON contains an object or array (`{"expires_in":{}}`, `{"exp":[]}`).
- **Fix**: Cast via `(elem as? JsonPrimitive)` safely without throwing.
- **JiTTest**: Pure-Kotlin JVM JiTTest (`ChatGptAuth` parseTokenResponse / validateIdToken).

#### H11 — `ProvidersViewModel.fetchModels` silently persists unsaved editor edits
- **Status**: `NEEDS-DEVICE-PROOF` (`ProviderEditorRequestsTest.fetchModelsOnExistingProviderDoesNotPersistUnsavedEditorChangesBeforeSave` added; `ProvidersViewModel.kt` & `ProviderEditorRequestsTest.kt` pass `kt-checks.py balance`; requires Gradle Robolectric test run)
- **Files**: `app/src/main/java/com/kaiser/rivet/ui/provider/ProvidersViewModel.kt:746-763`
- **Issue**: While editing an existing provider, `fetchModels` calls `store.save(updated)` whenever `nextConfig != it.config`, committing unsaved edits (`name`, `baseUrl`, `headers`, `reasoning`) even if the user later clicks Cancel.
- **Fix**: Keep refreshed model metadata in `_editorState` without calling `store.save(updated)` inside `fetchModels()` (or update only previously stored metadata without saving uncommitted fields).
- **JiTTest**: Robolectric `ProvidersViewModel` JiTTest (`NEEDS-DEVICE-PROOF` offline).

#### H12 — `SkillInstallDialog` crashes on non-`SkillInstallFailure` exceptions and re-triggers import on rotation
- **Status**: `NEEDS-DEVICE-PROOF` (Pure-Kotlin `SkillInstallStepPolicyTest` PASS `OK (2 tests)` on JVM; `SkillInstallDialog.kt` & helpers pass `kt-checks.py balance`; Compose/Activity configuration rotation UI verification pending device/CI run)
- **Files**: `app/src/main/java/com/kaiser/rivet/ui/provider/SkillInstallDialog.kt:88-116, 140-142, 175-205`
- **Issue**: `SecurityException` / `FileNotFoundException` / generic `Exception` escape `openZip`/`submitLink`/`ChooseStep`/`ReviewStep.onInstall` unhandled; `step` is not saved and `LaunchedEffect(initialZip)` re-runs on activity recreation.
- **Fix**: Catch `Exception` (rethrowing `CancellationException`) into `InstallStep.Failed(...)` across all install paths and track processed `initialZip` across configuration changes.
- **JiTTest**: Compose / Robolectric JiTTest (`NEEDS-DEVICE-PROOF` offline).

#### H13 — `BrowserSession.enqueueDownload` crashes on non-HTTP(S) URIs (`blob:`, `data:`)
- **Status**: `FIXED` (`BrowserDownloadPolicyTest` PASS `OK (2 tests)` on JVM for `http`/`https` scheme validation & filename sanitization; `BrowserSession.kt` passes `kt-checks.py balance`)
- **Files**: `app/src/main/java/com/kaiser/rivet/browser/BrowserSession.kt:1958-1970`
- **Issue**: `DownloadManager.Request(Uri.parse(prompt.url))` and `setDestinationInExternalFilesDir` run outside `runCatching`, crashing the main thread on `blob:`/`data:` URLs or invalid paths.
- **Fix**: Validate `http`/`https` scheme via pure `BrowserDownloadPolicy` helper, sanitize filename, and move request construction inside `runCatching`.
- **JiTTest**: Pure-Kotlin JVM JiTTest for `BrowserDownloadPolicy.isSupportedDownloadUrl` / `sanitizeFileName`.

#### H14 — Cross-tab snapshot corruption, premature form restore, and broken `clearBrowserData()`
- **Status**: `NEEDS-DEVICE-PROOF` (Robolectric `BrowserSessionClearDataTest` added; `BrowserSession.kt` & `BrowserSessionClearDataTest.kt` pass `kt-checks.py balance`; requires Android/Gradle Robolectric WebView/CookieManager run)
- **Files**: `app/src/main/java/com/kaiser/rivet/browser/BrowserSession.kt:164, 1115-1123, 1175-1192, 1685-1698`
- **Issue**:
  1. `saveActiveSnapshot()` reads mutable `activeTitle` after async `evaluateJavascript` when `selectWindow()` has already overwritten `activeTitle`, and writes empty fields when JS returns `"null"`.
  2. `loadSnapshotState()` runs via `view.post` before `onPageFinished`, injecting form values into the old page DOM.
  3. `clearBrowserData()` deletes `cacheDir/browser-snapshots` instead of `filesDir`, and leaves `windows`, `activeWindowId`, `trail`, `shotStore`, `readerCache`, `elementRefs`, and `_state.windows`/`domains` populated in memory.
- **Fix**: Capture `val titleSnapshot = activeTitle` synchronously, ignore `"null"` JS results, defer form restoration to `onPageFinished` for the active URL/window, target `filesDir/browser-snapshots` (via `snapshots.clear()`), and reset in-memory browser session collections on `clearBrowserData()`.
- **JiTTest**: Robolectric / Android `BrowserSession` JiTTest + pure helper test where extractable.

#### H15 — Broken WebView JSON string unescaping & regex `BrowserElementRefs.fromJson` drop form fields and controls
- **Status**: `FIXED` (`BrowserElementRefTest` PASS `OK (2 tests)` on JVM with `{`, `}`, escaped quotes, and WebView JSON string wrapping; `BrowserSession.kt` passes `kt-checks.py balance`)
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/browser/BrowserElementRef.kt:8-20`
  - `app/src/main/java/com/kaiser/rivet/browser/BrowserSession.kt:838, 1361-1364, 1551-1559`
- **Issue**: `.trim('"').replace("\\\"", "\"")` leaves `\\`, `\n`, `\uXXXX` corrupt; `BrowserElementRefs.fromJson` regex fails on labels containing `{`, `}`, or escaped quotes `\"`.
- **Fix**: Replace regex in `BrowserElementRefs.fromJson` with `kotlinx.serialization.json` parsing; unquote JS return strings safely via JSON string parsing (avoiding raw `.replace("\\\"", "\"")`).
- **JiTTest**: Pure-Kotlin JVM JiTTest calling `BrowserElementRefs.fromJson` with braces and escaped quotes in labels.

---

### Severity (b) — Logic Flaws

#### H16 — `LocalToolProtocol.grammar` omits JSON quotes on tool names and allows non-object `arguments`
- **Status**: `NEEDS-DEVICE-PROOF` (`LocalToolProtocolTest.grammarQuotesToolNamesAsJsonStringsAndRequiresObjectArguments` added; `LocalToolProtocol.kt` & `LocalToolProtocolTest.kt` pass `kt-checks.py balance`; full suite pulled transitive `@Serializable` models needing Gradle plugin)
- **Files**: `app/src/main/java/com/kaiser/rivet/local/LocalToolProtocol.kt:193-197`
- **Issue**: `names` lacks escaped outer JSON string quotes (`\"...\"`) and `"arguments"` uses `value` instead of `object`, causing llama.cpp grammar-constrained tool calls to emit invalid JSON or non-object arguments.
- **Fix**: Wrap each tool name in escaped JSON quotes (`"\"${...}\""`) and constrain `"arguments"` to `object`.
- **JiTTest**: Pure-Kotlin JVM JiTTest validating GBNF rules produced by `LocalToolProtocol.grammar`.

#### H17 — `LocalThinkingFilter` leaks `"ing>"` across split `</thinking>` chunks and over-nests `<thinking>` inside `<think>`
- **Status**: `FIXED` (`LocalThinkingFilterTest` `OK (10 tests)` on JVM via `K2JVMCompiler` + `JUnitCore`; `kt-checks.py balance` PASS)
- **Files**: `app/src/main/java/com/kaiser/rivet/local/LocalThinkingFilter.kt:118-121`
- **Issue**: `END_MARKERS` matches `"</think"` before `"</thinking"`, emitting stray `"ing>"` when `</thinking>` arrives across deltas; matching `<thinking` inside active `<think>` increments `thinkingDepth`.
- **Fix**: Replaced integer `thinkingDepth` with an `openTags: MutableList<TagFamily>` stack matching full `</think>`/`</thinking>` (and `&gt;`) closing markers to matching opened tags, eliminating split `</thinking` leaks and unmatched inner closing tags.
- **JiTTest**: `LocalThinkingFilterTest.splitThinkingCloseTagDoesNotLeakIngSuffixAndIgnoresUnmatchedInnerTag` (verified RED `AssertionError expected:<[]answer> but was:<[ing>]answer>` -> GREEN `OK (10 tests)`).

#### H18 — `LocalProviderClient` parses tool calls before stripping `<think>` blocks and ignores coroutine cancellation
- **Status**: `NEEDS-DEVICE-PROOF` (`kt-checks.py balance` PASS; `LocalToolProtocolTest` + `LocalProviderClient` require `@Serializable` plugin / Android runtime)
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/local/LocalProviderClient.kt:65-145`
  - `app/src/main/java/com/kaiser/rivet/local/LocalToolProtocol.kt:88-123`
- **Issue**: `LocalToolProtocol.parse` / `looksLikeToolAttempt` inspect raw output before `LocalThinkingFilter.strip(raw)` (so `<think>...</think>{"name":...}` or JSON examples inside `<think>` misfire), and `withContext(Dispatchers.IO)` doesn't invoke `manager.stopGeneration()` on job cancellation.
- **Fix**: Strip `<think>` blocks inside `LocalToolProtocol.parse` and `looksLikeToolAttempt` as well as in `LocalProviderClient` (initial and retry output), and attach `coroutineContext[Job]?.invokeOnCompletion` + `ensureActive()` cancellation checks to call `manager.stopGeneration()` immediately on job cancellation.
- **JiTTest**: `LocalToolProtocolTest.ignoresToolJsonAndAttemptsInsideThinkingBlocks`.

#### H19 — `ModelDownloadManager` preserves stale `prior.wifiOnly` and wedges on HTTP 416 when `.part` > expected size
- **Status**: `NEEDS-DEVICE-PROOF` (`kt-checks.py balance` PASS; `DownloadPolicyTest` / `ModelDownloadManager` require Gradle/Android SDK runtime)
- **Files**: `app/src/main/java/com/kaiser/rivet/local/ModelDownloadManager.kt:85-92, 145-190, 295-380`
- **Issue**: When global `wifiOnly` is turned off, paused/queued jobs keep `prior.wifiOnly == true` and never resume on metered networks; HTTP 416 recovery only deletes `.part` when `part.length() == entry.size`, leaving oversized `.part` files permanently wedged.
- **Fix**: Added `DownloadPolicy.effectiveWifiOnly` & `DownloadPolicy.http416Action`; updated `setWifiOnlyPreference` and `start` to recompute job `wifiOnly` while preserving `allowMobile`; extracted `executeDownloadAttempt` to delete mismatched/oversized `.part` on HTTP 416 and restart cleanly without `Range` instead of wedging.
- **JiTTest**: `DownloadPolicyTest.effectiveWifiOnlyRespectsGlobalDisableAndExplicitMobileAllow` and `DownloadPolicyTest.http416RestartsOversizedOrRejectedPartInsteadOfWedging`.

#### H20 — `ByoModelResolver` fails on uppercase `HTTPS://`, corrupts `+` and branches, and mishandles repos named `blob`/`resolve`
- **Status**: `FIXED` (`ByoModelResolverTest` live RED->GREEN on JVM via `K2JVMCompiler` + `JUnitCore`; `kt-checks.py balance` PASS)
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/local/ByoLinkParser.kt` (new pure helper)
  - `app/src/main/java/com/kaiser/rivet/local/ByoModelResolver.kt:1-60`
  - `app/src/main/java/com/kaiser/rivet/local/ModelCatalogue.kt:45-52`
- **Issue**: Case-sensitive `removePrefix("https://")` rejects `HTTPS://...`; searching for `"blob"`/`"resolve"` segment across `owner/repo` misparses repos named `blob`/`resolve`; `URLDecoder.decode` turns literal `+` in filenames into spaces; hardcoded `resolve/main/` drops non-`main` branches.
- **Fix**: Extracted `ByoLinkParser` using `java.net.URI` (case-insensitive `https` and host check), matching `"blob"`/`"resolve"` strictly at path segment index 2 (`<owner>/<repo>/(blob|resolve)/<revision>/<file>`), decoding `%XX` UTF-8 bytes without converting `+` to spaces, rejecting `%2e%2e` traversal segments, and adding defaulted `revision: String = "main"` to `LocalModelEntry` + `ByoLinkParser.downloadUrl`.
- **JiTTest**: `ByoModelResolverTest` (`parsesUppercaseSchemeAndHostPreservingPlusAndBranch`, `doesNotConfuseOwnerOrRepoNamedBlobOrResolveWithPathDelimiter`, `rejectsTraversalEvenWhenPercentEncoded`; verified RED 2 failures -> GREEN `OK (3 tests)`).

#### H21 — `AgentToolExecutor.reservedGoalMutation` lacks `ReadOnly`, `AgentLoop` truncates duplicate call IDs & mislabels move/rename folder prompts, `ReadArgs.offset` is `Int`
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/agent/AgentToolExecutor.kt:109-111, 686`
  - `app/src/main/java/com/kaiser/rivet/agent/AgentLoop.kt:157-164, 356-361`
- **Issue**:
  1. `reservedGoalMutation` returns default `AgentToolEffect.Write` instead of `ReadOnly`, causing `AgentLoop` to abort the whole turn as `CapabilityBlocked` in `Ask`/plan contexts instead of returning tool error `"reserved_goal"`.
  2. `AgentLoop.kt:158` `truncatedCalls` does not deduplicate duplicate/blank call IDs like normal tool calls do.
  3. `AgentLoop.kt:357-361` overwrites `rename_path`/`move_path` prompt titles with `"Delete folder?"` when `target in createdDirectories`.
  4. `ReadArgs.offset` is `Int` (overflowing on >2GB offset vs `Long`).
- **Fix**: Set `effect = AgentToolEffect.ReadOnly` in `reservedGoalMutation` `PreparedAgentTool`; deduplicate `truncatedCalls` IDs; scope `"Delete folder?"` strictly to `call.name == "delete_path"`; widen `ReadArgs.offset` to `Long`.
- **JiTTest**: Pure-Kotlin JVM JiTTests for `AgentLoop` (`Delete folder?` vs `rename_path`, duplicate truncated IDs) and `AgentToolExecutor` (`reservedGoalMutation` effect, `ReadArgs`).

#### H22 — `GoalDocument.parse` masks `DATE` fallback, `ProjectContext.projection` drops `SKILL_CATALOG_KIND`, `SkillTool` leaks hidden skill names
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/agent/GoalMemory.kt:84-87`
  - `app/src/main/java/com/kaiser/rivet/agent/InternalContext.kt:185-205`
  - `app/src/main/java/com/kaiser/rivet/agent/SkillTool.kt:21`
- **Issue**:
  1. `GoalDocument.parse` line `85` returns full `stateLine.substringBefore('·')` even if it's `"Active"`, preventing line `86`'s `DATE.find(stateLine)` from running.
  2. `ProjectContext.projection` skips `SKILL_CATALOG_KIND` when `latestProject == null` (repos without `AGENTS.md`).
  3. `SkillTool` leaks `disable-model-invocation` skill names in its `skill_not_found` error hint.
- **Fix**: Run `DATE.find(stateLine)?.value` before arbitrary `substringBefore('·')`; render `SKILL_CATALOG_KIND` independently of `latestProject`; filter `catalog.skills.filter { !it.disableModelInvocation }` in `SkillTool` error hint.
- **JiTTest**: Pure-Kotlin JVM JiTTests for all three fixes.

#### H23 — `GitCheckpoint.commit` destroys the user's staged Git index (`git add`) on every run
- **Status**: `PENDING`
- **Files**: `app/src/main/java/com/kaiser/rivet/runtime/RunCheckpointStore.kt:329-358`
- **Issue**: `git.add().addFilepattern(".")` followed by `git.reset().setMode(MIXED).setRef(HEAD)` unstages all user-staged files in `.git/index`.
- **Fix**: Build the checkpoint commit tree using an in-memory JGit index / temporary index file so `.git/index` and `HEAD` are untouched.
- **JiTTest**: JGit `RunCheckpointStoreTest` (`NEEDS-DEVICE-PROOF` offline).

#### H24 — "Undo this run" & `restoreRunCheckpoint` fail on multi-step runs, ignore `sync()` failure, and leak `restore-worktree`
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/ui/processes/ProcessesScreen.kt:157-167, 228-231`
  - `app/src/main/java/com/kaiser/rivet/runtime/RunCheckpointStore.kt:123-125`
  - `app/src/main/java/com/kaiser/rivet/runtime/RuntimeController.kt:308-342`
- **Issue**: Tapping "Undo this run" restores only `seq 0` and checks conflicts against `seq 0.after` rather than net changes `runStart.before..runEnd.after`; `restoreRunCheckpoint` ignores `active.sync()` return value at line `335` and leaks `restore-worktree` without `store.discard(record)` when throwing `MirrorFailure("sync_required")` at line `324`.
- **Fix**: Add run-level net restore (`stageRestoreRun(checkpointsInRun, worktree)` comparing against latest `after` and restoring earliest `before`), check `active.sync()` state in `restoreRunCheckpoint`, and discard staged restore at line `324`.
- **JiTTest**: Pure-Kotlin JVM JiTTest for multi-step `RunCheckpointStore` run restore.

#### H25 — `Toolchain.preparePip` pipe-buffer deadlock & `ToolchainEntryAudit` misclassifying all paths when `nativeDir == ""`
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/runtime/Toolchain.kt:288-290`
  - `app/src/main/java/com/kaiser/rivet/runtime/ToolchainEntryAudit.kt:32-35`
- **Issue**: `preparePip` calls `waitFor` before reading `process.inputStream` (>64KB output deadlocks); `ToolchainEntryAudit` turns `nativeDir = ""` into `nativePrefix = "/"`, classifying `/data/...` writable files as `NativeLinked`.
- **Fix**: Drain `process.inputStream` on a reader/before `waitFor` (with bounded buffer); treat blank `nativeDir` as matching no native prefix (`ToolchainEntryKind.WritableFile`).
- **JiTTest**: Pure-Kotlin JVM JiTTest for `ToolchainEntryAudit.audit(..., nativeDir = "")`.

#### H26 — `ManagedProcesses.redactCommand` drops flags and leaks quoted Bearer tokens
- **Status**: `PENDING`
- **Files**: `app/src/main/java/com/kaiser/rivet/runtime/ManagedProcesses.kt:447-453`
- **Issue**: Line `450` drops `--user`/`--password` flag names (`"$1[redacted]"` missing capture group for flag), and line `452` exposes tokens in quoted `"Authorization: Bearer secret"` headers.
- **Fix**: Preserve the `--user`/`--username`/`--password` flag name capture group and redact quoted/unquoted `Authorization: Bearer ...` tokens cleanly.
- **JiTTest**: Pure-Kotlin JVM JiTTest (testing `redactCommand` / `registerCommand`).

#### H27 — `RuntimeController.openTerminal` never settles dirty mirrors and `TerminalPane` hides cursor behind IME when `transcriptRows == 0`
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/runtime/RuntimeController.kt:188`
  - `app/src/main/java/com/kaiser/rivet/ui/terminal/TerminalPane.kt:188, 338-344`
- **Issue**: `openTerminal` returns `"sync_required"` instead of calling `active.sync()` when `ready.dirty`, and `TerminalPane` shows *"The workspace is syncing..."*; `TerminalGrid` (`341`) skips scrolling `cursorIndex` into view when `transcriptRows == 0` even after IME shrinks visible height below `cursorIndex` (preserving `InteractiveTerminal.requestResize`'s intentional no-SIGWINCH optimization!).
- **Fix**: Call `active.sync()` in `openTerminal` when `ready.dirty` (matching `runCommand`), improve `"sync_required"` message, and scroll `cursorIndex` into view in `TerminalGrid` when `totalRows > 0 && (transcriptRows > 0 || cursorIndex >= visibleRows)` without touching `InteractiveTerminal.requestResize`.
- **JiTTest**: Structural `kt-checks.py` + Robolectric/UI JiTTest (`NEEDS-DEVICE-PROOF` offline).

#### H28 — `WorkspaceMirror.sync()` clears `partial-create.json` on 2nd sync, duplicates `skipped`, and misclassifies dir conflicts
- **Status**: `PENDING`
- **Files**: `app/src/main/java/com/kaiser/rivet/runtime/WorkspaceMirror.kt:119, 140-150, 160-200, 212-218, 252`
- **Issue**: Clearing `partial-create.json` when SAF normalized the name allows duplicate creation on the 3rd sync; passing the same `skipped` list to two `localSnapshot` calls duplicates entries; descendant paths of conflicting file↔dir paths fail instead of excluding/reporting `Conflict`.
- **Fix**: Keep/record normalized-create exclusions accurately, capture fresh `skipped` from the post-sync snapshot, and handle parent/child `WorkspaceFailure(CONFLICT)` cleanly.
- **JiTTest**: Robolectric `WorkspaceMirrorTest` JiTTest (`NEEDS-DEVICE-PROOF` offline).

#### H29 — `ResponsesAgentStream` latches `itemDone` permanently and `OkHttpClient.responsesSse` relabels caller exceptions
- **Status**: `PENDING`
- **Files**: `app/src/main/java/com/kaiser/rivet/provider/ChatGptPlanClient.kt:185, 201-203, 259-261, 293, 373, 388-392`
- **Issue**:
  1. `itemDone = true` stays true after the first item finishes, so a truncated second item (`function_call` without `output_item.done` or `completed`) is accepted as complete.
  2. `responsesSse` catches `onEvent` exceptions and `CancellationException` and relabels them as `ProviderError.InvalidResponse("malformed stream event")`.
- **Fix**: Reset `itemDone = false` when a new item/delta begins and require every `PendingCall` to be marked `done` (or `completed == true`); mirror `Http.kt`'s `StreamCallbackFailure` and `CancellationException` pass-through in `responsesSse`.
- **JiTTest**: Pure-Kotlin JVM JiTTest for `ResponsesAgentStream` multi-item truncation; `MockWebServer` JiTTest for `responsesSse` (`NEEDS-DEVICE-PROOF` offline).

#### H30 — `OpenAiAgentStream` blanks `pending.id` on empty delta chunks, ignores `request.model` ceiling, and `Http` prints `"null"` on `JsonNull`
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/provider/OpenAiCompatibleClient.kt:66, 169`
  - `app/src/main/java/com/kaiser/rivet/provider/Http.kt:87-107`
- **Issue**: `"id": ""` on chunk 2 overwrites `"id": "call_1"` from chunk 1; `OpenAiCompatibleClient.streamAgent` calls `config.openRouterOutputCeiling()` without `request.model`; `errorBodyText`/`errorBodyCode` treat JSON `null` (`JsonNull.content`) as `"null"` and throw on non-object `"error"`.
- **Fix**: Require `isNotBlank()` before assigning `pending.id`; pass `request.model` to `openRouterOutputCeiling(request.model)`; use `.obj()` / `.str()` in `Http.kt` with string `"error"` fallback.
- **JiTTest**: Pure-Kotlin JVM JiTTests for `OpenAiAgentStream` and `Http.errorBodyText`/`errorBodyCode`.

#### H31 — `ProviderConfig` `/v1` Anthropic thinking mismatch, unscoped `openRouterOutputCeiling`, and wiped manual token inputs on model entry
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/provider/ProviderConfig.kt:176-181, 208-219, 296-300`
  - `app/src/main/java/com/kaiser/rivet/ui/provider/ProviderEditorState.kt:89-106`
- **Issue**: `anthropicThinkingMode` rejects `https://api.anthropic.com/v1`; `openRouterOutputCeiling` ignores `model`/`baseUrl` match on `modelContextLimit`; setting manual limits while `model.isBlank()` is wiped when typing a model name afterward.
- **Fix**: Normalize `baseUrl` with `.trim().trimEnd('/').removeSuffix("/v1")`; check `it.model == targetModel && it.baseUrl == baseUrl` in `openRouterOutputCeiling`; preserve manual limits across blank->non-blank model transition.
- **JiTTest**: Pure-Kotlin JVM JiTTests on `ProviderConfig` & `ProviderEditorState`.

#### H32 — `refreshBundle` drops `scopes`, ignores `idToken` email/optional refresh, masks network vs reauth errors, and strict `Json` in `ProvidersViewModel`
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/provider/ChatGptSession.kt:291-306`
  - `app/src/main/java/com/kaiser/rivet/provider/ChatGptAuth.kt:184-195, 244-252`
  - `app/src/main/java/com/kaiser/rivet/ui/provider/ProvidersViewModel.kt:517`
- **Issue**: Refresh omitting `"scope"` wipes `bundle.scopes`; `email` isn't updated when `id_token` rotates; refresh omitting `id_token` is rejected; `refreshBundle` collapses `NetworkFailed` vs `CHATGPT_DEAD_REFRESH_ERRORS`; `ProvidersViewModel.decodeChatGptBundle` rejects unknown keys (`Json` vs `Json { ignoreUnknownKeys = true }`).
- **Fix**: Preserve `bundle.scopes` when `tokens.scope.isBlank()`, accept optional `id_token` on refresh while updating `email` when present, preserve distinct refresh outcomes, and share `ignoreUnknownKeys = true` JSON codec.
- **JiTTest**: Pure-Kotlin JVM JiTTest (`ChatGptAuth` refresh / `ChatGptBundle` decode).

#### H33 — `ProvidersViewModel.disconnectGitHub` clears `gitAvailable`, `startChatGptSignIn` orphans UUIDs, and `fetchModels` sets `state.test`
- **Status**: `PENDING`
- **Files**: `app/src/main/java/com/kaiser/rivet/ui/provider/ProvidersViewModel.kt:160-167, 282-327, 360-383, 724-727`
- **Issue**: `disconnectGitHub` resets `gitAvailable = false`; new ChatGPT sign-in ignores `_editorState.config.id` and persists `model = ""` before resolving models; missing API key in `fetchModels` writes to `test` instead of `fetchError`.
- **Fix**: Preserve `gitAvailable` via `it.copy(...)`; use editor's target config ID and resolve model before persisting; write missing-key error to `fetchError` (leaving `ModelSheet` / editor consistent).
- **JiTTest**: Robolectric `ProvidersViewModel` JiTTest (`NEEDS-DEVICE-PROOF` offline).

#### H34 — Index-keyed `messageReasoning` and `turnDurationsMs` desync across filtering, trimming, compaction, and mid-turn slices
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt:748, 1763-1775, 2131, 2144-2155`
  - `app/src/main/java/com/kaiser/rivet/ui/chat/ChatProcessGroups.kt:397-463`
- **Issue**: `AgentRole.Context` injection shifts indices during streaming while `recent()` strips `Context` at turn end; `send()` `.takeLast(100)` and `compactContext` do not shift `messageReasoning`; `segmentTurnItems` creates `turnIndex = 0` for leading orphan non-User slices whereas `finish()` keys `turnDurationsMs` by `visible.count { User } - 1`.
- **Fix**: Align `turnDurationsMs` alignment with `segmentTurnItems` (or exclude orphan pre-user slice from incrementing user turn index / use consistent turn indexing) and compute reasoning association without counting stripped `AgentRole.Context` messages (updating indices cleanly on trim/compaction).
- **JiTTest**: Pure-Kotlin JVM JiTTest for `segmentTurnItems` turn index alignment on mid-turn window slices.

#### H35 — Composer `/goal` draft wedging, stale `runRecovery`, `ModelSheet` stranded `loadError`, and `RivetApp` nav overlap
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt:1901-1929`
  - `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt:652-676, 737-741`
  - `app/src/main/java/com/kaiser/rivet/ui/chat/ModelSheet.kt:196-203`
  - `app/src/main/java/com/kaiser/rivet/ui/RivetApp.kt:122-128`
- **Issue**:
  1. `/goal` never clears `draft` and wedges `pendingText != null` if `notice` string didn't change.
  2. `refreshGoalMemory` retains `else it.runRecovery` when no run is resumable.
  3. `ModelSheet` hides provider switch rows & `ManageProvidersRow` when `loadError != null` (including `Local` keyless provider).
  4. `RivetApp` notification navigation opens `processesOpen` without closing `terminalOpen` / `browser` / `modelsOpen` / `editing`.
- **Fix**: Track a monotonic command/submission ack or clear `draft`/`pendingText` on `/goal`; set `runRecovery = if (resumable) ... else null`; keep provider jump rows & Manage Providers accessible under `loadError` and avoid API-key error for `Local`; close overlay panes on `processNavigationRequest`.
- **JiTTest**: Structural `kt-checks.py` + Compose/Robolectric JiTTest (`NEEDS-DEVICE-PROOF` offline).

#### H36 — Pure-Kotlin browser policy/extractor flaws: IPv6 `[::1]` loopback, uppercase URL scheme, `opacity: 0.x` removal
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/browser/BrowserAuthGuard.kt:34-37`
  - `app/src/main/java/com/kaiser/rivet/browser/ReaderService.kt:81`
  - `app/src/main/java/com/kaiser/rivet/browser/ReaderExtractor.kt:85, 124-126`
- **Issue**: `URI.host` for IPv6 loopback is `"[::1]"` (`isLoopback` misses it and uppercase hosts); `BrowserUrlPolicy.validate` rejects `HTTP://`/`HTTPS://`; `ReaderExtractor.removeHidden` matches `opacity:0.5` / `0.8`; (`ReaderExtractor` link filter fixed alongside in `H51` or together here).
- **Fix**: Strip `[...]` and lowercase host in `isLoopback`; lowercase `uri.scheme` in `BrowserUrlPolicy.validate`; use boundary regex for `opacity: 0(.0+)?`.
- **JiTTest**: Pure-Kotlin JVM JiTTest (`BrowserAuthGuard`, `BrowserUrlPolicy`, `ReaderExtractor` with `jsoup`).

#### H37 — `BrowserSession` state flaws: `read`/`find` stale renderer & dropped `visibleOnly`, `openVisual` flag ordering, zombie tab, `observedBrowserBytes` peak latch, `parseClickTarget`/`typeScript`/fractional `scrollY`, hidden screenshot
- **Status**: `PENDING`
- **Files**: `app/src/main/java/com/kaiser/rivet/browser/BrowserSession.kt:337-340, 385-388, 474-573, 713, 1213-1237, 1496-1530, 1551-1559, 1594-1632`
- **Issue**: `read()`/`find()` read stale `renderer` over newer `openText()`, ignore `visibleOnly = false`, and keep old `elementRefs`; `openVisual()` sets `rendererVisible = true` before `ensureRenderer()` guards; `closeWindow()` on last tab loads `"about:blank"` recreating a tab and `endSession()` leaves `handoff`/`downloadPrompt`/`loadValveNotice`; `observedBrowserBytes` latches peaks; `parseClickTarget("null")` returns `found = true` and `typeScript` crashes on non-CSS names; fractional `scrollY` fails `.toIntOrNull()`; hidden `PixelCopy` captures `ChatScreen`.
- **Fix**: Prefer fresh `readerCache` for `openText` URLs and clear `elementRefs`, run `ensureRenderer` eligibility before flipping `rendererVisible`, clean `endSession` state & skip zombie `about:blank` tab recreation, keep separate `lastOwnGrowthBytes` in budget refresh, guard JS selectors / parse JSON strictly / round `scrollY`, and draw directly from `view.draw(Canvas)` when browser is hidden.
- **JiTTest**: Pure-Kotlin JVM JiTTests for extracted parsers (`parseClickTarget` / `parseScrollY`) + Robolectric for `BrowserSession`.

#### H38 — `SpendStore.isLocal` substring matching misclassifies remote hosts (`localhost.evil.com`, `172.200.0.1`)
- **Status**: `PENDING`
- **Files**: `app/src/main/java/com/kaiser/rivet/storage/SpendStore.kt:80-89`
- **Issue**: Substring `.contains("localhost")` and `.contains("://172.2")` treat `https://localhost.evil.com` or public `http://172.200.0.1` as zero-spend local URLs.
- **Fix**: Parse `URI(baseUrl)` host and validate exact loopback / RFC-1918 IPv4 ranges (`10.*`, `192.168.*`, `172.16..31.*`, `10.0.2.2`, `localhost`, `127.0.0.1`, `::1`).
- **JiTTest**: Pure-Kotlin JVM JiTTest (`SpendStoreTest`).

---

### Severity (c) — Race / Lifecycle / Resource Leaks

#### H39 — `ModelDownloadManager` detached `finish()` race, unsynchronized `_jobs`/`persist()`, and `LocalModelManager.remove()` caller-thread lock
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/local/ModelDownloadManager.kt:143-216, 425-445, 461-476`
  - `app/src/main/java/com/kaiser/rivet/local/LocalModelManager.kt:118-132`
- **Issue**: `finish()` launches detached `scope.launch` that can lose to `pause()` deleting `.part` / completed file; `_jobs` and `persist()` write `downloads.json.tmp` concurrently without synchronization; `LocalModelManager.remove()` runs blocking `nativeLock` + disk deletion on caller thread without `Dispatchers.IO`.
- **Fix**: Synchronize `_jobs` & `persist()` atomically with unique temp file / lock, run `finish()` persistence cleanly before releasing active job state, and move `LocalModelManager.remove` blocking work onto `Dispatchers.IO`.
- **JiTTest**: Robolectric / JVM concurrency JiTTest.

#### H40 — `SkillDiscovery.catalog` caches `null` workspace under `workspaceId` key, poisoning subsequent workspace calls
- **Status**: `PENDING`
- **Files**: `app/src/main/java/com/kaiser/rivet/agent/SkillDiscovery.kt:39-40`
- **Issue**: Cache key omits whether `workspace != null`, so `catalog(null, id)` caches global-only skills under `id` and blocks `catalog(workspace, id)` from discovering project skills.
- **Fix**: Include `workspace != null` (or separate global vs project key) in cache key.
- **JiTTest**: Pure-Kotlin JVM JiTTest (`SkillDiscoveryTest`).

#### H41 — `InteractiveTerminalSession` races between `ensureStarted`, `open`, `closeInternal`, and `job.invokeOnCompletion`
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/runtime/InteractiveTerminal.kt:119-138, 157-217, 341-365`
  - `app/src/main/java/com/kaiser/rivet/runtime/RuntimeController.kt:165-214`
- **Issue**: Closing while `engineFactory.create` is in flight sets `finished = true` while `engine == null`, then `ensureStarted` stores `created` and skips `closeInternal`, leaking PTY/process; `open()` ignores `starting == true`; `job.invokeOnCompletion` races synchronously off `scope` and `RuntimeController` registers an already-cancelled `job`.
- **Fix**: Check `ticket != generation || finished || job.isCancelled` right after `engineFactory.create` and close `created` directly if stale; cancel stale starts in `open()` when `starting`; run stop cleanup cleanly and reject cancelled jobs before `registerCommand`.
- **JiTTest**: Pure-Kotlin JVM JiTTest with fake `TerminalEngineFactory` / `InteractiveTerminalSession` (checking coroutines-test or simple fake).

#### H42 — `ManagedProcesses` service-start race & slot leak, `PersistentProcessService` non-stopping one-shot actions, `ProjectDownloader` cancel race
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/runtime/ManagedProcesses.kt:216-261, 353-375`
  - `app/src/main/java/com/kaiser/rivet/runtime/PersistentProcessService.kt:37-62`
  - `app/src/main/java/com/kaiser/rivet/runtime/ProjectDownloader.kt:95-97, 143-150`
- **Issue**: `ensureForegroundService` overwrites `serviceStarted` after `withTimeoutOrNull` racing with `onForegroundServiceStarted`/`Stopped`; `stop`/`stopAll` omit `finishPreview(id, failed = true)` on exceptions, leaking `MAX_PERSISTENT_PROCESSES` slots; `PersistentProcessService` leaves service alive on `ACTION_RAISE`/`ACTION_PAUSE`/`ACTION_APPROVE`/`ACTION_DENY`; `ProjectDownloader` cancel can delete `staged` before `onResponse` creates `FileOutputStream(target)`.
- **Fix**: Preserve callback-driven `serviceStarted` state on timeout; wrap persistent `stop`/`stopAll` cleanup in `try/catch` calling `finishPreview(id, failed = true)`; call `stopSelfResult(startId)`/`stopIfIdle()` for one-shot actions when not foregrounded; guard `ProjectDownloader` staging cleanup with an `AtomicBoolean` cancelled flag.
- **JiTTest**: Pure-Kotlin JVM JiTTest for `ManagedProcesses` slot leak / `serviceStarted` race; Robolectric for `PersistentProcessService`.

#### H43 — `ChatGptToken` refresh/sign-out race, uninterruptible `CountDownLatch` in `ChatGptLoopback`, and `ProvidersViewModel` callback/save races
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/provider/ChatGptToken.kt:20, 37-49`
  - `app/src/main/java/com/kaiser/rivet/provider/ChatGptSession.kt:65-85`
  - `app/src/main/java/com/kaiser/rivet/ui/provider/ProvidersViewModel.kt:330-412, 545-556, 609-646`
- **Issue**: `resolveChatGptAccessToken` `?: bundle` fallback resurrects a cleared bundle if sign-out ran while waiting; `ChatGptLoopback.awaitCallback` blocks `Dispatchers.IO` via `CountDownLatch.await` ignoring job cancellation; `ProvidersViewModel` sign-in/sign-out/save lack session/editor-id/re-entrancy guards.
- **Fix**: Never fall back to stale `bundle` when `secrets.oauthBundle` is null and synchronize save/clear with `chatGptRefreshMutex`; replace `CountDownLatch` with cancellable `CompletableDeferred` + `withTimeoutOrNull`; guard VM state updates with `session === chatGptSession` / `id` / `saving` check.
- **JiTTest**: Pure-Kotlin JVM JiTTest for `ChatGptLoopback` cancellation + `ChatGptToken` clear-vs-refresh race.

#### H44 — `ChatViewModel.networkCallback` mutates/reads VM state on `ConnectivityThread` and drops auto-resume
- **Status**: `PENDING`
- **Files**: `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt:510-523`
- **Issue**: `NetworkCallback.onAvailable`/`onLost` run off `Main`, reading/mutating non-synchronized `currentCloud`/`generation`/`currentRun` and calling `retryInterruptedTurn()` before `startTurn`'s `finally` completes.
- **Fix**: Hop onto `viewModelScope.launch` (`Main`) in both callbacks and await `sendJob?.join()` before auto-resuming `RunPauseReason.NetworkLost`.
- **JiTTest**: Robolectric `ChatViewModelTest` JiTTest (`NEEDS-DEVICE-PROOF` offline).

#### H45 — `BrowserSession` teardown race, missing watchdog on `requestRendererTeardown`, `BrowserIdleArming` deadline reset, swallowed `userOpen` errors
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/browser/BrowserSession.kt:249-259, 283-287, 1496-1530, 1784-1852`
  - `app/src/main/java/com/kaiser/rivet/browser/BrowserIdleArming.kt:16-20`
- **Issue**: `userOpen`/`ensureRenderer` reuse `renderer` while `teardownRequested == true`; `requestRendererTeardown` omits `armTeardownWatchdog()`; `BrowserIdleArming.onBackground` resets an already-armed hidden deadline; `ensureRenderer` leaves `idleJob`/`idleArming` active; `userOpen` swallows `openVisual` failures.
- **Fix**: Reject/wait out pending `teardownRequested` before reusing `renderer`; call `armTeardownWatchdog()` in `requestRendererTeardown()`; only arm in `onBackground` when `!armed` and disarm `idleArming` / cancel `idleJob` in `ensureRenderer`; publish `BrowserFailure` in `userOpen().onFailure`.
- **JiTTest**: Pure-Kotlin JVM JiTTest for `BrowserIdleArming`.

---

### Severity (d) — Security-by-Structure Holes

#### H46 — `ByoModelResolver` allows `.`/`..` & unvalidated `rfilename` paths; `ModelDownloadManager` marks HTTP `ETag` as `checksumVerified`
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/local/ByoModelResolver.kt`
  - `app/src/main/java/com/kaiser/rivet/local/ModelDownloadManager.kt:403, 432`
- **Issue**: `repoPattern` allows `..` segments and HF API `rfilename` isn't validated for path traversal or safe filename; HTTP `ETag` is treated as cryptographic checksum verification (`checksumVerified = true`).
- **Fix**: Reject `.`/`..` in owner/repo, validate/basename `rfilename`, and only set `checksumVerified = true` when verified against an actual SHA-256 checksum (never `ETag`).
- **JiTTest**: Pure-Kotlin JVM JiTTest for `ByoModelResolver`.

#### H47 — Reserved `.rivet/goal.md` bypasses & `SkillScriptGate` matching gaps (`cwd`, `cd --`, `./`, multi-skill)
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/agent/AgentToolExecutor.kt:313, 673-682`
  - `app/src/main/java/com/kaiser/rivet/agent/SkillScriptGate.kt:1-83`
- **Issue**: `reservedGoalMutation` only inspects direct `path` (missable by deleting `.rivet`, renaming/moving `.rivet` or `dir` into `.rivet`, or renaming/moving a file to `goal.md` inside `.rivet`); `matchSkillScript` omits command `cwd`, misses `cd --` and `./`, and stops at the first matching skill alphabetically.
- **Fix**: Cover `.rivet` ancestor mutations and destination paths of `rename_path`/`move_path` in `reservedGoalMutation`; pass `cwd` into `matchSkillScript`, handle `cd --`/`./`, and collect/match across all skills cleanly.
- **JiTTest**: Pure-Kotlin JVM JiTTests for `SkillScriptGate` and `AgentToolExecutor.reservedGoalMutation`.

#### H48 — `LocalPreviewServer` case-sensitive `.git` block, missing `Host` check, and tiny connection queue
- **Status**: `PENDING`
- **Files**: `app/src/main/java/com/kaiser/rivet/runtime/LocalPreviewServer.kt:35-36, 65, 73-76, 198, 204-240`
- **Issue**: `/.GIT/config` bypasses `it == ".git"` on case-insensitive filesystems; missing `Host` header validation exposes DNS rebinding; `CLIENT_QUEUE_LIMIT = 2` drops parallel asset loads.
- **Fix**: Use `it.equals(".git", ignoreCase = true)`, validate `Host` is loopback (`127.0.0.1[:port]` / `localhost[:port]` / `[::1][:port]`), and raise `CLIENT_QUEUE_LIMIT` to handle normal browser concurrency (e.g. `16`).
- **JiTTest**: Pure-Kotlin JVM `ServerSocket` JiTTest against `LocalPreviewServer`.

#### H49 — OkHttp follows redirects with custom API-key headers, discovery endpoints unpinned to HTTPS, weak JWT `aud`/`sub`/`kty` checks
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/provider/Http.kt:26-42`
  - `app/src/main/java/com/kaiser/rivet/provider/ChatGptAuth.kt:263-277, 304-310, 334-369`
- **Issue**: Default OkHttp redirects forward `x-api-key`, `x-goog-api-key`, and form `refresh_token` across redirects; `fetchJwks`/`revokeChatGptRefreshToken` don't enforce `isHttps` or `isSuccessful`; `validateIdToken` accepts `["client", 123]` in `aud`, empty `sub`, and non-RSA `kty`.
- **Fix**: Disable `followRedirects(false).followSslRedirects(false)` on `http`; require `isSuccessful` + `https` scheme (+ expected host/valid HttpUrl) in `fetchJwks`/`revokeChatGptRefreshToken`; require `aud` array elements to be strings (`aud.size == 1 && aud[0].str() == expectedClientId`), `jwk["kty"]?.str() == "RSA"`, and non-blank `sub`.
- **JiTTest**: Pure-Kotlin JVM JiTTest for `validateIdToken` checks (`aud`, `sub`, `kty`).

#### H50 — Loopback OAuth listener aborts on wrong-`state` requests & invalid `%` escapes; unvalidated custom provider headers
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/provider/ChatGptSession.kt:104-154, 239-243`
  - `app/src/main/java/com/kaiser/rivet/provider/ProviderConfig.kt:310-319`
  - `app/src/main/java/com/kaiser/rivet/ui/provider/ProvidersViewModel.kt:609-646`
- **Issue**: Any third-party request to `/auth/callback*` aborts an active sign-in even with wrong `state` or `Error` callback; `/auth/callbackEVIL` matches `startsWith`; `URLDecoder` throws uncaught; invalid HTTP header names/values crash `Request.Builder.header` at turn time.
- **Fix**: Pass expected `state` to `ChatGptLoopback` (keep listening / return 400 on mismatched/missing `state` or non-exact path) and catch `IllegalArgumentException` in `parseQuery`; validate HTTP header name/value grammar before saving in `ProvidersViewModel.save()` / sanitize safely.
- **JiTTest**: Pure-Kotlin JVM JiTTest for `ChatGptLoopback` state validation & header validation helper.

#### H51 — `ReaderExtractor` permits uppercase `JAVASCRIPT:`/`data:`/`vbscript:` links; `BrowserSession` raw `Content-Disposition` filename & unescaped CSS `[name="..."]`
- **Status**: `PENDING`
- **Files**:
  - `app/src/main/java/com/kaiser/rivet/browser/ReaderExtractor.kt:124-126`
  - `app/src/main/java/com/kaiser/rivet/browser/BrowserSession.kt:1690-1696, 1942-1944`
- **Issue**: Case-sensitive `!href.startsWith("javascript:")` allows `JAVASCRIPT:`/`data:`/`vbscript:` links; raw `Content-Disposition` `filename` passes `..` and `/` into `setDestinationInExternalFilesDir`; unescaped webpage `<input name>` breaks `querySelector('[name="..."]')` in `loadSnapshotState`.
- **Fix**: Allow only `http://` / `https://` links (case-insensitive) in `ReaderExtractor`; extract & test safe basename sanitization in `BrowserDownloadPolicy`; match `e.name === k` over `querySelectorAll('input,textarea,select')` while skipping `password`/`hidden`/`file`.
- **JiTTest**: Pure-Kotlin JVM JiTTest (`ReaderExtractor` with `jsoup` + `BrowserDownloadPolicy`).

---

### Severity (e) — Dead or Unreachable Code That Misleads

#### H52 — Verified dead/unreachable production symbols cleanup
- **Status**: `PENDING`
- **Scope**: Remove strictly verified dead private/internal symbols with zero live production callers (updating only directly tied unit tests if needed, without breaking existing test contracts):
  - `LocalModelManager.tokenCount` (`194-197`), `LocalProviderClient` dead branches (`56`, `155`), `ByoModelResolver` dead `removePrefix("http://")`
  - `AgentToolExecutor.ACTIVE_PROCESS_STATES` (`808-809`), `MemoryFormat.atHardCap` (`GoalMemory.kt:198`)
  - `RuntimeController.writeProjectCheckpoint` (`349-380`), `ProcessesScreen` composable (`59-62`), `TerminalPane` unreachable `if (text.isEmpty())` (`502`)
  - `ProvidersViewModel.clearTest` (`780-782`) & `updateChatGptPlanBundle` (`527-532`)
  - `BrowserGovernor` unused `BrowserShedStep`/`nextShed`/`shedPlan`/`canUseReader` (verify `rg` references in `app/src/` before removing any item).

---

## Execution Log & Outcomes

*(Updated as each fix commit lands on `audit/hydrogen-whole-app`.)*

---

## RESUME HERE

Audit plan complete. Ready to begin executing fixes in strict severity order starting at **H1**.
