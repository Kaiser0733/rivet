package com.kaiser.rivet.ui.provider

import com.kaiser.rivet.provider.ChatGptSignInResult
import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.manualContextWindowForModel
import com.kaiser.rivet.provider.resolvedManualMaxOutputTokens
import java.util.Locale

internal const val MODEL_RESULTS_LIMIT = 30

internal data class ModelFilterResult(
    val models: List<ModelInfo>,
    val totalCount: Int,
    val matchCount: Int,
    val selectedModelVisible: Boolean,
)

internal fun filterModels(
    models: List<ModelInfo>,
    selectedModel: String,
    query: String,
    limit: Int = MODEL_RESULTS_LIMIT,
): ModelFilterResult {
    val needle = query.trim().lowercase(Locale.ROOT)
    val matches = if (needle.isEmpty()) models else models.filter { model ->
        model.id.lowercase(Locale.ROOT).contains(needle) || model.label.lowercase(Locale.ROOT).contains(needle)
    }
    val visible = matches.take(limit.coerceAtLeast(0))
    return ModelFilterResult(visible, models.size, matches.size,
        selectedModel.isNotEmpty() && visible.any { it.id == selectedModel })
}

internal fun providerDefaultName(type: ProviderType): String = when (type) {
    ProviderType.OpenAiCompatible -> "OpenAI-compatible"
    ProviderType.OpenAi -> "OpenAI"
    ProviderType.Anthropic -> "Anthropic"
    ProviderType.Gemini -> "Gemini"
    ProviderType.OpenRouter -> "OpenRouter"
    ProviderType.ChatGptPlan -> "ChatGPT plan"
}

internal fun chatGptSignInErrorMessage(result: ChatGptSignInResult): String? = when (result) {
    is ChatGptSignInResult.Success, ChatGptSignInResult.UserCancelled -> null
    ChatGptSignInResult.PlanNotGranted ->
        "ChatGPT plan usage wasn't granted. Try again or use another provider."
    is ChatGptSignInResult.NetworkError ->
        "Rivet couldn't reach ChatGPT. Check your connection and try again."
    ChatGptSignInResult.InvalidCallback ->
        "ChatGPT sign-in didn't complete. Try again."
    ChatGptSignInResult.NeedsReauth ->
        "ChatGPT sign-in expired. Sign in again."
    ChatGptSignInResult.TimedOut ->
        "ChatGPT sign-in timed out. Try again."
    is ChatGptSignInResult.ServerRejected ->
        "ChatGPT sign-in didn't complete (error: ${result.code}). Try again."
}

data class ProviderEditorState(
    val config: ProviderConfig = ProviderConfig(
        id = "",
        type = com.kaiser.rivet.provider.ProviderType.OpenAiCompatible,
        name = "",
        baseUrl = "",
        model = "",
    ),
    val isNew: Boolean = false,
    val keyPresent: Boolean = false,
    val keyInput: String = "",
    val headersText: String = "",
    val contextWindowInput: String = "",
    val maxOutputTokensInput: String = "",
    val busy: Boolean = false,
    val fetching: Boolean = false,
    val test: TestUi? = null,
    val models: List<ModelInfo> = emptyList(),
    val fetchError: String? = null,
    val chatGptEmail: String? = null,
    val signingIn: Boolean = false,
    val signInError: String? = null,
    val showChatGptIntro: Boolean = false,
    val signInCompleted: Boolean = false,
)

data class TestUi(val ok: Boolean, val message: String)

internal fun ProviderEditorState.withConfigUpdate(
    transform: (ProviderConfig) -> ProviderConfig,
): ProviderEditorState {
    val next = transform(config)
    val endpointChanged = next.type != config.type || next.baseUrl != config.baseUrl
    val modelChanged = next.model != config.model
    return copy(
        config = next,
        contextWindowInput = if (modelChanged) {
            next.manualContextWindowForModel()?.toString().orEmpty()
        } else contextWindowInput,
        maxOutputTokensInput = if (modelChanged) {
            next.resolvedManualMaxOutputTokens()?.toString().orEmpty()
        } else maxOutputTokensInput,
        test = null,
        fetchError = null,
        models = if (endpointChanged) emptyList() else models,
    )
}
