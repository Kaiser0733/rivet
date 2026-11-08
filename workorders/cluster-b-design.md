# Cluster B — design notes (phase B0, design only)

Work order: `workorders/cluster-b-workspace-dsh.md`. Branch `stability-v1.1.0`, starting commit `2d9fbf6`.
**No source file was modified in this pass.** This document is the only file written.

Line numbers below are against `2d9fbf6`. Another worker is editing the provider layer
(`provider/*Client.kt`, `Http.kt`), so none of my changes go near those files.

That worker committed during this pass; HEAD is now `e42d5de` (`git diff --stat 2d9fbf6..e42d5de`
touches only `provider/*` and their tests). I re-checked every citation below after the move: none of
my files shifted, so the line numbers still hold.

## Ownership, and one correction to the work order

The work order lists `workspace/*` as containing `WorkspaceMirror` and `TurnCheckpoint`. Both actually
live in `runtime/`:

- `app/src/main/java/com/kaiser/rivet/runtime/WorkspaceMirror.kt` (456 lines)
- `app/src/main/java/com/kaiser/rivet/runtime/TurnCheckpoint.kt` (316 lines)
- `workspace/` holds `SafWorkspace.kt`, `WorkspaceText.kt`, `WorkspaceFailure.kt`, plus path/search/entry helpers.

I am treating those two `runtime/` files as mine, per the intent. Everything else I touch is on the
stated list.

## Changes that need someone else's file (please confirm who owns them)

I can land each fix so it fails safe with only my files, but three small edits sit outside my list.
None of them is large.

| Fix | File / line | Needed change | If not assigned to me |
|---|---|---|---|
| F11, F12 | `agent/AgentToolExecutor.kt:467` | one `put("sync_skipped", result.syncSkipped)` line beside the existing `put("sync", …)` | state still reaches `RuntimeCommandResult`; the model just will not see the skipped paths |
| F10 | `agent/AgentToolExecutor.kt:119-136` | pass the existing `args.offset` into the new region read instead of always taking a whole-file snapshot | the honest error lands; paging through a >1 MiB file does not |
| F15 | `chat/ChatViewModel.kt:99` (`undoFailureMessage`) | one `when` branch for the new `undo_desync` code | Undo still stops safely, but the user gets the generic "couldn't finish Undo" line |
| F21 | Cluster A | final output/size ceiling numbers | paragraph stays out of the prompt; I will not guess numbers |

`undoFailureMessage` is a top-level function, not `systemInstruction`, so F15 sits outside the
"F21 only" carve-out for `ChatViewModel.kt`. Flagging rather than assuming.

## Testing reality

No JDK or Android SDK on this machine, so nothing below is verified locally. Each fix adds JVM unit
tests; CI is the gate. Existing harnesses I will extend, all already present:

- `runtime/WorkspaceMirrorTest.kt` — Robolectric + `TestDocumentsProvider`, real temp worktree.
- `runtime/TurnCheckpointTest.kt`
- `workspace/SafWorkspaceTest.kt`, `workspace/WorkspaceTextTest.kt`
- `storage/ProviderHeaderStorageTest.kt` — in-memory DataStore; `SecretStore` accepts an internal
  test key constructor (`SecretStore.kt:17`), which is how I will simulate a lost keystore.
- `agent/AgentToolExecutorTest.kt`, `agent/AgentLoopTest.kt`

---

## 1. F08 + F09 — hand the model the error vocabulary that already exists

**Functions I will change**

- `agent/AgentToolError.kt:10-34` — the `actions` map.
- `agent/AgentToolError.kt:35-41` — the `deterministic` set.
- No change needed at `workspace/SafAgentWorkspace.kt:122-126` (`call`, which flattens
  `WorkspaceFailure.Reason` to `e.reason.name.lowercase()`). Completing the two maps fixes it there.

**The change, in plain words**

`SafAgentWorkspace.call` turns every workspace failure into a bare code, and `AgentToolError` only
knows how to explain 24 codes. The codes that come from `WorkspaceFailure.Reason` are mostly absent,
so the model receives `{"error":"patch"}` with no instruction — and because those codes are also
missing from `deterministic`, the no-progress guard never fires and one uneditable file can run to
the 200-turn cap.

