package com.kaiser.rivet.chat

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.rivet.provider.ChatRequest
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderError
import com.kaiser.rivet.provider.providerClient
import com.kaiser.rivet.storage.ChatStore
import com.kaiser.rivet.storage.ProviderStore
import com.kaiser.rivet.storage.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streaming: Boolean = false,
    val streamText: String = "",
    val error: String? = null,
)

// Retained across rotation and process death by the ViewModelStore; all
// streaming work survives configuration changes without duplication.
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val providerStore = ProviderStore(app)
    private val chatStore = ChatStore(app)
    private val secrets = SecretStore(app)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var sendJob: Job? = null

    // Honest and tool-free: the model is a coding-oriented conversational
    // assistant inside Rivet, nothing more. No tool, file, or terminal
    // claims until Phase 4 gives it any.
    private val systemInstruction =
        "You are a coding assistant inside Rivet, an Android app. Keep answers clear and concise. You do not have access to tools, files, or a terminal."

    init {
        viewModelScope.launch {
            chatStore.messages.collect { stored ->
                // The live list is authoritative while streaming; storage
                // catches up on completion.
                if (!_uiState.value.streaming) {
                    _uiState.update { it.copy(messages = stored) }
                }
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.streaming) return
        sendJob = viewModelScope.launch {
            // Snapshot the provider and model at send time so changing
            // selection mid-stream cannot redirect an active request;
            // changes apply to the NEXT send.
            val activeId = providerStore.activeIdSnapshot() ?: run {
                _uiState.update { it.copy(error = "No provider selected. Add one in Settings.") }
                return@launch
            }
            val config: ProviderConfig =
                providerStore.configSnapshot().firstOrNull { it.id == activeId } ?: run {
                    _uiState.update { it.copy(error = "The selected provider no longer exists.") }
                    return@launch
                }
            val apiKey = secrets.apiKey(config.id) ?: run {
                _uiState.update { it.copy(error = "No API key stored for \"${config.name}\".") }
                return@launch
            }

            val outgoing = _uiState.value.messages + ChatMessage(ChatRole.User, trimmed)
            _uiState.update {
                it.copy(
                    messages = outgoing,
                    streaming = true,
                    streamText = "",
                    error = null,
                )
            }
            chatStore.save(outgoing)

            // Appended from the SSE reader thread; StringBuffer keeps the
            // concurrent read in the cancel path safe.
            val accumulated = StringBuffer()
            try {
                providerClient(config, apiKey).streamChat(
                    ChatRequest(
                        model = config.model,
                        messages = outgoing,
                        system = systemInstruction,
                        reasoning = config.reasoning,
                    ),
                ) { delta ->
                    accumulated.append(delta)
                    _uiState.update { it.copy(streamText = accumulated.toString()) }
                }
                val full = accumulated.toString()
                if (full.isBlank()) {
                    _uiState.update {
                        it.copy(streaming = false, error = ProviderError.EmptyResponse.text())
                    }
                } else {
                    val completed = outgoing + ChatMessage(ChatRole.Assistant, full)
                    _uiState.update { it.copy(messages = completed, streaming = false, streamText = "") }
                    chatStore.save(completed)
                }
            } catch (e: CancellationException) {
                // Mid-stream stop: keep whatever arrived as the reply.
                val partial = accumulated.toString()
                withContext(NonCancellable) {
                    if (partial.isNotBlank()) {
                        val stopped = outgoing + ChatMessage(ChatRole.Assistant, partial)
                        _uiState.update {
                            it.copy(messages = stopped, streaming = false, streamText = "")
                        }
                        chatStore.save(stopped)
                    } else {
                        _uiState.update { it.copy(streaming = false, streamText = "") }
                    }
                }
                throw e
            } catch (e: ProviderError) {
                // Category only; never body text or key material.
                Log.w("RivetChat", "stream failed: ${e.javaClass.simpleName}")
                _uiState.update { it.copy(streaming = false, streamText = "", error = e.text()) }
            } catch (e: Exception) {
                Log.w("RivetChat", "unexpected failure: ${e.javaClass.simpleName}")
                _uiState.update {
                    it.copy(
                        streaming = false,
                        streamText = "",
                        error = "Unexpected error (${e.javaClass.simpleName}).",
                    )
                }
            }
        }
    }

    fun cancel() {
        sendJob?.cancel()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun clearChat() {
        sendJob?.cancel()
        _uiState.value = ChatUiState()
        viewModelScope.launch { chatStore.clear() }
    }
}

