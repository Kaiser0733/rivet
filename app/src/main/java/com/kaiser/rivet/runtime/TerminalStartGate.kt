package com.kaiser.rivet.runtime

internal enum class TerminalOpenReset {
    NONE,
    CLOSE_SILENT,
    CLOSE_ENDED,
}

/**
 * Pure state-transition predicates guarding [InteractiveTerminalSession] start,
 * reopen, and launch completion against overlapping closes or project switches.
 */
internal object TerminalStartGate {
    fun decideOpenReset(
        hasLiveEngine: Boolean,
        isStarting: Boolean,
        liveCwd: String?,
        cwdExists: Boolean,
        projectIdentity: String?,
        existingIdentity: String?,
        jobCancelled: Boolean,
    ): TerminalOpenReset {
        if (!hasLiveEngine && !isStarting) return TerminalOpenReset.NONE
        if (projectIdentity != null && existingIdentity != null && existingIdentity != projectIdentity) {
            return TerminalOpenReset.CLOSE_ENDED
        }
        if ((!liveCwd.isNullOrEmpty() && !cwdExists) || (isStarting && jobCancelled)) {
            return TerminalOpenReset.CLOSE_SILENT
        }
        return TerminalOpenReset.NONE
    }

    fun canCompleteLaunch(
        ticket: Long,
        currentGeneration: Long,
        finished: Boolean,
        requested: Boolean,
        jobActive: Boolean,
    ): Boolean = ticket == currentGeneration && !finished && requested && jobActive

    fun canAcceptCreatedEngine(
        ticket: Long,
        currentGeneration: Long,
        finished: Boolean,
        requested: Boolean,
        jobActive: Boolean,
    ): Boolean = ticket == currentGeneration && !finished && requested && jobActive

    fun canAcceptManagedRegistration(jobActive: Boolean): Boolean = jobActive
}
