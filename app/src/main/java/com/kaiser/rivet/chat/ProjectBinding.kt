package com.kaiser.rivet.chat

import com.kaiser.rivet.workspace.sameWorkspaceTree

internal enum class ProjectBindingState { Loading, None, Matched, AccessLost }

internal fun projectBindingState(
    sessionId: String?,
    sessionWorkspaceId: String?,
    accessibleProjectId: String?,
    projectLoading: Boolean,
): ProjectBindingState {
    if (projectLoading) return ProjectBindingState.Loading
    if (sessionId == null) return ProjectBindingState.None
    if (sessionWorkspaceId == null) return ProjectBindingState.None
    if (sameWorkspaceTree(sessionWorkspaceId, accessibleProjectId)) return ProjectBindingState.Matched
    return ProjectBindingState.AccessLost
}

internal const val PROJECT_ACCESS_LOST_MESSAGE =
    "Rivet no longer has access to this project. Choose the folder again to restore file access."