I will complete both maps from `WorkspaceFailure.Reason` (16 values), taking the wording that already
exists in `WorkspaceFailure.kt:5-20` rather than writing new prose:

`invalid_path, permission, missing, not_directory, not_file, duplicate, unsupported, root, binary,
too_large, conflict, patch, limit, provider, write`
(`unsafe_entry` and `stale_target` are already present; `destination_exists` is not a
`WorkspaceFailure` code and stays as it is.)

I will also add the two codes `AgentToolExecutor` emits but nothing explains: `invalid_offset`
(`:127`) and `output_limit` (`:133`).

For `deterministic` I will include every code above **except** `provider` and `write`. Both describe
a condition that a genuine retry can clear (the document provider coming back, a write that could not
be verified); ending the run on them would be wrong. The rest describe the same call failing the same
way, which is exactly what the guard exists to catch.

**Risk**

Marking more codes deterministic makes the run stop sooner. If I misclassify a recoverable error, a
transient blip ends the turn with "Rivet couldn't get past the same problem." That is why `provider`
and `write` stay out, and why the test below is generative: it walks `Reason.entries` so a future
reason cannot be added without an explanation and a classification.

**Test I will add**

`agent/AgentToolExecutorTest.kt` (or a new `agent/AgentToolErrorTest.kt` if the class is internal —
it is, `AgentToolError` at `:9`):

- for every `WorkspaceFailure.Reason`, assert `AgentToolError.content(code)` contains a non-empty
  `required_action`, and assert `noProgress(code)` carries the same text;
- assert `deterministicCode` is non-null for `patch`, `conflict`, `too_large`, `missing`, `binary`,
  `limit`, `duplicate`, `not_file`, `not_directory`, `root`, `permission` (the list in the work order);
- assert it is null for `provider` and `write`.

---

## 2. F11 — one symlink must not brick the sync

**Functions I will change**

- `runtime/WorkspaceMirror.kt:363-401` — `localSnapshot`. The two throwing sites are `:374`
  (symbolic link) and `:394` (neither directory nor regular file).
- `runtime/WorkspaceMirror.kt:37` — `MirrorSyncResult`, to carry the skipped paths.
- `runtime/RuntimeController.kt:19-28` — `RuntimeCommandResult`, to carry them to the caller;
  filled in at `runCommand` `:74-84`.

**The change, in plain words**

`localSnapshot` walks the whole worktree and throws `unsafe_entry` on the first symlink or special
file, which aborts `prepare()`, `sync()` and `hasLocalChanges()` — so the project answers
`sync_required` forever. `npm install` makes that happen (`node_modules/.bin`).

It will collect the path, leave the entry out of the map, and keep walking. The root itself
(`:364-365`) stays fatal — a symlinked worktree root is a different problem and must not be
tolerated silently.

The skipped paths propagate out through `MirrorSyncResult.skipped` and `RuntimeCommandResult`, so the
tool result can name them instead of the model guessing.

**Why the local map can simply omit them**

`prepare()` (`:82`) and `hasLocalChanges()` (`:90`) compare `localSnapshot()` against `baseline.entries`,
and the baseline is written from the SAF snapshot (`:197`), which cannot contain a locally created
symlink. Both sides omit the path, so the comparison stays consistent and the tree does not look
dirty on every call.

**Risk**

- If the SAF side *does* report an entry at a path the mirror skipped (a real file in the project
  where the mirror has a symlink), the final verify at `:195` would see a mismatch and fail the sync.
  I will treat that as a conflict for that one path and report it, not a silent failure.
- **This does not fix Undo for those projects.** `TurnCheckpoint.scan` throws `unsafe_entry` at
  `:168` and `:183` on the same files, so a project with symlinks still cannot checkpoint. Fixing
  that means deciding that Undo will not restore skipped paths, which is a product decision, not a
  side effect of F11. Out of scope here; noted so it is not mistaken for solved.

