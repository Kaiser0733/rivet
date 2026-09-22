package com.kaiser.rivet.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.rivet.agent.AgentApprovalGate
import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentLoop
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentRunResult
import com.kaiser.rivet.agent.AgentStopReason
import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.PreparedAgentTool
import com.kaiser.rivet.agent.SafAgentWorkspace
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderError
import com.kaiser.rivet.provider.providerClient
import com.kaiser.rivet.storage.AgentSessionStore
import com.kaiser.rivet.storage.AgentSessionLimitException
import com.kaiser.rivet.storage.AgentSessionPersistence
import com.kaiser.rivet.storage.ProviderStore
import com.kaiser.rivet.storage.SecretStore
import com.kaiser.rivet.workspace.WorkspaceSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<AgentMessage> = emptyList(),
    val ready: Boolean = false,
    val streaming: Boolean = false,
    val streamText: String = "",
    val pendingApproval: AgentApprovalRequest? = null,
    val error: String? = null,
)

internal sealed interface ProviderRuntimeResult {
    data class Ready(val config: ProviderConfig, val apiKey: String) : ProviderRuntimeResult
    data class Failure(val message: String) : ProviderRuntimeResult
}

internal fun interface ProviderRuntimeSource {
    suspend fun load(): ProviderRuntimeResult
}

private class StoredProviderRuntimeSource(app: Application) : ProviderRuntimeSource {
    private val providerStore = ProviderStore(app)
    private val secrets = SecretStore(app)

    override suspend fun load(): ProviderRuntimeResult {
        val activeId = providerStore.activeIdSnapshot()
            ?: return ProviderRuntimeResult.Failure("No provider selected. Add one in Settings.")
        val config = providerStore.configSnapshot().firstOrNull { it.id == activeId }
            ?: return ProviderRuntimeResult.Failure("The selected provider no longer exists.")
        val apiKey = secrets.apiKey(config.id)
            ?: return ProviderRuntimeResult.Failure("No API key stored for \"${config.name}\".")
        return ProviderRuntimeResult.Ready(config, apiKey)
    }
}

