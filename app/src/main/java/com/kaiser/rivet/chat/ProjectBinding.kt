package com.kaiser.rivet.chat

internal enum class ProjectBindingState { Loading, None, Matched, AccessLost, Mismatch }

internal fun projectBindingState(
    sessionId: String?,
    sessionWorkspaceId: String?,
    accessibleProjectId: String?,
    projectLoading: Boolean,
): ProjectBindingState {
    if (projectLoading) return ProjectBindingState.Loading
    if (sessionId == null) return ProjectBindingState.None
    if (sessionWorkspaceId == null && accessibleProjectId == null) return ProjectBindingState.None
    if (sessionWorkspaceId == null) return ProjectBindingState.Mismatch
    if (accessibleProjectId == null) return ProjectBindingState.AccessLost
    if (sessionWorkspaceId == accessibleProjectId) return ProjectBindingState.Matched
    return ProjectBindingState.Mismatch
}

internal const val PROJECT_ACCESS_LOST_MESSAGE =
    "Rivet no longer has access to this project. Choose the folder again to continue this conversation."

internal const val PROJECT_BINDING_MISMATCH_MESSAGE =
    "This conversation belongs to another project. Choose that project or start a new conversation."
