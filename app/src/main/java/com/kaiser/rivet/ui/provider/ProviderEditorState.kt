package com.kaiser.rivet.ui.provider

import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderConfig

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
