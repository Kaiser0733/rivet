package com.kaiser.rivet.ui.provider

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Owns the single network operation associated with the current provider
// editor. Replacing or mutating the editor invalidates every older ticket.
internal class ProviderEditorRequests(private val scope: CoroutineScope) {
    data class Ticket internal constructor(
        internal val revision: Long,
        internal val editorId: String,
    )

    private var revision = 0L
    private var job: Job? = null

    val running: Boolean
        get() = job?.isActive == true

    fun tryLaunch(
        editorId: String,
        operation: suspend (Ticket) -> Unit,
    ): Boolean {
        if (running) return false
        val ticket = Ticket(revision, editorId)
        val launched = scope.launch { operation(ticket) }
        job = launched
        launched.invokeOnCompletion {
            if (job === launched) job = null
        }
        return true
    }

    fun cancel(onCancelled: () -> Unit) {
        revision += 1
        job?.cancel()
        job = null
        onCancelled()
    }

    fun isCurrent(ticket: Ticket, editorId: String): Boolean =
        ticket.revision == revision && ticket.editorId == editorId
}
