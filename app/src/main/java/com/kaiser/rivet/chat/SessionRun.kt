package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolLifecycle
import com.kaiser.rivet.agent.GoalDocument
import com.kaiser.rivet.agent.RunRecord
import com.kaiser.rivet.agent.SpendCaps
import com.kaiser.rivet.runtime.RunCheckpointInfo
import com.kaiser.rivet.storage.CodingSessionHeader
import com.kaiser.rivet.storage.ContextEstimate
import com.kaiser.rivet.storage.SessionUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal data class SessionRunUi(
    val messages: List<AgentMessage> = emptyList(),
    val streaming: Boolean = false,
    val compacting: Boolean = false,
    val streamingText: String = "",
    val streamingReasoning: String = "",
    val messageReasoning: Map<Int, String> = emptyMap(),
    val pendingApproval: AgentApprovalRequest? = null,
    val error: String? = null,
    val errorAction: ChatErrorAction? = null,
    val unsupportedReasoning: UnsupportedReasoningNotice? = null,
    val queuedSessionId: String? = null,
    val currentSessionId: String? = null,
    val currentSessionTitle: String? = null,
    val currentSessionWorkspaceId: String? = null,
    val usage: SessionUsage? = null,
    val contextEstimate: ContextEstimate? = null,
    val contextMeter: ContextMeterReading? = null,
    val activity: String? = null,
    val toolLifecycle: List<AgentToolLifecycle> = emptyList(),
    val recoveringProjectChanges: Boolean = false,
    val lastTurnFiles: List<String> = emptyList(),
    val lastTurnFileCount: Int = 0,
    val undoCheckpointId: String? = null,
    val undoing: Boolean = false,
    val notice: String? = null,
    val acceptedMessageCount: Long = 0,
    val commandAckCount: Long = 0,
    val turnStartedAt: Long? = null,
    val turnStartedWallClock: Long? = null,
    val turnDurationsMs: Map<Int, Long> = emptyMap(),
    val interruptedTurn: Boolean = false,
    val commandRunning: Boolean = false,
    val goal: GoalDocument? = null,
    val goalLine: String? = null,
    val goalAttachOffer: Boolean = false,
    val memoryMarkdown: String = "",
    val memoryLines: List<String> = emptyList(),
    val run: RunRecord? = null,
    val runRecovery: String? = null,
    val spendLabel: String? = null,
    val spendEstimate: String? = null,
    val spendFromEstimate: Boolean = false,
    val runSpendUsd: Double = 0.0,
    val budgetPaused: Boolean = false,
    val runCheckpoints: List<RunCheckpointInfo> = emptyList(),
    val runCheckpointNotice: String? = null,
)

internal data class ChatScreenUi(
    val ready: Boolean = false,
    val sessions: List<CodingSessionHeader> = emptyList(),
    val projectName: String? = null,
    val projectIdentity: String? = null,
    val projectLoading: Boolean = true,
    val projectError: String? = null,
    val starterError: String? = null,
    val spendCaps: SpendCaps = SpendCaps(),
    val userPricePerMillionUsd: Double? = null,
    val batteryAsk: Boolean = false,
)

internal class SessionRun(initial: ChatUiState = ChatUiState()) {
    private val snapshot = MutableStateFlow(SessionRunSnapshot.from(initial))
    val projection = snapshot.map { it.project() }

    var activeMessages: List<AgentMessage> = emptyList()
    var activeSummary: String = ""

    var value: ChatUiState
        get() = snapshot.value.project()
        set(value) { snapshot.value = SessionRunSnapshot.from(value) }

    fun update(transform: (ChatUiState) -> ChatUiState) {
        snapshot.update { SessionRunSnapshot.from(transform(it.project())) }
    }
}

