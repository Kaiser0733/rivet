package com.kaiser.rivet.chat

import android.app.Application
import com.kaiser.rivet.agent.AgentApprovalGate
import com.kaiser.rivet.agent.AgentRunControl
import com.kaiser.rivet.runtime.ManagedProcesses
import com.kaiser.rivet.storage.CodingSessionHeader
import com.kaiser.rivet.storage.CodingSessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

enum class ConversationActivity { Running, NeedsApproval, FinishedUnseen }

internal class SessionRunRegistry(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val sessions = CodingSessions(app)
    val active = MutableStateFlow<SessionRun?>(null)
    // Single-transcript persistence can run without a stored conversation ID.
    val activeRuns = MutableStateFlow<Map<String?, SessionRun>>(emptyMap())
    val unseen = MutableStateFlow<Set<String>>(emptySet())
    val queued = MutableStateFlow<Set<String>>(emptySet())
    val history = MutableStateFlow<List<CodingSessionHeader>?>(null)
    var lastRun: SessionRun? = null
        private set
    private val visible = mutableMapOf<Any, String?>()
    val approvals = AgentApprovalGate { request ->
        val owner = active.value
        request.copy(sessionId = owner?.value?.currentSessionId, sessionTitle = owner?.sessionDisplayName)
    }

    init {
        val processes = ManagedProcesses.forApplication(app)
        scope.launch {
            approvals.pending.collect { pending ->
                processes.updateApproval(pending)
                activeRuns.value.values.forEach { owner ->
                    if (owner.value.currentSessionId == pending?.sessionId) {
                        owner.update { it.copy(pendingApproval = pending) }
                    }
                }
            }
        }
        scope.launch {
            AgentRunControl.pauses.collect { reason -> active.value?.pauseAction?.invoke(reason) }
        }
        scope.launch {
            AgentRunControl.raises.collect { lastRun?.raiseBudgetAction?.invoke() }
        }
    }

    fun begin(owner: SessionRun) {
        val id = owner.value.currentSessionId
        lastRun = owner
        activeRuns.value = activeRuns.value + (id to owner)
        active.value = owner
    }

    fun finish(owner: SessionRun) {
        val id = owner.value.currentSessionId
        activeRuns.value = activeRuns.value - id
        owner.pauseAction = null
        owner.checkpoints = null
        clearQueued(id)
        if (!owner.value.budgetPaused) owner.raiseBudgetAction = null
        if (id != null && id !in visible.values) unseen.value += id
        if (active.value === owner) active.value = activeRuns.value.values.lastOrNull()
    }

    fun isActive(owner: SessionRun): Boolean = activeRuns.value.values.any { it === owner }
    fun hasActiveRuns(): Boolean = activeRuns.value.isNotEmpty()
    fun activeCount(): Int = activeRuns.value.size
    fun isQueued(sessionId: String?): Boolean = sessionId != null && sessionId in queued.value
    fun markQueued(sessionId: String?) {
        if (sessionId != null) queued.value = queued.value + sessionId
    }
    fun clearQueued(sessionId: String?) {
        if (sessionId != null) queued.value = queued.value - sessionId
    }

    fun show(view: Any, sessionId: String?) {
        visible[view] = sessionId
        if (sessionId != null) unseen.value -= sessionId
    }

    fun detach(view: Any) {
        visible.remove(view)
    }

    fun forget(id: String?) {
        if (lastRun?.value?.currentSessionId == id && !isActive(lastRun!!)) lastRun = null
        if (id != null) unseen.value -= id
    }

    fun project(state: ChatUiState): ChatUiState {
        val pending = approvals.pending.value
        val selectedApproval = pending?.takeIf { it.sessionId == state.currentSessionId }
        val rows = unseen.value.associateWith { ConversationActivity.FinishedUnseen }.toMutableMap()
        for (owner in activeRuns.value.values) {
            val id = owner.value.currentSessionId ?: continue
            rows[id] = if (pending?.sessionId == id) ConversationActivity.NeedsApproval
                else ConversationActivity.Running
        }
        for (id in queued.value) rows[id] = ConversationActivity.Running
        return state.copy(
            pendingApproval = selectedApproval,
            backgroundApproval = pending?.takeUnless { it === selectedApproval },
            conversationActivity = rows,
        )
    }

    companion object {
        private var applicationRegistry: SessionRunRegistry? = null

        fun forApplication(app: Application): SessionRunRegistry = synchronized(this) {
            applicationRegistry ?: SessionRunRegistry(app).also { applicationRegistry = it }
        }
    }
}

internal val SessionRun.sessionDisplayName: String
    get() = value.currentSessionTitle?.takeUnless { it == "New session" } ?: "New conversation"
