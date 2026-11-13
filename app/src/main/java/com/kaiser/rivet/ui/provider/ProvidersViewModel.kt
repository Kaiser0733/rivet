package com.kaiser.rivet.ui.provider

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.rivet.R
import com.kaiser.rivet.provider.ChatGptBundle
import com.kaiser.rivet.provider.ChatGptSignInResult
import com.kaiser.rivet.provider.ChatGptSignInSession
import com.kaiser.rivet.provider.revokeChatGptRefreshToken
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderError
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.detectedContextWindowForModel
import com.kaiser.rivet.provider.manualContextWindowForModel
import com.kaiser.rivet.provider.parseHeaders
import com.kaiser.rivet.provider.parseTokenCount
import com.kaiser.rivet.provider.providerClient
import com.kaiser.rivet.provider.resolveChatGptAccessToken
import com.kaiser.rivet.provider.resolveSignInClientId
import com.kaiser.rivet.provider.resolvedManualMaxOutputTokens
import com.kaiser.rivet.provider.runSignIn
import com.kaiser.rivet.provider.selectListedModel
import com.kaiser.rivet.provider.withManualContextWindow
import com.kaiser.rivet.provider.withManualMaxOutputTokens
import com.kaiser.rivet.provider.withModelId
import com.kaiser.rivet.runtime.Toolchain
import com.kaiser.rivet.storage.GitHubTokenStore
import com.kaiser.rivet.storage.HostIdStore
import com.kaiser.rivet.storage.ProviderStore
import com.kaiser.rivet.storage.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.UUID