private data class SessionRunSnapshot(val run: SessionRunUi, val screen: ChatScreenUi) {
    fun project() = ChatUiState(
        messages = run.messages,
        ready = screen.ready,
        streaming = run.streaming,
        compacting = run.compacting,
        streamingText = run.streamingText,
        streamingReasoning = run.streamingReasoning,
        messageReasoning = run.messageReasoning,
        pendingApproval = run.pendingApproval,
        error = run.error,
        errorAction = run.errorAction,
        unsupportedReasoning = run.unsupportedReasoning,
        queuedSessionId = run.queuedSessionId,
        sessions = screen.sessions,
        currentSessionId = run.currentSessionId,
        currentSessionTitle = run.currentSessionTitle,
        currentSessionWorkspaceId = run.currentSessionWorkspaceId,
        usage = run.usage,
        contextEstimate = run.contextEstimate,
        contextMeter = run.contextMeter,
        projectName = screen.projectName,
        projectIdentity = screen.projectIdentity,
        projectLoading = screen.projectLoading,
        projectError = screen.projectError,
        starterError = screen.starterError,
        activity = run.activity,
        toolLifecycle = run.toolLifecycle,
        recoveringProjectChanges = run.recoveringProjectChanges,
        lastTurnFiles = run.lastTurnFiles,
        lastTurnFileCount = run.lastTurnFileCount,
        undoCheckpointId = run.undoCheckpointId,
        undoing = run.undoing,
        notice = run.notice,
        acceptedMessageCount = run.acceptedMessageCount,
        commandAckCount = run.commandAckCount,
        turnStartedAt = run.turnStartedAt,
        turnStartedWallClock = run.turnStartedWallClock,
        turnDurationsMs = run.turnDurationsMs,
        interruptedTurn = run.interruptedTurn,
        commandRunning = run.commandRunning,
        goal = run.goal,
        goalLine = run.goalLine,
        goalAttachOffer = run.goalAttachOffer,
        memoryMarkdown = run.memoryMarkdown,
        memoryLines = run.memoryLines,
        run = run.run,
        runRecovery = run.runRecovery,
        spendCaps = screen.spendCaps,
        userPricePerMillionUsd = screen.userPricePerMillionUsd,
        spendLabel = run.spendLabel,
        spendEstimate = run.spendEstimate,
        spendFromEstimate = run.spendFromEstimate,
        runSpendUsd = run.runSpendUsd,
        budgetPaused = run.budgetPaused,
        runCheckpoints = run.runCheckpoints,
        runCheckpointNotice = run.runCheckpointNotice,
        batteryAsk = screen.batteryAsk,
    )

    companion object {
        fun from(state: ChatUiState) = SessionRunSnapshot(
            run = SessionRunUi(
                messages = state.messages,
                streaming = state.streaming,
                compacting = state.compacting,
                streamingText = state.streamingText,
                streamingReasoning = state.streamingReasoning,
                messageReasoning = state.messageReasoning,
                pendingApproval = state.pendingApproval,
                error = state.error,
                errorAction = state.errorAction,
                unsupportedReasoning = state.unsupportedReasoning,
                queuedSessionId = state.queuedSessionId,
                currentSessionId = state.currentSessionId,
                currentSessionTitle = state.currentSessionTitle,
                currentSessionWorkspaceId = state.currentSessionWorkspaceId,
                usage = state.usage,
                contextEstimate = state.contextEstimate,
                contextMeter = state.contextMeter,
                activity = state.activity,
                toolLifecycle = state.toolLifecycle,
                recoveringProjectChanges = state.recoveringProjectChanges,
                lastTurnFiles = state.lastTurnFiles,
                lastTurnFileCount = state.lastTurnFileCount,
                undoCheckpointId = state.undoCheckpointId,
                undoing = state.undoing,
                notice = state.notice,
                acceptedMessageCount = state.acceptedMessageCount,
                commandAckCount = state.commandAckCount,
                turnStartedAt = state.turnStartedAt,
                turnStartedWallClock = state.turnStartedWallClock,
                turnDurationsMs = state.turnDurationsMs,
                interruptedTurn = state.interruptedTurn,
                commandRunning = state.commandRunning,
                goal = state.goal,
                goalLine = state.goalLine,
                goalAttachOffer = state.goalAttachOffer,
                memoryMarkdown = state.memoryMarkdown,
                memoryLines = state.memoryLines,
                run = state.run,
                runRecovery = state.runRecovery,
                spendLabel = state.spendLabel,
                spendEstimate = state.spendEstimate,
                spendFromEstimate = state.spendFromEstimate,
                runSpendUsd = state.runSpendUsd,
                budgetPaused = state.budgetPaused,
                runCheckpoints = state.runCheckpoints,
                runCheckpointNotice = state.runCheckpointNotice,
            ),
            screen = ChatScreenUi(
                ready = state.ready,
                sessions = state.sessions,
                projectName = state.projectName,
                projectIdentity = state.projectIdentity,
                projectLoading = state.projectLoading,
                projectError = state.projectError,
                starterError = state.starterError,
                spendCaps = state.spendCaps,
                userPricePerMillionUsd = state.userPricePerMillionUsd,
                batteryAsk = state.batteryAsk,
            ),
        )
    }
}