class ChatViewModel private constructor(
    app: Application,
    private val sessionStore: AgentSessionPersistence,
    private val providerSource: ProviderRuntimeSource,
    private val workspaceSelection: WorkspaceSelection,
    private val clientFactory: (ProviderConfig, String) -> com.kaiser.rivet.provider.ProviderClient,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(
        app,
        AgentSessionStore(app),
        StoredProviderRuntimeSource(app),
        WorkspaceSelection(app),
        ::providerClient,
    )

    internal constructor(
        app: Application,
        sessionPersistence: AgentSessionPersistence,
        providerSource: ProviderRuntimeSource,
        clientFactory: (ProviderConfig, String) -> com.kaiser.rivet.provider.ProviderClient,
    ) : this(app, sessionPersistence, providerSource, WorkspaceSelection(app), clientFactory)

    private val approvals = AgentApprovalGate()

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var sendJob: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            val ticket = generation
            val restored = try {
                sessionStore.load()
            } catch (_: AgentSessionLimitException) {
                if (ticket == generation) {
                    _uiState.update { it.copy(ready = true, error = CONTEXT_LIMIT_ERROR) }
                }
                return@launch
            }
            if (ticket == generation) {
                _uiState.update { state ->
                    state.copy(
                        messages = restored.messages,
                        ready = true,
                        error = if (restored.interrupted) "Previous agent turn was interrupted." else state.error,
                    )
                }
                if (restored.interrupted) sessionStore.markInterrupted(false)
            }
        }
        viewModelScope.launch {
            approvals.pending.collect { pending ->
                _uiState.update { it.copy(pendingApproval = pending) }
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.streaming || !_uiState.value.ready) return
        val ticket = ++generation
        sendJob = viewModelScope.launch {
            val snapshot = providerSnapshot() ?: return@launch
            val restoredWorkspace = try {
                workspaceSelection.restore()
            } catch (_: Exception) {
                null
            }
            val workspace = restoredWorkspace?.first
            val workspaceId = workspace?.tree?.toString()
            val executor = workspace?.let { AgentToolExecutor(SafAgentWorkspace(it)) }
            val tools = if (executor == null) emptyList() else AgentToolExecutor.definitions
            val durable = (_uiState.value.messages + AgentMessage.user(trimmed)).toMutableList()
            try {
                sessionStore.save(durable, interrupted = true)
            } catch (_: AgentSessionLimitException) {
                if (ticket == generation) {
                    _uiState.update {
                        it.copy(streaming = false, streamText = "", pendingApproval = null, error = CONTEXT_LIMIT_ERROR)
                    }
                }
                return@launch
            }
            _uiState.update {
                it.copy(messages = durable.toList(), streaming = true, streamText = "", error = null)
            }
            val client = clientFactory(snapshot.config, snapshot.apiKey)

            val loop = AgentLoop(
                requestModel = { messages, definitions, onText ->
                    client.streamAgent(
                        AgentRequest(
                            model = snapshot.config.model,
                            messages = messages,
                            system = systemInstruction(workspace != null),
                            reasoning = snapshot.config.reasoning,
                            tools = definitions,
                        ),
                        onText,
                    )
                },
                prepareTool = { call ->
                    executor?.prepare(call) ?: PreparedAgentTool(call, null) {
                        AgentToolResult(
                            call.id,
                            call.name,
                            "{\"error\":\"workspace_unavailable\"}",
                            error = true,
                            summary = "Failed  ${call.name}",
                        )
                    }
                },
                requestApproval = approvals::await,
                workspaceIsCurrent = {
                    workspaceId == null || workspaceSelection.currentIdentity() == workspaceId
                },
                canPersistToolOutput = { assistant, correlatedErrors ->
                    sessionStore.canSaveWithReserve(
                        durable + assistant + correlatedErrors,
                        AgentLoop.MAX_ENCODED_TOOL_OUTPUT_RESERVE_BYTES,
                    )
                },
            )
            val streamed = StringBuffer()
            try {
                val runLoop: suspend () -> AgentRunResult = {
                    loop.run(
                        initial = durable,
                        tools = tools,
                        onText = { delta ->
                            if (ticket == generation) {
                                streamed.append(delta)
                                _uiState.update { it.copy(streamText = streamed.toString()) }
                            }
                        },
                        onMessage = { message ->
                            if (ticket == generation) {
                                val candidate = durable + message
                                sessionStore.save(candidate, interrupted = true)
                                durable += message
                                if (message.role == AgentRole.Assistant) streamed.setLength(0)
                                _uiState.update { it.copy(messages = durable.toList(), streamText = streamed.toString()) }
                            }
                        },
                    )
                }
                val result = if (workspaceId == null) runLoop() else coroutineScope {
                    val running = async { runLoop() }
                    val changed = async { workspaceSelection.awaitIdentityChange(workspaceId) }
                    select {
                        running.onAwait { completed ->
                            changed.cancelAndJoin()
                            completed
                        }
                        changed.onAwait {
                            approvals.cancel()
                            running.cancelAndJoin()
                            AgentRunResult(durable.toList(), AgentStopReason.WorkspaceChanged, 0, 0)
                        }
                    }
                }
                if (ticket == generation) finish(result, durable)
            } catch (_: AgentSessionLimitException) {
                withContext(NonCancellable) {
                    if (ticket == generation) {
                        sessionStore.markInterrupted(false)
                        _uiState.update {
                            it.copy(
                                messages = durable.toList(),
                                streaming = false,
                                streamText = "",
                                pendingApproval = null,
                                error = CONTEXT_LIMIT_ERROR,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (ticket == generation) {
                        sessionStore.save(durable, interrupted = false)
                        _uiState.update { it.copy(streaming = false, streamText = "", pendingApproval = null) }
                    }
                }
                throw e
            } catch (e: ProviderError) {
                Log.w("RivetChat", "agent request failed: ${e.javaClass.simpleName}")
                stableFailure(ticket, durable, e.text())
            } catch (e: Exception) {
                Log.w("RivetChat", "agent turn failed: ${e.javaClass.simpleName}")
                stableFailure(ticket, durable, "Unexpected error (${e.javaClass.simpleName}).")
            } finally {
                approvals.cancel()
            }
        }
    }

    private suspend fun finish(result: AgentRunResult, durable: List<AgentMessage>) {
        val error = when (result.stopReason) {
            AgentStopReason.Completed -> {
                val last = result.messages.lastOrNull()
                if (last?.role == AgentRole.Assistant && last.text.isBlank() && last.toolCalls.isEmpty()) {
                    ProviderError.EmptyResponse.text()
                } else null
            }
            AgentStopReason.IterationLimit -> "Agent stopped after ${AgentLoop.MAX_MODEL_ITERATIONS} model iterations."
            AgentStopReason.ToolCallLimit -> "Agent stopped after ${AgentLoop.MAX_TOOL_CALLS} tool calls."
            AgentStopReason.WorkspaceChanged -> "Workspace changed. The agent turn was stopped."
            AgentStopReason.SessionLimit -> CONTEXT_LIMIT_ERROR
        }
        val persisted = if (error == ProviderError.EmptyResponse.text()) durable.dropLast(1) else durable
        sessionStore.save(persisted, interrupted = false)
        _uiState.update {
            it.copy(messages = persisted, streaming = false, streamText = "", pendingApproval = null, error = error)
        }
    }

    private suspend fun stableFailure(ticket: Long, durable: List<AgentMessage>, message: String) {
        if (ticket != generation) return
        sessionStore.save(durable, interrupted = false)
        _uiState.update {
            it.copy(messages = durable, streaming = false, streamText = "", pendingApproval = null, error = message)
        }
    }

    private suspend fun providerSnapshot(): ProviderRuntimeResult.Ready? {
        return when (val result = providerSource.load()) {
            is ProviderRuntimeResult.Ready -> result
            is ProviderRuntimeResult.Failure -> failBeforeStart(result.message)
        }
    }

    private fun failBeforeStart(message: String): Nothing? {
        _uiState.update { it.copy(error = message) }
        return null
    }

    fun approve(callId: String) {
        approvals.resolve(callId, approved = true)
    }

    fun deny(callId: String) {
        approvals.resolve(callId, approved = false)
    }

    fun cancel() {
        approvals.cancel()
        sendJob?.cancel()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearChat() {
        val ticket = ++generation
        approvals.cancel()
        viewModelScope.launch {
            sendJob?.cancelAndJoin()
            if (ticket == generation) {
                sessionStore.clear()
                _uiState.value = ChatUiState(ready = true)
            }
        }
    }

    companion object {
        internal const val CONTEXT_LIMIT_ERROR =
            "This conversation reached Rivet's current context limit. Start a new session to continue."

        private fun systemInstruction(workspace: Boolean): String = if (workspace) {
            "You are a coding agent inside Rivet. Inspect relevant files before editing. Paths are relative to the selected workspace. Prefer targeted edits. Tool results are authoritative and mutations require approval. You have no terminal, shell, Git, build, or test execution. Never claim commands ran or invent file contents."
        } else {
            "You are a coding assistant inside Rivet. Keep answers clear and concise. No workspace is selected, and you have no file, terminal, shell, Git, build, or test access."
        }
    }
}
