package com.kaiser.rivet.ui.provider

import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
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
    val busy: Boolean = false,
    val fetching: Boolean = false,
    val test: TestUi? = null,
    val models: List<ModelInfo> = emptyList(),
    val fetchError: String? = null,
)

data class TestUi(val ok: Boolean, val message: String)

internal fun ProviderEditorState.withConfigUpdate(
    transform: (ProviderConfig) -> ProviderConfig,
): ProviderEditorState {
    val next = transform(config)
    val endpointChanged = next.type != config.type || next.baseUrl != config.baseUrl
    return copy(
        config = next,
        test = null,
        fetchError = null,
        models = if (endpointChanged) emptyList() else models,
    )
}
