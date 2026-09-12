package com.kaiser.rivet.ui.provider

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.TestResult
import com.kaiser.rivet.provider.parseHeaders
import com.kaiser.rivet.provider.providerClient
import com.kaiser.rivet.storage.ProviderStore
import com.kaiser.rivet.storage.SecretStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class ProviderListUiState(
    val configs: List<ProviderConfig> = emptyList(),
    val activeId: String? = null,
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

    private var fetchJob: Job? = null

    init {
        viewModelScope.launch {
            store.configs.collect { configs ->
                _listState.update { it.copy(configs = configs) }
            }
        }
        viewModelScope.launch {
            store.activeId.collect { id ->
                _listState.update { it.copy(activeId = id) }
            }
        }
    }

    fun startNewProvider(type: ProviderType) {
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
        viewModelScope.launch {
            val config = _listState.value.configs.firstOrNull { it.id == id } ?: return@launch
            _editorState.value = ProviderEditorState(
                config = config,
                isNew = false,
                keyPresent = secrets.hasApiKey(id),
                headersText = config.headers.joinToString("\n") { "${it.name}: ${it.value}" },
            )
        }
    }

    fun closeEditor() {
        fetchJob?.cancel()
        _editorState.value = ProviderEditorState()
    }

    fun updateConfig(transform: (ProviderConfig) -> ProviderConfig) {
        _editorState.update { it.copy(config = transform(it.config)) }
    }

    // Blank means "keep the existing stored key" for an edit; only a
    // non-empty value replaces it.
    fun setKeyInput(value: String) {
        _editorState.update { it.copy(keyInput = value) }
    }

    fun save(onSaved: () -> Unit) {
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
            store.save(config)
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
        val state = _editorState.value
        val apiKey = state.keyInput.trim().ifEmpty {
            secrets.apiKey(state.config.id) ?: ""
        }
        if (apiKey.isEmpty()) {
            _editorState.update { it.copy(test = TestUi(false, "Enter an API key first.")) }
            return
        }
        _editorState.update { it.copy(test = null, busy = true) }
        viewModelScope.launch {
            val result = try {
                providerClient(state.config, apiKey).testConnection()
            } catch (e: Exception) {
                TestResult(false, "Unexpected error (${e.javaClass.simpleName}).")
            }
            _editorState.update {
                it.copy(busy = false, test = TestUi(result.ok, result.message))
            }
        }
    }

    fun fetchModels() {
        val state = _editorState.value
        val apiKey = state.keyInput.trim().ifEmpty {
            secrets.apiKey(state.config.id) ?: ""
        }
        if (apiKey.isEmpty()) {
            _editorState.update { it.copy(test = TestUi(false, "Enter an API key first.")) }
            return
        }
        fetchJob?.cancel()
        _editorState.update { it.copy(fetching = true, fetchError = null) }
        fetchJob = viewModelScope.launch {
            try {
                val models = providerClient(state.config, apiKey).listModels()
                _editorState.update {
                    it.copy(
                        fetching = false,
                        models = models,
                        // Selecting the first fetched model would surprise a
                        // user who typed one manually; only suggest when empty.
                        config = if (it.config.model.isBlank() && models.isNotEmpty()) {
                            it.config.copy(model = models.first().id)
                        } else it.config,
                    )
                }
            } catch (e: com.kaiser.rivet.provider.ProviderError) {
                _editorState.update {
                    it.copy(fetching = false, fetchError = e.text())
                }
            } catch (e: Exception) {
                _editorState.update {
                    it.copy(fetching = false, fetchError = "Unexpected error (${e.javaClass.simpleName}).")
                }
            }
        }
    }

    fun clearTest() {
        _editorState.update { it.copy(test = null) }
    }

    fun updateHeadersText(text: String) {
        _editorState.update {
            it.copy(headersText = text, config = it.config.copy(headers = parseHeaders(text)))
        }
    }

    fun deleteAndClose() {
        val id = _editorState.value.config.id
        closeEditor()
        delete(id)
    }
}
