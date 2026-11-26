# F2 — file safety: failed writes, uncovered checkpoints, and external Undo

Branch `flaw-files`, based on `origin/batch/surface-fix` at
`6f9f78adaf462b8daef3e539c7375084f36bd134`. Implementation HEAD before this
report: `79ea7be724878e0d7db625dc99449b4b3e92db94`. Nothing was merged,
tagged, released, or built as an APK. `versionName`/`versionCode` are
unchanged, and the self-updater was not touched.

The brief's numbering is inconsistent with the flaw report: it calls the SAF
rollback "1.4" and the external-delete work "1.2". This report uses the flaw
report's numbers — 1.1 SAF truncation, 1.2 external grants, 1.4 checkpoint
gaps. Two file guesses in the handoff do not exist: there is no
`checkpoint/CheckpointManager.kt` (the scan is `runtime/TurnCheckpoint.kt`) and
no `RuntimeController.kt:1357` (the real SAF write paths are in
`workspace/SafWorkspace.kt`).

## 1. A failed SAF verify no longer leaves a truncated file (finding 1.1)

Commit `46b7cf74`. Existing before this session.

**Root cause.** `SafWorkspace.writeFileFrom` and `SafWorkspace.write` opened a
document with mode `"rwt"`, which truncates first, then verified the new bytes.
On a write or verify failure the original was already gone, so a storage
hiccup became permanent data loss.

**Change.** Both paths now read the pre-write bytes into a bounded rollback
buffer before opening `"rwt"`. If the write, stream, or post-write verify
fails, the previous bytes are written back inside `NonCancellable`; the
caller still receives `WorkspaceFailure(WRITE)`.

The buffer is in memory and capped at 8 MiB (`MAX_ROLLBACK_BYTES`). A file
larger than that is still written without a rollback copy; that limitation is
listed under honest gaps.

**JiTTest.** `SafWorkspaceTest.failedVerifyOrMidStreamWriteLeavesOriginalSafFileByteIdentical`.
The test arms the disposable provider to corrupt the document on the verify
read and asserts the original bytes are unchanged after
`WorkspaceFailure(WRITE)`.

**Red/green.** Parent is `6f9f78ad`. This session reproduced the parent
behaviour by disabling the two `restore(entry, original)` calls on the branch:
the test failed with `org.junit.ComparisonFailure` at
`SafWorkspaceTest.kt:603`. With the fix restored, `SafWorkspaceTest` passes
40/40. The exact parent commit itself cannot run the test as written because
the test file was added in `46b7cf74`.

## 2. Checkpoint gaps are named honestly (finding 1.4)

Commit `36281642`. Existing before this session.

**What is actually in the tree.** Full checkpoint coverage is **not**
implemented, and the brief's "checkpoint-coverage item" is the spec's
fallback instead:

- `runtime/TurnCheckpoint.kt` still skips `.git` subtrees and files
  (`scan`: `preVisitDirectory` around line 283, `visitFile` around line 319),
  gitignored paths (line 292), and symlinks/non-regular files (lines 279-281,
  320-325). `stageUndo` restores ignored directories from the *current*
  worktree (`preserveIgnoredDirectories`, lines 363-383), so a deleted ignored
  folder stays deleted.
- `runtime/WorkspaceMirror.kt` never mirrors `.git` (`isVcsPath`), and the
  checkpoint is taken from that mirror. Even a changed scan filter would have
  no `.git` bytes to save without a separate delete-time snapshot.
- Commit `36281642` therefore makes the delete approval honest:
  `.git` says "Rivet keeps no copy of repository metadata such as .git, so
  this cannot be undone"; ignored build folders and symlinks are named as
  not covered by the 3-turn Undo copy.

**Why full coverage was not added.** Covering `.git` needs a new
delete-time snapshot of the SAF tree, not the mirror; covering ignored trees
would defeat the entry cap that keeps checkpoints inside 50,000 entries and
500 MB. The spec says to stop a fix that needs a larger architecture change
and explain it; this is that explanation. The fallback is the shipped
protection.

**JiTTest.**
`AgentLoopTest.deleteApprovalSaysRepositoryMetadataCannotBeUndone` and
`AgentLoopTest.deleteApprovalNamesIgnoredFoldersAndSymlinksAsUncovered`.