**Test I will add**

`runtime/WorkspaceMirrorTest.kt`: build a worktree containing a regular file and a symlink
(`Files.createSymbolicLink` into a temp target), run `sync()`; assert it returns `Ok` or `NoChanges`
rather than throwing, that the regular file reached SAF, that `skipped` contains the symlink path,
and that a second `prepare()` still reports `dirty == false`.

---

## 3. F12 — one conflicted file must not block the whole sync

**Functions I will change**

- `runtime/WorkspaceMirror.kt:129-133` — the single-difference scan that returns `Conflict` early.
- `runtime/WorkspaceMirror.kt:102-108` — the pending-create conflict return.
- `runtime/WorkspaceMirror.kt:194-197` — the final verify.
- `runtime/WorkspaceMirror.kt:40-44` — `MirrorBaseline`, to remember which paths are excluded.
- `runtime/RuntimeController.kt:19-28` — `RuntimeCommandResult`.

**The change, in plain words**

Today `sync()` finds the first path that differs on both sides and returns `Conflict`, having written
nothing. One external edit therefore stops the whole project from syncing, and the run-command path
drops the path into a bare `"conflict"`.

It will collect **all** conflicting paths, sync everything that is not conflicted, and return a
`Conflict` result carrying the list so the tool result names the files. The conflicted paths are left
alone on the SAF side — the user's edit is never overwritten.

The design point that matters: the excluded paths are written into the baseline
(`MirrorBaseline.excluded`, a new field with a default so existing baselines still decode). Without
that, the next `prepare()` would compare the untouched local copy against a baseline that claims the
file was synced, mark the tree dirty, and report `sync_required` for a project that is otherwise
healthy.

**Risk**

- A conflict is now sticky by design: the file keeps appearing as a conflict until the user resolves
  it. That is honest, but it must not spread. Because `hasLocalChanges()` (`:90`) gates every read
  tool through `RuntimeController.requireSafCurrent()` (`:87-90`), I will route all four comparison
  sites (`prepare :82`, `hasLocalChanges :90`, `sync :129`, `installCheckpoint :223`) through one
  helper that drops the excluded paths, so a conflict blocks writes to that file and nothing else.
- Partial sync means some files are pushed and some are not. The result states exactly which, and the
  baseline records it, so the state is recoverable rather than ambiguous.

**Test I will add**

`runtime/WorkspaceMirrorTest.kt`: two files edited on both sides and one file edited only in the
mirror; assert `sync()` writes the unconflicted file, leaves the two conflicted files untouched in
SAF, returns `Conflict` with both paths, and that `hasLocalChanges()` is false afterwards (reads
still work).

---

## 4. F15 — a failed post-Undo sync must not leave the two sides disagreeing

**Functions I will change**

- `runtime/RuntimeController.kt:142-160` — `undoLastCheckpoint`; the unguarded call is `active.sync()`
  at `:157`, immediately after `installCheckpoint` at `:150`.
- `runtime/TurnCheckpoint.kt:150-154` — add `markDesynced(id)` beside the existing `markUndone`.
- `runtime/TurnCheckpoint.kt:31-39` — `CheckpointRecord`, one new defaulted field.
- `runtime/TurnCheckpoint.kt:99` — `latest()`, to skip a desynced record.

**The change, in plain words**

Undo does: stage the pre-turn tree, install it into the mirror, then sync the mirror back to the
project. The install is already wrapped in a cleanup path (`:150-156`), but the sync at `:157` is not.
If that sync throws — storage full, a path the project refuses, an unsafe entry the snapshot now
rejects — the mirror holds the old content, the project still holds the new content, the checkpoint
stays marked live, and tapping Undo again repeats the same half-applied operation.

I will wrap the sync the same way the install is wrapped: on a non-cancellation failure, mark the
checkpoint `desynced` (a separate flag — recording it as `undone` would claim a success that did not
happen), then throw `CheckpointFailure("undo_desync")`.

`latest()` will skip desynced records, so the same broken Undo cannot be offered again.

