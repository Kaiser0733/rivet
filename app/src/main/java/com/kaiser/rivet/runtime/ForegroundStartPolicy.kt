package com.kaiser.rivet.runtime

/**
 * Pure policies governing foreground-service start state reconciliation and
 * persistent process cleanup on stop failure.
 */
internal object ForegroundStartPolicy {
    fun reconcileServiceStarted(awaited: Boolean?, currentServiceStarted: Boolean): Boolean =
        if (awaited == null) currentServiceStarted else (awaited && currentServiceStarted)

    fun shouldFinishPersistentOnStopError(isPersistent: Boolean, threw: Boolean): Boolean =
        isPersistent
}
