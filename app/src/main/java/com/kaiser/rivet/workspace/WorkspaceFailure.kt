package com.kaiser.rivet.workspace

class WorkspaceFailure(val reason: Reason) : Exception(reason.message) {
    enum class Reason(val message: String) {
        INVALID_PATH("Use a workspace-relative path with valid names."),
        PERMISSION("Workspace access was lost. Select the project folder again."),
        MISSING("The file or folder no longer exists. Refresh its parent folder."),
        NOT_DIRECTORY("The parent is not a directory."),
        NOT_FILE("This path is not a file."),
        DUPLICATE("An entry with this name already exists."),
        UNSUPPORTED("This document provider does not support this operation."),
        ROOT("The workspace root cannot be deleted, renamed, or moved."),
        BINARY("Binary or non-UTF-8 content cannot be edited."),
        TOO_LARGE("This file exceeds the 1 MiB editable-file limit."),
        CONFLICT("The file changed outside this editor. Your changes were not saved. Reopen it to review the current version."),
        STALE_TARGET("This file or folder changed while approval was pending. Inspect it before requesting a new approval."),
        PATCH("Each patch must match exactly once. No changes were written."),
        LIMIT("The directory or operation exceeds the workspace safety limit."),
        PROVIDER("The document provider is unavailable or rejected the operation."),
        WRITE("The provider could not verify the saved content. The file may have changed; keep your draft and check the file."),
    }
}