The user-facing half is one sentence in `undoFailureMessage` (`ChatViewModel.kt:99`): the project may
hold either version and Rivet has stopped. That file is outside my list — see the coordination table.

**Risk**

- One new field on a persisted record. `CheckpointRecord` is `@Serializable` and the field has a
  default, so records written by 1.0.0 still decode. Same lesson as `ProviderConfig`: a field with
  no default makes the decoder throw and history would look corrupt.
- Skipping desynced records in `latest()` changes what Undo offers. It stays within the existing
  three-turn history (`MAX_RETAINED`, `:308`).

**Test I will add**

- `runtime/TurnCheckpointTest.kt`: `markDesynced` then `latest()` no longer returns that record, and a
  record written before the field existed still decodes.
- `runtime/WorkspaceMirrorTest.kt`, if the fault seam is reachable: a `TestDocumentsProvider` that
  fails the write on one marker path, so `installCheckpoint` succeeds and `sync()` throws; assert the
  error code is `undo_desync` and the checkpoint is no longer offered.

---

## 5. F18 — a big project must be editable, and the message must name the real limit

**Functions I will change**

- `runtime/TurnCheckpoint.kt:160-207` — `scan`; the `.git`-only skips are `:169` and `:182`, the limit
  throws are `:176` and `:186`.
- `runtime/TurnCheckpoint.kt:305-306` — `MAX_ENTRIES`, `MAX_CONTENT_BYTES`.
- `runtime/TurnCheckpoint.kt:47` — `begin`, to load the ignore list once per scan.
- `agent/AgentToolError.kt` — the `checkpoint_unavailable` and `checkpoint_limit` wording (shared with
  fix 1).

**The change, in plain words**

`scan` walks everything except `.git`. A project with `node_modules` installed therefore exceeds
50,000 entries, `begin()` refuses, `AgentLoop` turns that into `checkpoint_unavailable`, and every
edit is refused with a message about storage — advice that can never work, because the project did not
change size and there is no way to exclude a directory.

`scan` will read `\<root>/.rivetignore` and `\<root>/.gitignore` once and skip matched **directories**
with `FileVisitResult.SKIP_SUBTREE`, so a generated tree is never entered at all. Support is
deliberately small: blank lines and `#` comments, a trailing `/` for directories, `*` within one
segment, and literal segments. No negation, no `**`. Those two forms cover `node_modules/`, `build/`,
`dist/`, `.gradle/`, `target/` — the cases that actually break the limit.

The error will name the ceiling and what was counted, for example: "This project has more than 50,000
items outside the ignored folders. Rivet's Undo can cover 50,000. Add a folder to `.rivetignore`."
The 1 GiB case gets its own sentence with the measured size.

**Risk**

- A mis-parsed pattern would skip source files, and Undo would then not restore them — silent
  data-shape loss, the worst kind. Directory-only matching, no negation and no `**` keep the parser
  small enough to be obviously correct, and the test pins the coverage list.
- `.gitignore` semantics are subtle and users expect them exactly. Reading the file and honouring only
  part of it is a promise I am making quietly. It is still strictly better than refusing every edit,
  and the error text says which file to edit for more.
- Ignored directories are not checkpointed, so Undo will not restore them. That is the point, but the
  checkpoint record should say so — I will record the ignored roots on the record so the UI can stay
  honest later.

**Test I will add**

`runtime/TurnCheckpointTest.kt`: a tree with `src/a.kt` and 60,000 tiny files under `node_modules/`,
plus `.gitignore` containing `node_modules/`; assert `begin()` succeeds, `scan` does not contain any
`node_modules` path, and `src/a.kt` is present. Second case: without the ignore file, assert the
failure text contains both the number and the word `.rivetignore`.

---

## 6. F10 — files over 1 MiB

**Functions I will change**

- `workspace/WorkspaceText.kt:14` — `MAX_BYTES`; enforcement at `:48` (`decode`), `:61` (`encode`),
  `:80-95` (`patch`).
- `workspace/SafWorkspace.kt:408-424` — `read`, the `TOO_LARGE` check at `:412`.
- `workspace/SafWorkspace.kt:184-210` — `applyTextPatch`, the whole-file read at `:187`.

