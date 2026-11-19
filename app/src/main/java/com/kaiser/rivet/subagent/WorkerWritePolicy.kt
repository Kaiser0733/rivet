package com.kaiser.rivet.subagent

/**
 * Structural refusal for a worker write, decided before any user approval is
 * built. A worker with no declared write scope is read-only: its writes are
 * refused in the tool result and never escalated to a person.
 */
internal object WorkerWritePolicy {
    const val READ_ONLY = "worker_read_only"
    const val OUTSIDE = "outside_file_scope"

    fun refusal(registered: Boolean, scopeEmpty: Boolean, outsideScope: Boolean): String? = when {
        !registered -> OUTSIDE
        scopeEmpty -> READ_ONLY
        outsideScope -> OUTSIDE
        else -> null
    }
}
