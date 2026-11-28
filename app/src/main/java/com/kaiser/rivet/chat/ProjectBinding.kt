package com.kaiser.rivet.chat

import com.kaiser.rivet.workspace.sameWorkspaceTree

internal enum class ProjectBindingState { Loading, None, Matched, AccessLost, Mismatch }

internal fun projectBindingState(
    sessionId: String?,
    sessionWorkspaceId: String?,
    accessibleProjectId: String?,
    projectLoading: Boolean,
): ProjectBindingState {
    if (projectLoading) return ProjectBindingState.Loading
    if (sessionId == null) return ProjectBindingState.None
    if (sessionWorkspaceId == null) return ProjectBindingState.None
    if (accessibleProjectId == null) return ProjectBindingState.AccessLost
    if (sameWorkspaceTree(sessionWorkspaceId, accessibleProjectId)) return ProjectBindingState.Matched
    return ProjectBindingState.Mismatch
}

internal const val PROJECT_ACCESS_LOST_MESSAGE =
    "Rivet no longer has access to this project. Choose the folder again to restore file access."

internal const val PROJECT_BINDING_MISMATCH_MESSAGE =
    "This conversation belongs to another project. Choose that project or start a new conversation."