**The change, in plain words, in two parts**

*Part 1, entirely mine, lands now:* the honest error. `Reason.TOO_LARGE` already has a good sentence
("This file exceeds the 1 MiB editable-file limit."); fix 1 is what carries it to the model. Where the
size is known, the message names it.

*Part 2, needs one line from the executor owner:* read and patch a region instead of the whole file.
`read_file` already takes an `offset` argument and already pages by chunks (`AgentToolExecutor.kt:119-136`)
— it fails early only because `SafWorkspace.snapshot` must materialise the whole document first
(`:53`, `:408-424`). I will add `SafWorkspace.readRegion(path, offsetBytes, maxBytes)` that streams and
returns a slice plus the full size and hash, so the existing offset plumbing works past 1 MiB without
loading the file. For writes, I will add a region form of `applyTextPatch` that streams
head/patch/tail and still verifies the full-file hash before writing, so a lost update is still
impossible.

I am not raising `MAX_BYTES` itself. A bigger in-memory ceiling just moves the wall and grows the
memory a cheap phone must hold.

**Risk**

- Region edges must land on UTF-8 boundaries or the model receives half a character. `isUtf8Boundary`
  already exists in the executor (`:556`) and the region read must apply it on both ends.
- Hash semantics: a region patch must verify against the *whole file* hash it was given, not the
  slice, otherwise an edit made elsewhere in the file is silently lost.
- Part 2 is the largest piece of work in this cluster and touches an owned file plus one unowned call
  site. If Hermes prefers a smaller slice, ship Part 1 alone: the failure becomes explainable and the
  model stops looping on it, which is most of the user-visible damage.

**Test I will add**

- `workspace/WorkspaceTextTest.kt`: region boundary maths (offsets on and off a UTF-8 boundary, empty
  region, region exactly at EOF).
- `workspace/SafWorkspaceTest.kt`: a 1.5 MiB document through `TestDocumentsProvider`; assert
  `readRegion(offset = 1_200_000)` returns the tail and `snapshot` still refuses with `TOO_LARGE`;
  assert a region patch verifies the full hash and rejects a stale one.

---

## 7. F28 — keystore loss must not dead-end Settings

**Functions I will change**

- `storage/ProviderStore.kt:116-122` — `decodeForUse`, the throwing `?:` at `:119`.
- `storage/ProviderStore.kt:87-107` — `migrateLegacyHeaderValues`, the `check(...)` at `:93-95`.
- `storage/ProviderStore.kt:109-114` — `protectHeaders` (re-encrypt only what decrypted).

**The change, in plain words**

`decodeForUse` throws when one stored header cannot be decrypted. That flow backs the provider list,
and the migration that runs before every save throws the same way, so once the keystore entry is gone
the list cannot render and nothing can be saved — Settings is a permanent dead end whose only escape
is clearing app data.

Both sites will treat an unreadable header as data to drop, not as a fatal condition: the config keeps
its id, type, name, base URL and model, loses only the header nobody can read, and the store is
rewritten once (the migration already rewrites when something changed, `:103-105`). The list renders
and can be edited again.

The count of dropped headers is kept so Settings can say so in plain words: "Rivet removed 2 custom
headers it could not read. Add them again if this provider needs them." Losing a header silently would
turn into a confusing provider error later, which the current build hides anyway.

**Risk**

- Dropping a header a proxy requires makes that provider fail at request time. The notice above is
  what keeps this from being a silent downgrade.
- The config is kept rather than deleted. Deleting the whole provider would be cleaner internally and
  much worse for the user, who would have to re-enter the key as well.

**Test I will add**

`storage/ProviderHeaderStorageTest.kt`: seed the store with a header encrypted under key A, build a
`ProviderStore` whose `SecretStore` uses key B (`SecretStore.kt:17` test constructor), and assert the
list decodes with the config present, the header gone, `dropped` counted as 1, and that a following
`save()` succeeds instead of throwing.

---

## 8. F29 + F30 + F31 — the store safety trio