**Red/green.** Parent is `46b7cf74` (the wording change's parent). This
session reproduced the parent wording on the branch: both tests failed with
`AssertionError` at `AgentLoopTest.kt:835` and `:848`. With `36281642`
restored, `AgentLoopTest` passes 68/68.

## 3. External-grant deletes have a real Undo path (finding 1.2)

Commit `79ea7be724878e0d7db625dc99449b4b3e92db94`.

**Root cause.** `AgentToolExecutor` marked every external `delete_path`
`undoable = false` and appended `NO_EXTERNAL_UNDO`; `ChatViewModel` skipped
the checkpoint gate for external file mutations; `RuntimeController` Undo
only operated on the current project mirror. Nothing copied the bytes before
the SAF provider removed them, so the approval's "Deletion is permanent" was
true.

**Change.**

- New `workspace/ExternalDeleteStore.kt` stages a grant delete in app-private
  storage (`filesDir/external-deletes`, deliberately not the evictable OS
  cache, so the Undo survives a process restart). Each entry writes
  `entry.json` and a payload tree atomically; files and whole folders are
  supported. Caps: 500 MB and 50,000 files per staged delete, three retained
  turn groups, 500 MB total retained.
- `AgentToolExecutor.delete_path` plans before the approval:
  - readable and inside the caps → approval says Rivet keeps a copy and sets
    `undoable = true`;
  - over a cap → the measured byte count and the limit are named, and the
    approval says it cannot be undone (the delete proceeds);
  - unreadable → the approval says Rivet could not copy it and it cannot be
    undone (the delete proceeds).
  The copy is made immediately before `workspace.delete`, after approval.
- A failed provider delete is still an error result, never a success. The
  staged copy is discarded only when a fresh structural scan proves the
  target is byte-identical to the pre-delete stamp; a partial delete keeps
  the copy.
- `ChatViewModel` owns one store, stamps each run with a turn id, and shows a
  separate Undo row ("N files deleted outside the project"). Undo restores
  the tree to the original grant path, verifies every restored file's size and
  SHA-256, then deletes the store entry. A newer file at the same path stops
  Undo with `undo_conflict` instead of overwriting it. Dismissing the row
  removes the staged copy.
- Readability is probed without copying twice: `SafWorkspace.canRead` opens
  the document and reads one byte.

**JiTTest.** `ExternalDeleteStoreTest` (7 tests): byte-identical binary file
restore; folder restore including an empty nested directory; two deletes in
one turn share one row and both return; over-cap approval names the size and
the cannot-undo line; unreadable file is not promised Undo; failed provider
delete is an error and discards an intact copy; a newer file blocks Undo.

**Red/green.** Parent is `36281642`. The new JiTTest necessarily cannot
compile on that commit because `ExternalDeleteStore` and the executor's
staging parameters do not exist there. To prove the test catches the missing
behaviour and not only the API, this session removed the plan/stage call on
the branch: `externalDeleteIsStagedAndUndoRestoresTheExactBytes` failed with
`AssertionError` at `ExternalDeleteStoreTest.kt:108`. With the fix restored,
all 7 tests pass.

## Verification

Environment: local Gradle 8.13 wrapper, JDK 17, Android SDK 35, offline
dependency mirror, Robolectric 28. The machine has no Java on `PATH`, so the
runs used `JAVA_HOME` explicitly; no Android device was used.

- `:app:compileDebugKotlin` — **BUILD SUCCESSFUL**.
- Final focused suite, restored branch `79ea7be7`:
  `ExternalDeleteStoreTest` 7/7, `SafWorkspaceTest` 40/40,
  `AgentLoopTest` 68/68, `AgentToolExecutorTest` 32/32,
  `UndoFailureMessageTest` 6/6 — **153 tests, 0 failures, 0 errors**.
- Red run with the three fixes' behaviour disabled on the branch:
  `SafWorkspaceTest.failedVerifyOrMidStreamWriteLeavesOriginalSafFileByteIdentical`
  (ComparisonFailure at line 603),
  `AgentLoopTest.deleteApprovalSaysRepositoryMetadataCannotBeUndone`
  (AssertionError at line 835),
  `AgentLoopTest.deleteApprovalNamesIgnoredFoldersAndSymlinksAsUncovered`
  (AssertionError at line 848), and
  `ExternalDeleteStoreTest.externalDeleteIsStagedAndUndoRestoresTheExactBytes`
  (AssertionError) — **4 failed, 0 passed**, as intended.
- No PR, merge, tag, release, or APK. Branch pushed as `flaw-files`.

## Honest gaps

1. **Fix 1 only protects files up to 8 MiB.** `RollbackBuffer` keeps the old
   bytes in memory and drops them when the file is larger, so a failed verify
   on a >8 MiB file can still leave it truncated. Commit `46b7cf74` comments
   this limitation. It was left as shipped because the brief treated Fix 1 as
   already closed; a temp-file rollback is the next step for that class.
2. **Fix 1/2 parent-red was reproduced by disabling the fix on the branch,
   not by checking out the parent commit.** Both tests were added in the same
   commit as their fix, so the parent commit cannot run them as written.
3. **Full checkpoint coverage for `.git`/ignored/symlinks is still absent.**
   The shipped protection is the approval copy, which is exactly what the
   spec allows when coverage is impossible; the classes are named in the
   dialog instead of being silently unprotected.
4. **External Undo restore is not transactional.** If the provider fails
   halfway through restoring a folder, some files can be back and the entry
   is kept; retrying then hits `undo_conflict` for the already-restored paths.
   The user is told the copy is still kept, but the UI has no repair flow.
5. **Restore covers bytes and directory structure, not timestamps or file
   permissions.** "Byte-for-byte" is verified (size + SHA-256); provider
   mtime is whatever creation produces.
6. **Staging uses `filesDir`, not `cacheDir` as the brief's wording says.**
   That is intentional for Undo durability, but it means the copies count
   against app data until Undo, dismissal, or pruning.
7. **The per-delete cap is 500 MB; a single turn can stage several deletes.**
   The current group can exceed the 500 MB retention target until a later
   prune because the just-created group is always kept. No test covers that
   multi-large-delete edge.
8. **The readability probe is not a guarantee.** It opens each file and reads
   one byte before approval. A provider that revokes access after approval
   makes staging fail; the delete then aborts (safe, but the approval promised
   a copy that was not made).
9. **Only unit/Robolectric coverage.** No physical SAF provider, no process
   restart after a real delete, and no Compose/UI test for the new Undo row.
10. **`ExternalDeleteStore` is new API surface.** The parent-red for Fix 3 is
   therefore behavioural (staging disabled), not a runnable parent test.
