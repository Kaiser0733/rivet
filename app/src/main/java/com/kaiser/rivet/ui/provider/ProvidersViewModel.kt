package com.kaiser.rivet.ui.provider

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderError
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.parseHeaders
import com.kaiser.rivet.provider.providerClient
import com.kaiser.rivet.provider.selectListedModel
import com.kaiser.rivet.storage.ProviderStore
import com.kaiser.rivet.storage.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ProviderListUiState(
    val configs: List<ProviderConfig> = emptyList(),
    val activeId: String? = null,
    val loadError: String? = null,
)

// List + editor state for the provider screens. API keys enter through
// SecretStore only; this layer sees booleans, never key material.
class ProvidersViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProviderStore(app)
    private val secrets = SecretStore(app)

    private val _listState = MutableStateFlow(ProviderListUiState())
    val listState: StateFlow<ProviderListUiState> = _listState.asStateFlow()

    private val _editorState = MutableStateFlow(ProviderEditorState())
    val editorState: StateFlow<ProviderEditorState> = _editorState.asStateFlow()

    private val editorRequests = ProviderEditorRequests(viewModelScope)

    init {
        viewModelScope.launch {
            try {
                store.configs.collect { configs ->
                    _listState.update { it.copy(configs = configs, loadError = null) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _listState.update {
                    it.copy(loadError = "Rivet couldn't securely read provider settings. Re-enter any custom headers in Settings.")
                }
            }
        }
        viewModelScope.launch {
            store.activeId.collect { id ->
                _listState.update { it.copy(activeId = id) }
            }
        }
    }

    fun startNewProvider(type: ProviderType) {
        cancelEditorRequests()
        _editorState.value = ProviderEditorState(
            config = ProviderConfig(
                id = UUID.randomUUID().toString(),
                type = type,
                // Default name matches the type; the user renames freely.
                name = type.name,
                baseUrl = type.defaultBaseUrl,
                model = "",
            ),
            isNew = true,
        )
    }

    fun startEdit(id: String) {
        val config = _listState.value.configs.firstOrNull { it.id == id } ?: return
        cancelEditorRequests()
        _editorState.value = ProviderEditorState(
            config = config,
            isNew = false,
            keyPresent = secrets.hasApiKey(id),
            headersText = config.headers.joinToString("\n") { "${it.name}: ${it.value}" },
        )
    }

    fun closeEditor() {
        cancelEditorRequests()
        _editorState.value = ProviderEditorState()
    }

    fun updateConfig(transform: (ProviderConfig) -> ProviderConfig) {
        cancelEditorRequests()
        _editorState.update { it.withConfigUpdate(transform) }
    }

    // Blank means "keep the existing stored key" for an edit; only a
    // non-empty value replaces it.
    fun setKeyInput(value: String) {
        cancelEditorRequests()
        _editorState.update {
            it.copy(
                keyInput = value,
                test = null,
                models = emptyList(),
                fetchError = null,
            )
        }
    }

    fun save(onSaved: () -> Unit) {
        cancelEditorRequests()
        viewModelScope.launch {
            val state = _editorState.value
            val config = state.config
            if (config.model.isBlank()) return@launch
            if (state.keyInput.isNotBlank() &&
                !secrets.saveApiKey(config.id, state.keyInput.trim())
            ) {
                _editorState.update {
                    it.copy(test = TestUi(false, "Unable to store the API key securely."))
                }
                return@launch
            }
            try {
                store.save(config)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _editorState.update {
                    it.copy(test = TestUi(false, "Rivet couldn't save these provider settings securely. Try again."))
                }
                return@launch
            }
            if (state.isNew) {
                store.setActive(config.id)
            }
            _listState.update { list ->
                if (list.activeId == null) list.copy(activeId = config.id) else list
            }
            _editorState.value = ProviderEditorState()
            onSaved()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            store.delete(id)
            _listState.update { list ->
                if (list.activeId == id) list.copy(activeId = null) else list
            }
        }
    }

    fun setActive(id: String) {
        viewModelScope.launch { store.setActive(id) }
    }

    fun testConnection() {
        if (editorRequests.running) return
        val state = _editorState.value
        val apiKey = state.keyInput.trim().ifEmpty {
            secrets.apiKey(state.config.id) ?: ""
        }
        if (apiKey.isEmpty()) {
            _editorState.update { it.copy(test = TestUi(false, "Enter an API key first.")) }
            return
        }
        _editorState.update { it.copy(test = null, busy = true) }
        if (!editorRequests.tryLaunch(state.config.id) { ticket ->
                try {
                    val result = providerClient(state.config, apiKey).testConnection()
                    if (isCurrent(ticket)) {
                        _editorState.update { it.copy(test = TestUi(result.ok, result.message)) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isCurrent(ticket)) {
                        _editorState.update { it.copy(test = TestUi(false, editorError(e))) }
                    }
                } finally {
                    if (isCurrent(ticket)) _editorState.update { it.copy(busy = false) }
                }
            }
        ) {
            _editorState.update { it.copy(busy = false) }
        }
    }

    fun fetchModels() {
        if (editorRequests.running) return
        val state = _editorState.value
        val apiKey = state.keyInput.trim().ifEmpty {
            secrets.apiKey(state.config.id) ?: ""
        }
        if (apiKey.isEmpty()) {
            _editorState.update { it.copy(test = TestUi(false, "Enter an API key first.")) }
            return
        }
        _editorState.update { it.copy(fetching = true, fetchError = null) }
        if (!editorRequests.tryLaunch(state.config.id) { ticket ->
                try {
                    val models = providerClient(state.config, apiKey).listModels()
                    if (isCurrent(ticket)) {
                        _editorState.update {
                            it.copy(
                                models = models,
                                // Keep a manually entered model; suggest the
                                // first fetched id only when the field is empty.
                                config = if (it.config.model.isBlank() && models.isNotEmpty()) {
                                    it.config.selectListedModel(models.first())
                                } else it.config,
                            )
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (isCurrent(ticket)) {
                        _editorState.update { it.copy(fetchError = editorError(e)) }
                    }
                } finally {
                    if (isCurrent(ticket)) _editorState.update { it.copy(fetching = false) }
                }
            }
        ) {
            _editorState.update { it.copy(fetching = false) }
        }
    }

    fun clearTest() {
        _editorState.update { it.copy(test = null) }
    }

    fun updateHeadersText(text: String) {
        cancelEditorRequests()
        _editorState.update {
            it.copy(
                headersText = text,
                config = it.config.copy(headers = parseHeaders(text)),
                test = null,
                models = emptyList(),
                fetchError = null,
            )
        }
    }

    fun deleteAndClose() {
        val id = _editorState.value.config.id
        closeEditor()
        delete(id)
    }

    private fun cancelEditorRequests() {
        editorRequests.cancel {
            _editorState.update { it.copy(busy = false, fetching = false) }
        }
    }

    private fun isCurrent(ticket: ProviderEditorRequests.Ticket): Boolean =
        editorRequests.isCurrent(ticket, _editorState.value.config.id)

    private fun editorError(error: Exception): String = when (error) {
        is ProviderError -> error.text()
        is IllegalArgumentException -> "Provider configuration contains an invalid header or value."
        is SecurityException -> ProviderError.Network("permission").text()
        else -> "Rivet couldn't connect to this provider. Check its settings and try again."
    }
}