**Functions I will change**

- F29: `storage/ProviderStore.kt:46-51` (`decode`), `:53-62` (`save`), `:64-73` (`delete`),
  `:38-42` (the `configs` flow).
- F30: `storage/SecretStore.kt:25-38`, `:41-58`, `:62-71`, `:73-86`, `:97-109`, `:111-128` — the six
  crypto entry points.
- F31: `ui/provider/ProvidersViewModel.kt:415-421` (`delete`), `:424-425` (`setActive`).

**The change, in plain words**

*F29.* `decode()` catches everything and returns `emptyList()`, and `save()` then reads that empty list
and writes it back — so one unreadable stored config silently deletes every other provider on the next
save. Reading and writing need different behaviour. The write paths will use a `decodeOrThrow` so a
failed parse aborts the `dataStore.edit` with nothing written. The read path will not crash the list:
it catches, moves the unreadable raw value to a quarantine key, and renders an empty list with a
notice naming what happened. Nothing is destroyed and nothing is written over.

*F30.* The six entry points catch `GeneralSecurityException` and `IOException` only. A vendor keystore
that throws `ProviderException` or `IllegalStateException` — both `RuntimeException` — takes the app
down while saving a key. Each entry point gets one more catch returning its existing failure value.
`Error` is deliberately not caught.

*F31.* `delete` and `setActive` launch unguarded while every sibling write in the same class is
guarded (`:394-403`, `:140-147`). Both get the same shape: rethrow `CancellationException`, otherwise
report through `loadError`.

**Risk**

- F29 makes a corrupt store *unwritable* where it used to be silently wiped. That is the correct
  trade, but it needs the quarantine path to be real, or I have swapped a data-loss bug for a dead end
  (the same one F28 exists to remove).
- F30 catching `RuntimeException` can hide a genuine programming error inside these six functions.
  Keeping it to the crypto entry points, and not to helpers they call, keeps the blast radius small.
- F31's failure message reuses `loadError`, which Settings already renders with no action
  (`SettingsScreen.kt:162-166`).

**Test I will add**

- `storage/ProviderHeaderStorageTest.kt` or a new `storage/ProviderStoreTest.kt`: a DataStore seeded
  with an unparseable `configs` string; assert `save()` throws and the stored raw bytes are unchanged,
  and that the read flow yields empty plus a quarantine key holding the original text.
- `storage/SecretRecordCodecTest.kt` plus a `SecretStore` test: a key provider that throws
  `ProviderException` (needs the internal constructor extended to accept `() -> SecretKey`; it
  currently takes a fixed `SecretKey` at `:17`) — assert `saveApiKey` returns false and nothing is
  thrown.
- `ui/provider/ProvidersViewModelTest.kt` (new): a store whose `delete`/`setActive` throw; assert the
  viewmodel surfaces `loadError` and does not propagate.

---

## 9. F21 — tell the model what the sandbox is

**Function I will change**

- `chat/ChatViewModel.kt:1104-1105` — `systemInstruction(workspace = true)`, the workspace branch only.

**The change, in plain words**

One paragraph, appended to the existing prompt, stating the facts that are already in the code and
nowhere in the prompt: the shell is `/system/bin/sh -lc` with
`PATH=/system/bin:/system/xbin:/vendor/bin` (`RuntimeController.kt:270-277`); there is no package
manager and no node, python, git, npm or gradle; `HOME` and `TMPDIR` are app storage, not the project;
files written by download or by a command do not keep an executable bit, so a downloaded binary cannot
be run; and the ceilings that apply — file edit, command output, and how far Undo reaches.

Wording rules from the work order apply. The limits are stated as Rivet's, named with their real
values, and the paragraph ends with what to do instead (edit files and report, rather than trying to
install a toolchain).

**Risk**

- The numbers must match what actually ships. Cluster A owns the stream/output ceilings; until Hermes
  confirms them I will not write a number into the prompt. A prompt that overstates a limit is worse
  than no prompt. This is live, not hypothetical: `978ad9e` just raised the SSE and model-list caps
  from 1 MiB to 16 MiB (`Http.kt:47`, `:51`), so any number I wrote yesterday is already stale.
