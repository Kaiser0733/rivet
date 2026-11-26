# Plan F2 — File safety fixes (findings 1.1, 1.4, 1.2)

## Fix 1 — A failed SAF verify must never leave a truncated file (finding 1.1)

- **Root cause**:
  - Note on brief citation: the spec mentions `RuntimeController.kt:1357`, which does not exist (`RuntimeController.kt` is 880 lines). The real SAF write paths live in `app/src/main/java/com/kaiser/rivet/workspace/SafWorkspace.kt`:
    - `writeFileFrom` (`SafWorkspace.kt:178-218`): opens `uri(entry.documentId)` with `"rwt"` (truncating first) at line 192, streams `input`, and verifies at lines 208-211; any write/verify failure throws `WorkspaceFailure(Reason.WRITE)` leaving the document truncated on disk.
    - `write` (`SafWorkspace.kt:233-253`, used by `writeTextFile` and `applyTextPatch`): opens `uri(entry.documentId)` with `"rwt"` at line 242, writes `bytes`, and verifies at lines 245-247; any read/verify failure throws `WorkspaceFailure(Reason.WRITE)` leaving the document truncated on disk.
- **Change**:
  - Android SAF (`DocumentsContract`) has no atomic replace-over-existing document operation, and replacing every write with `createDocument` + `renameDocument` changes `documentId` and breaks provider name-normalization and call-count contracts. Instead, keep a verified pre-truncation rollback copy before opening `"rwt"` and restore it inside `NonCancellable` if writing or post-write verification fails (cleaning up any temporary rollback storage in `finally`).
  - In `write()`: reuse the `originalBytes` already read at line 237 (`read(entry, WorkspaceText.MAX_BYTES)`).
  - In `writeFileFrom()`: capture the original bytes during the pre-write `stream(entry, null)` hash check (in-memory up to a bounded cap, or in a temporary SAF rollback sibling / temp file for larger files), and restore them if the `"rwt"` write or post-write verification fails.
- **JiTTest**:
  - In `app/src/test/java/com/kaiser/rivet/workspace/SafWorkspaceTest.kt`: force post-write verification to fail on both `writeTextFile` and `writeFileFrom`, and assert `WorkspaceFailure(Reason.WRITE)` is thrown while the original file's bytes on disk remain byte-identical.

## Fix 2 — Checkpoints must cover `.git`, gitignored, and symlinked files (finding 1.4)

- **Root cause**:
  - `app/src/main/java/com/kaiser/rivet/runtime/TurnCheckpoint.kt`: `scan()` skips `.git` (lines 283, 319), gitignored paths (line 292), and symlinks (lines 279-281, 320-325). `stageUndo()` restores ignored directories from the current worktree (`preserveIgnoredDirectories`, lines 363-383) instead of `before.zip`, so a deleted ignored directory stays deleted.
  - `app/src/main/java/com/kaiser/rivet/runtime/WorkspaceMirror.kt`: the checkpoint scans the app-private mirror, and `isVcsPath` excludes `.git` from that mirror entirely. `installCheckpoint()` also strips `.git` from a replacement worktree.
  - The brief named `checkpoint/CheckpointManager.kt`; no such file exists. The real scan/filter code is `runtime/TurnCheckpoint.kt`.
- **Shipped decision** (commit `36281642`): full coverage was judged outside this fix's architecture — covering `.git` would require snapshotting the SAF tree at delete time because the mirror never holds repository metadata, and covering ignored trees would defeat the entry cap the checkpoint relies on. Per the spec's fallback, the delete approval now says plainly what Undo cannot cover:
  - `.git` → "Rivet keeps no copy of repository metadata such as .git, so this cannot be undone."
  - ignored folders and symlinks → the Undo line names both as uncovered.
- **JiTTest**: `AgentLoopTest.deleteApprovalSaysRepositoryMetadataCannotBeUndone` and `AgentLoopTest.deleteApprovalNamesIgnoredFoldersAndSymlinksAsUncovered`.

## Fix 3 — Deletes in externally-granted folders need an Undo path (finding 1.2)

- **Root cause**:
  - `app/src/main/java/com/kaiser/rivet/agent/AgentToolExecutor.kt` marks every external `delete_path` `undoable = false` with `NO_EXTERNAL_UNDO`, so the approval says the delete is permanent.
  - `app/src/main/java/com/kaiser/rivet/chat/ChatViewModel.kt` skips `checkpointGate` for external file mutations, and `RuntimeController.undoLastCheckpoint()` only operates on the current project mirror. Nothing snapshots the bytes before a grant delete.
- **Change**:
  - New `app/src/main/java/com/kaiser/rivet/workspace/ExternalDeleteStore.kt` stages a grant delete in app-private storage (`filesDir/external-deletes`) before the SAF delete runs. It supports files and whole folders, caps each staged copy at 500 MB / 50,000 files (the checkpoint caps), keeps up to 3 turn groups / 500 MB total, and writes an `entry.json` plus a payload tree atomically.
  - `AgentToolExecutor.delete_path` plans the delete before the approval: a readable path within the caps says Rivet keeps a copy and sets `undoable = true`; an over-cap path names its measured size and the cap and says it cannot be undone; an unreadable path says it cannot be copied and says it cannot be undone. It stages immediately before `workspace.delete`, keeps the copy when the delete succeeds, and discards it only when a failed delete left the target provably unchanged.
  - `ChatViewModel` owns one store, gives it a per-run turn id, rehydrates its own Undo row per session, and `undoExternalDelete()` restores the staged trees to their original grant paths and clears the store entry. A conflict (a newer file at the path) stops Undo before overwriting.
  - External deletes keep their own Undo row ("N files deleted outside the project") instead of sharing the checkpoint row.
- **JiTTest** (`ExternalDeleteStoreTest`): delete a binary file, Undo, bytes identical; delete a folder with files and an empty directory, Undo, all restored; two deletes in one turn share one row and both come back; over-cap approval names the size and cannot-undo line; unreadable file is not promised Undo; failed provider delete is an error and discards the copy; a newer file blocks Undo with `undo_conflict`.
