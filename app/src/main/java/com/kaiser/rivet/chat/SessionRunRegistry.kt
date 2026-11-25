package com.kaiser.rivet.chat

import android.app.Application
import com.kaiser.rivet.agent.AgentApprovalGate
import com.kaiser.rivet.agent.AgentRunControl
import com.kaiser.rivet.agent.NotificationApprovals
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
    val unseen = MutableStateFlow<Set<String>>(emptySet())
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
        NotificationApprovals.gate = approvals
        scope.launch {
            approvals.pending.collect { pending ->
                processes.updateApproval(pending)
                active.value?.update { it.copy(pendingApproval = pending) }
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
        check(active.value == null)
        lastRun = owner
        active.value = owner
    }

    fun finish(owner: SessionRun) {
        if (active.value !== owner) return
        owner.pauseAction = null
        owner.checkpoints = null
        if (!owner.value.budgetPaused) owner.raiseBudgetAction = null
        val id = owner.value.currentSessionId
        if (id != null && id !in visible.values) unseen.value += id
        active.value = null
    }

    fun show(view: Any, sessionId: String?) {
        visible[view] = sessionId
        if (sessionId != null) unseen.value -= sessionId
    }

    fun detach(view: Any) {
        visible.remove(view)
    }

    fun forget(id: String?) {
        if (lastRun?.value?.currentSessionId == id && lastRun !== active.value) lastRun = null
        if (id != null) unseen.value -= id
    }

    fun project(state: ChatUiState): ChatUiState {
        val pending = approvals.pending.value
        val selectedApproval = pending?.takeIf { it.sessionId == state.currentSessionId }
        val rows = unseen.value.associateWith { ConversationActivity.FinishedUnseen }.toMutableMap()
        active.value?.value?.currentSessionId?.let {
            rows[it] = if (pending?.sessionId == it) ConversationActivity.NeedsApproval else ConversationActivity.Running
        }
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