- Prompt length and stability: this text is constant, appended inside the existing instructions block,
  so the prefix stays byte-identical for every turn in a conversation. It invalidates each provider's
  prompt cache once, at upgrade, which is expected.

**Test I will add**

`agent/InternalContextTest.kt` style, or `chat/PromptPrefixTest.kt`: assert the workspace prompt
contains the shell path, the "no package manager" statement, and the download-cannot-run sentence;
assert the no-workspace prompt (`:1107`) still says only that there is no workspace; assert the
instructions block is byte-identical across two consecutive turns with the same workspace.

---

## Suggested order and commit shape

One local commit per fix, plain messages, no push. Order chosen so later fixes rest on earlier ones:

1. F08+F09 (vocabulary) — unblocks the honest-error half of F10 and F18.
2. F11 (symlink skip), 3. F12 (partial sync) — both touch `WorkspaceMirror.sync`/comparison helpers;
   ship together in that order to keep the helper introduction in one place.
4. F15 (post-Undo recovery).
5. F18 (ignore list + honest limit).
6. F10 part 1 (honest error) — part 2 only if Hermes assigns the executor line.
7. F28 (header drop), 8. F29+F30+F31 (store trio).
9. F21 (prompt) — last, so it can quote the numbers that actually shipped.

## Open questions for Hermes

1. Do I own the three one-line changes in the coordination table, or does another cluster?
2. F11 leaves `TurnCheckpoint.scan` still throwing on symlinks, so Undo stays unavailable for
   `npm install` projects even after the sync is fixed. Should I extend the skip into `scan` (Undo
   then cannot restore those paths), or leave it for a later pass?
3. F10 part 2 is the largest item here. Ship part 1 only, or take part 2 in this cluster?
4. For F21, the ceiling numbers Cluster A settles.

---

## Hermes review — APPROVED with these decisions (2026-11-01)

The design is approved as written, including the judgement calls: `provider`/`write` in `actions` but NOT `deterministic`; F28 keeps the config and drops only the unreadable header; F18 ignore support is directories-only; F15 gets its own `desynced` flag rather than a false `undone`. The work-order path correction is accepted (WorkspaceMirror/TurnCheckpoint live in `runtime/` — they are yours).

Decisions on the three open questions:
1. **The four out-of-list lines are YOURS.** `AgentToolExecutor.kt:467` (surface skipped paths), `AgentToolExecutor.kt:119-136` (F10 part 2), and the `undoFailureMessage` branch at `ChatViewModel.kt:99` all serve Cluster B fixes — take them. Keep each change to the minimum the design specifies.
2. **Yes — extend the symlink/special-file skip into `TurnCheckpoint.scan`**, same collect-and-report pattern as F11. A checkpoint that throws on the projects F11 just fixed would be a half-fix. Skipped entries are simply not checkpointed (they were never Rivet-managed content); record them the same way the mirror does.
3. **Yes — F10 part 2 belongs in this cluster.** The executor already pages `read_file` by offset; removing the whole-file materialisation in `SafWorkspace.snapshot` is the fix. `MAX_BYTES` stays as the in-memory edit bound.

F21 ceiling numbers (post-Cluster-A, commit 978ad9e — write these): streamed reply cap 16 MiB; single SSE line cap 128 KiB; stream progress deadline 120 s; model-list body cap 16 MiB with at most 2000 models retained; single-file in-memory edit limit 1 MiB (unchanged); checkpoint limits 50,000 entries / 1 GiB (with .rivetignore support after F18). Shell facts for the same paragraph: commands run via /system/bin/sh with PATH=/system/bin:/system/xbin:/vendor/bin; no node, python, git, npm or package manager; HOME and TMPDIR are app-private storage, not the project; downloaded files lose their executable bit and cannot be run.

Proceed to the edit pass: implement in the design's order, one local commit per fix, tests per fix (named seams may be added where the design calls for them). Never push. If a limit stops you, leave the tree committed and clean and say where you stopped.