data class GitHubUiState(
    val connected: Boolean = false,
    val gitAvailable: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

data class ProviderListUiState(
    val configs: List<ProviderConfig> = emptyList(),
    val activeId: String? = null,
    val loadError: String? = null,
    // Set when Rivet had to remove custom headers it could not read.
    val headerNotice: String? = null,
)

// List + editor state for the provider screens. API keys enter through
// SecretStore only; this layer sees booleans, never key material.
class ProvidersViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ProviderStore(app)
    private val secrets = SecretStore(app)
    private val gitHub = GitHubTokenStore(app)
    private val toolchain = Toolchain(app)

    private val _gitHubState = MutableStateFlow(GitHubUiState())
    val gitHubState: StateFlow<GitHubUiState> = _gitHubState.asStateFlow()

    private val _listState = MutableStateFlow(ProviderListUiState())
    val listState: StateFlow<ProviderListUiState> = _listState.asStateFlow()

    private val _editorState = MutableStateFlow(ProviderEditorState())
    val editorState: StateFlow<ProviderEditorState> = _editorState.asStateFlow()

    private val editorRequests = ProviderEditorRequests(viewModelScope)
    private var chatGptSession: ChatGptSignInSession? = null
    private var signInJob: Job? = null
    private var saveJob: Job? = null
    private var signOutJob: Job? = null
    internal var clientFactory: (ProviderConfig, String) -> com.kaiser.rivet.provider.ProviderClient =
        ::providerClient
    internal var signInRunner: suspend (
        String,
        (String) -> Unit,
        String,
        ChatGptSignInSession,
        HostIdStore,
    ) -> ChatGptSignInResult = { hostId, onAuthorizeUrl, clientId, session, hostIds ->
        runSignIn(
            hostId = hostId,
            onAuthorizeUrl = onAuthorizeUrl,
            clientId = clientId,
            session = session,
            hostIds = hostIds,
        )
    }

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
            try {
                store.activeId.collect { id ->
                    _listState.update { it.copy(activeId = id) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the current activeId if the preferences store cannot be read.
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            _gitHubState.update {
                it.copy(connected = gitHub.isConnected(),
                    gitAvailable = toolchain.availability().git)
            }
        }
        viewModelScope.launch {
            store.droppedHeaders.collect { count ->
                _listState.update {
                    it.copy(headerNotice = when {
                        count <= 0 -> null
                        count == 1 -> "Rivet could not read a custom header and removed it. Add it again in Settings if you still need it."
                        else -> "Rivet could not read $count custom headers and removed them. Add them again in Settings if you still need them."
                    })
                }
            }
        }
    }

    /** The token is only passed through to the Keystore-backed store; UI state stays boolean. */
    fun connectGitHub(token: String) {
        if (_gitHubState.value.busy) return
        if (!_gitHubState.value.gitAvailable) {
            _gitHubState.update { it.copy(error = "Git is not available in this build.") }
            return
        }
        _gitHubState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val saved = gitHub.connect(token)
            _gitHubState.update {
                it.copy(
                    connected = saved,
                    busy = false,
                    error = if (saved) null else
                        "Rivet couldn't save that token. Check that it has no spaces or line breaks, then try again.",
                )
            }
        }
    }

    fun disconnectGitHub() {
        if (_gitHubState.value.busy) return
        _gitHubState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            gitHub.disconnect()
            _gitHubState.update { it.copy(connected = false, busy = false, error = null) }
        }
    }

    fun startNewProvider(type: ProviderType) {
        cancelChatGptSignIn()
        cancelEditorRequests()
        _editorState.value = ProviderEditorState(
            config = ProviderConfig(
                id = UUID.randomUUID().toString(),
                type = type,
                name = providerDefaultName(type),
                baseUrl = type.defaultBaseUrl,
                model = "",
            ),
            isNew = true,
            chatGptEmail = null,
        )
    }

    fun startEdit(id: String) {
        val config = _listState.value.configs.firstOrNull { it.id == id } ?: return
        cancelChatGptSignIn()
        cancelEditorRequests()
        val email = if (config.type == ProviderType.ChatGptPlan) {
            decodeChatGptBundle(id)?.email
        } else null
        _editorState.value = ProviderEditorState(
            config = config,
            isNew = false,
            keyPresent = secrets.hasApiKey(id),
            headersText = config.headers.joinToString("\n") { "${it.name}: ${it.value}" },
            contextWindowInput = config.manualContextWindowForModel()?.toString().orEmpty(),
            maxOutputTokensInput = config.resolvedManualMaxOutputTokens()?.toString().orEmpty(),
            chatGptEmail = email,
        )
    }

    fun closeEditor() {
        cancelChatGptSignIn()
        cancelEditorRequests()
        _editorState.value = ProviderEditorState()
    }

    // Curated preference order for OpenRouter :free models. The first id present
    // in a live listing wins; otherwise the first :free model returned.
    private val preferredFreeModels = listOf(
        "nvidia/nemotron-3-super-120b-a12b:free",
        "google/gemma-4-31b-it:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "mistralai/mistral-small-3.1-24b-instruct:free",
        "google/gemma-3-27b-it:free",
    )

    fun saveFreeProvider(
        apiKey: String,
        onSaved: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            val res = getApplication<Application>().resources
            val key = apiKey.trim()
            if (key.isBlank()) {
                onError(res.getString(R.string.free_key_blank))
                return@launch
            }
            val hadProviders = _listState.value.configs.isNotEmpty()
            val activate = !hadProviders
            val id = UUID.randomUUID().toString()
            if (!secrets.saveApiKey(id, key)) {
                onError(res.getString(R.string.free_key_save_failed))
                return@launch
            }
            val config = ProviderConfig(
                id = id,
                type = ProviderType.OpenRouter,
                name = "Free tier",
                baseUrl = "https://openrouter.ai/api/v1",
                model = "",
            )
            try {
                store.save(config)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onError(res.getString(R.string.free_provider_save_failed))
                return@launch
            }
            if (activate) {
                try {
                    store.setActive(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Keep the saved provider; active selection can be fixed later.
                }
            }
            _listState.update { list ->
                if (list.activeId == null || activate) list.copy(activeId = id) else list
            }
            if (resolveFreeModel(id, key, onError)) onSaved()
        }
    }

    // ChatGPT plan equivalent of saveFreeProvider: the credential is an OAuth
    // bundle (stored encrypted via the OAuth slot), not an API key. Mirrors
    // the same save-then-resolve flow.
    fun saveChatGptPlanProvider(
        bundle: ChatGptBundle,
        onSaved: () -> Unit = {},
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            saveChatGptPlanProviderInternal(bundle, onSaved, onError)
        }
    }

    private suspend fun saveChatGptPlanProviderInternal(
        bundle: ChatGptBundle,
        onSaved: () -> Unit = {},
        onError: (String) -> Unit = {},
        providerId: String = UUID.randomUUID().toString(),
    ) {
        val encoded = try {
            com.kaiser.rivet.provider.chatGptBundleJson.encodeToString(ChatGptBundle.serializer(), bundle)
        } catch (e: Exception) {
            null
        }
        if (encoded == null) {
            onError("Rivet couldn't save your key securely. Try again.")
            return
        }
        val baseConfig = ProviderConfig(
            id = providerId,
            type = ProviderType.ChatGptPlan,
            name = "ChatGPT plan",
            baseUrl = ProviderType.ChatGptPlan.defaultBaseUrl,
            model = "",
        )
        // Resolve an initial model before persisting so failed fetches never leave an empty-model entry.
        val resolved = resolveChatGptPlanModel(baseConfig, bundle.accessToken, onError) ?: return
        if (!com.kaiser.rivet.provider.saveChatGptBundle(secrets, providerId, encoded)) {
            onError("Rivet couldn't save your key securely. Try again.")
            return
        }
        val hadProviders = _listState.value.configs.isNotEmpty()
        val activate = !hadProviders
        try {
            store.save(resolved)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            com.kaiser.rivet.provider.clearChatGptBundle(secrets, providerId)
            onError("Rivet couldn't save this provider. Try again.")
            return
        }
        if (activate) {
            try {
                store.setActive(providerId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the saved provider; active selection can be fixed later.
            }
        }
        _listState.update { list ->
            if (list.activeId == null || activate) list.copy(activeId = providerId) else list
        }
        if (_editorState.value.config.id == providerId) {
            _editorState.update {
                it.copy(config = resolved, isNew = false, chatGptEmail = bundle.email)
            }
        }
        onSaved()
    }

    fun startChatGptSignIn(onAuthorizeUrl: (String) -> Unit) {
        val initial = _editorState.value
        if (initial.signingIn || signInJob?.isActive == true) return
        val editorId = initial.config.id
        val isNew = initial.isNew
        val session = ChatGptSignInSession()
        chatGptSession = session
        _editorState.update {
            it.copy(
                signingIn = true,
                signInError = null,
                showChatGptIntro = false,
                signInCompleted = false,
            )
        }
        val launched = viewModelScope.launch {
            val app = getApplication<Application>()
            try {
                val hostIds = HostIdStore(app)
                val result = signInRunner(
                    hostIds.getOrCreateHostId(),
                    onAuthorizeUrl,
                    resolveSignInClientId(
                        bundleClientId = chatGptClientId(editorId),
                        pendingClientId = hostIds.pendingClientId(),
                    ),
                    session,
                    hostIds,
                )
                if (!com.kaiser.rivet.provider.ChatGptSessionGuards.canApplySignInOutcome(
                        isCurrentSession = chatGptSession === session,
                        currentEditorId = _editorState.value.config.id,
                        expectedEditorId = editorId,
                    )) {
                    return@launch
                }
                when (result) {
                    is ChatGptSignInResult.Success -> {
                        if (isNew) {
                            saveChatGptPlanProviderInternal(
                                bundle = result.bundle,
                                onSaved = {
                                    if (com.kaiser.rivet.provider.ChatGptSessionGuards.canApplySignInOutcome(
                                            isCurrentSession = chatGptSession === session,
                                            currentEditorId = _editorState.value.config.id,
                                            expectedEditorId = editorId,
                                        )) {
                                        if (shouldShowChatGptPlanIntro()) {
                                            markChatGptPlanIntroSeen()
                                            _editorState.update { it.copy(showChatGptIntro = true) }
                                        } else {
                                            _editorState.update { it.copy(signInCompleted = true) }
                                        }
                                    }
                                },
                                onError = { msg ->
                                    if (com.kaiser.rivet.provider.ChatGptSessionGuards.canApplySignInOutcome(
                                            isCurrentSession = chatGptSession === session,
                                            currentEditorId = _editorState.value.config.id,
                                            expectedEditorId = editorId,
                                        )) {
                                        _editorState.update { it.copy(signInError = msg) }
                                    }
                                },
                                providerId = editorId,
                            )
                        } else {
                            updateChatGptPlanBundleInternal(editorId, result.bundle)
                        }
                    }
                    else -> {
                        val message = chatGptSignInErrorMessage(result)
                        if (message != null && com.kaiser.rivet.provider.ChatGptSessionGuards.canApplySignInOutcome(
                                isCurrentSession = chatGptSession === session,
                                currentEditorId = _editorState.value.config.id,
                                expectedEditorId = editorId,
                            )) {
                            _editorState.update { it.copy(signInError = message) }
                        }
                    }
                }
            } finally {
                val ownsSession = chatGptSession === session
                if (ownsSession) {
                    chatGptSession = null
                }
                if (ownsSession && _editorState.value.config.id == editorId) {
                    _editorState.update { it.copy(signingIn = false) }
                }
            }
        }
        signInJob = launched
        launched.invokeOnCompletion {
            if (signInJob === launched) signInJob = null
        }
    }

    fun cancelChatGptSignIn() {
        chatGptSession?.cancel()
        chatGptSession = null
        signInJob?.cancel()
        signInJob = null
        _editorState.update { it.copy(signingIn = false) }
    }

    fun dismissChatGptPlanIntro() {
        _editorState.update { it.copy(showChatGptIntro = false) }
    }

    fun consumeChatGptSignInCompleted() {
        _editorState.update { it.copy(signInCompleted = false) }
    }

    // Resolve a real model for the freshly signed-in ChatGPT plan provider before saving.
    private suspend fun resolveChatGptPlanModel(
        baseConfig: ProviderConfig,
        accessToken: String,
        onError: (String) -> Unit,
    ): ProviderConfig? {
        return try {
            val models = providerClient(baseConfig, accessToken).listModels()
            if (models.isEmpty()) {
                onError("ChatGPT didn't return any models right now. Try again in a bit.")
                return null
            }
            baseConfig.selectListedModel(models.first())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is ProviderError.RateLimited,
                is ProviderError.ResourceExhausted,
                is ProviderError.UsageLimit,
                is ProviderError.Server -> "ChatGPT is busy right now. Try again in a bit."
                is ProviderError.Network,
                is ProviderError.Timeout -> "Rivet couldn't reach ChatGPT. Check your connection and try again."
                else -> "Rivet couldn't load ChatGPT's models. Try again."
            }
            onError(message)
            null
        }
    }

    // Resolve a real :free model for the freshly saved 'Free tier' provider and
    // persist it. Returns true only when a model was set.
    suspend fun resolveFreeModel(
        providerId: String,
        apiKey: String,
        onError: (String) -> Unit = {},
    ): Boolean {
        val res = getApplication<Application>().resources
        val existing = try {
            store.configSnapshot().firstOrNull { it.id == providerId }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (existing == null) {
            onError(res.getString(R.string.free_provider_save_failed))
            return false
        }
        return try {
            val models = providerClient(existing, apiKey).listModels()
            val freeModels = models.filter { it.id.endsWith(":free") }
            if (freeModels.isEmpty()) {
                onError(res.getString(R.string.free_models_none))
                return false
            }
            val freeIds = freeModels.map { it.id }.toSet()
            val chosenId = preferredFreeModels.firstOrNull { it in freeIds }
                ?: freeModels.first().id
            val chosen = freeModels.first { it.id == chosenId }
            store.save(existing.selectListedModel(chosen))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = when (e) {
                is ProviderError.RateLimited,
                is ProviderError.ResourceExhausted,
                is ProviderError.UsageLimit,
                is ProviderError.Server -> res.getString(R.string.free_models_busy)
                is ProviderError.Network,
                is ProviderError.Timeout -> res.getString(R.string.free_models_unreachable)
                else -> res.getString(R.string.free_models_failed)
            }
            onError(message)
            false
        }
    }

    // Decode the stored OAuth bundle (if any) for a ChatGptPlan provider using the shared permissive codec.
    private fun decodeChatGptBundle(providerId: String): ChatGptBundle? =
        com.kaiser.rivet.provider.decodeChatGptBundleRecord(secrets.oauthBundle(providerId))

    fun chatGptEmail(providerId: String): String? = decodeChatGptBundle(providerId)?.email

    fun chatGptClientId(providerId: String): String? = decodeChatGptBundle(providerId)?.clientId

    fun updateChatGptPlanBundle(bundle: ChatGptBundle, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            updateChatGptPlanBundleInternal(_editorState.value.config.id, bundle)
            onDone()
        }
    }

    private suspend fun updateChatGptPlanBundleInternal(id: String, bundle: ChatGptBundle) {
        val encoded = try {
            Json.encodeToString(ChatGptBundle.serializer(), bundle)
        } catch (_: Exception) {
            null
        }
        if (encoded != null &&
            com.kaiser.rivet.provider.saveChatGptBundle(secrets, id, encoded) &&
            _editorState.value.config.id == id) {
            _editorState.update { it.copy(chatGptEmail = bundle.email) }
        }
    }

    fun signOutChatGptPlan(onDone: () -> Unit = {}) {
        if (signOutJob?.isActive == true) return
        val launched = viewModelScope.launch {
            val id = _editorState.value.config.id
            val bundle = decodeChatGptBundle(id)
            if (bundle != null) {
                revokeChatGptRefreshToken(bundle.clientId, bundle.refreshToken)
            }
            com.kaiser.rivet.provider.clearChatGptBundle(secrets, id)
            if (com.kaiser.rivet.provider.ChatGptSessionGuards.canApplySignOutState(
                    currentEditorId = _editorState.value.config.id,
                    signedOutProviderId = id,
                )) {
                _editorState.update { it.copy(chatGptEmail = null) }
            }
            onDone()
        }
        signOutJob = launched
        launched.invokeOnCompletion { if (signOutJob === launched) signOutJob = null }
    }

    fun shouldShowChatGptPlanIntro(): Boolean {
        val prefs = getApplication<Application>().getSharedPreferences("rivet_chatgpt_plan", Context.MODE_PRIVATE)
        return !prefs.getBoolean("intro_seen", false)
    }

    fun markChatGptPlanIntroSeen() {
        getApplication<Application>().getSharedPreferences("rivet_chatgpt_plan", Context.MODE_PRIVATE)
            .edit().putBoolean("intro_seen", true).commit()
    }

    fun updateConfig(transform: (ProviderConfig) -> ProviderConfig) {
        cancelEditorRequests()
        _editorState.update { it.withConfigUpdate(transform) }
    }

    fun setContextWindowInput(value: String) {
        cancelEditorRequests()
        _editorState.update { state ->
            state.copy(
                contextWindowInput = value,
                config = state.config.withManualContextWindow(parseTokenCount(value)),
                test = null,
            )
        }
    }

    fun setMaxOutputTokensInput(value: String) {
        cancelEditorRequests()
        _editorState.update { state ->
            state.copy(
                maxOutputTokensInput = value,
                config = state.config.withManualMaxOutputTokens(parseTokenCount(value)),
                test = null,
            )
        }
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
        if (!com.kaiser.rivet.provider.ChatGptSessionGuards.canStartSave(saveJob?.isActive == true)) return
        cancelEditorRequests()
        val launched = viewModelScope.launch {
            val state = _editorState.value
            val config = state.config.withModelId(state.config.model, state.models)
            if (config.model.isBlank()) return@launch
            val invalidHeader = config.headers.firstOrNull {
                !com.kaiser.rivet.provider.ProviderHeaderPolicy.isValidHeaderName(it.name) ||
                    !com.kaiser.rivet.provider.ProviderHeaderPolicy.isValidHeaderValue(it.value)
            }
            if (invalidHeader != null) {
                _editorState.update {
                    it.copy(
                        test = TestUi(
                            false,
                            "Invalid custom header \"${invalidHeader.name}\". Use ASCII header token names and printable ASCII values.",
                        ),
                    )
                }
                return@launch
            }
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
                // Inside the same handling as the save: a DataStore write can fail here too, and
                // outside it the failure becomes an uncaught exception in this scope.
                if (state.isNew) store.setActive(config.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _editorState.update {
                    it.copy(test = TestUi(false, "Rivet couldn't save these provider settings securely. Try again."))
                }
                return@launch
            }
            _listState.update { list ->
                if (list.activeId == null) list.copy(activeId = config.id) else list
            }
            val needsAutoDetect = config.detectedContextWindowForModel() == null && state.models.isEmpty()
            if (_editorState.value.config.id == config.id) {
                _editorState.value = ProviderEditorState()
            }
            onSaved()
            if (needsAutoDetect) {
                autoDetectSavedModelMetadata(config.id, config.model)
            }
        }
        saveJob = launched
        launched.invokeOnCompletion { if (saveJob === launched) saveJob = null }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                store.delete(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _listState.update {
                    it.copy(loadError = "Rivet couldn't delete this provider securely. Try again.")
                }
                return@launch
            }
            _listState.update { list ->
                if (list.activeId == id) list.copy(activeId = null) else list
            }
        }
    }

    fun setActive(id: String) {
        viewModelScope.launch {
            try {
                store.setActive(id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _listState.update {
                    it.copy(loadError = "Rivet couldn't switch the active provider. Try again.")
                }
            }
        }
    }

    fun testConnection() {
        if (editorRequests.running) return
        val state = _editorState.value
        val isChatGptPlan = state.config.type == ProviderType.ChatGptPlan
        if (!isChatGptPlan && state.config.type != ProviderType.Local) {
            val apiKey = state.keyInput.trim().ifEmpty {
                secrets.apiKey(state.config.id) ?: ""
            }
            if (apiKey.isEmpty()) {
                _editorState.update { it.copy(test = TestUi(false, "Enter an API key first.")) }
                return
            }
        }
        _editorState.update { it.copy(test = null, busy = true) }
        if (!editorRequests.tryLaunch(state.config.id) { ticket ->
                try {
                    val credential = resolveEditorCredential(state.config, state.keyInput)
                    val result = providerClient(state.config, credential).testConnection()
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
        val isChatGptPlan = state.config.type == ProviderType.ChatGptPlan
        if (!isChatGptPlan && state.config.type != ProviderType.Local) {
            val apiKey = state.keyInput.trim().ifEmpty {
                secrets.apiKey(state.config.id) ?: ""
            }
            if (apiKey.isEmpty()) {
                _editorState.update { it.copy(fetchError = "Enter an API key first.") }
                return
            }
        }
        _editorState.update { it.copy(fetching = true, fetchError = null) }
        if (!editorRequests.tryLaunch(state.config.id) { ticket ->
                try {
                    val credential = resolveEditorCredential(state.config, state.keyInput)
                    val models = clientFactory(state.config, credential).listModels()
                    if (isCurrent(ticket)) {
                        _editorState.update {
                            val nextConfig = when {
                                it.config.model.isBlank() && models.isNotEmpty() ->
                                    it.config.selectListedModel(models.first())
                                it.config.model.isNotBlank() ->
                                    models.firstOrNull { m -> m.id == it.config.model }?.let { matched ->
                                        it.config.selectListedModel(matched)
                                    } ?: it.config
                                else -> it.config
                            }
                            val modelChanged = nextConfig.model != it.config.model
                            it.copy(
                                models = models,
                                config = nextConfig,
                                contextWindowInput = if (modelChanged) {
                                    nextConfig.manualContextWindowForModel()?.toString().orEmpty()
                                } else it.contextWindowInput,
                                maxOutputTokensInput = if (modelChanged) {
                                    nextConfig.resolvedManualMaxOutputTokens()?.toString().orEmpty()
                                } else it.maxOutputTokensInput,
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

    private suspend fun autoDetectSavedModelMetadata(providerId: String, targetModel: String) {
        try {
            val saved = store.configSnapshot().firstOrNull { it.id == providerId && it.model == targetModel }
                ?: return
            val credential = resolveEditorCredential(saved, "")
            if (saved.type != ProviderType.ChatGptPlan && credential.isEmpty()) return
            val models = providerClient(saved, credential).listModels()
            val matched = models.firstOrNull { it.id == targetModel } ?: return
            val latest = store.configSnapshot().firstOrNull { it.id == providerId && it.model == targetModel }
                ?: return
            val updated = latest.selectListedModel(matched)
            if (updated != latest) {
                store.save(updated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Best-effort background auto-detection; fallback window applies if unreachable.
        }
    }

    // ChatGPT plan providers authenticate with an OAuth access token (refreshed
    // when expired); every other type uses the API-key slot.
    private suspend fun resolveEditorCredential(config: ProviderConfig, keyInput: String): String {
        if (config.type == ProviderType.ChatGptPlan) {
            return when (val token = com.kaiser.rivet.provider.resolveChatGptToken(secrets, config)) {
                is com.kaiser.rivet.provider.ChatGptTokenResult.Ready -> token.accessToken
                com.kaiser.rivet.provider.ChatGptTokenResult.NetworkFailed -> throw ProviderError.Network("refresh")
                com.kaiser.rivet.provider.ChatGptTokenResult.ReauthRequired -> throw ChatGptReauthRequired()
            }
        }
        return keyInput.trim().ifEmpty { secrets.apiKey(config.id) ?: "" }
    }

    private class ChatGptReauthRequired : Exception()

    private fun editorError(error: Exception): String = when (error) {
        is ChatGptReauthRequired ->
            "ChatGPT sign-in expired. Sign in again."
        is ProviderError -> error.text()
        is IllegalArgumentException -> "Provider configuration contains an invalid header or value."
        is SecurityException -> ProviderError.Network("permission").text()
        else -> "Rivet couldn't connect to this provider. Check its settings and try again."
    }

    override fun onCleared() {
        cancelChatGptSignIn()
        super.onCleared()
    }
}
