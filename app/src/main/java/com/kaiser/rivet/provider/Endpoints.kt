package com.kaiser.rivet.provider

// Every URL a provider client requests is assembled here. Normalization is
// deliberately conservative: trailing slashes and one known duplicated
// suffix are folded away; an arbitrary base URL is never rewritten further.
object Endpoints {
    private fun openAiBase(base: String): String =
        base.trim().trimEnd('/').removeSuffix("/chat/completions")

    fun openAiChat(base: String): String = openAiBase(base) + "/chat/completions"

    fun openAiModels(base: String): String = openAiBase(base) + "/models"

    private fun anthropicBase(base: String): String = base.trim().trimEnd('/').removeSuffix("/v1")

    fun anthropicChat(base: String): String = anthropicBase(base) + "/v1/messages"

    fun anthropicModels(base: String): String = anthropicBase(base) + "/v1/models"

    private fun geminiBase(base: String): String = base.trim().trimEnd('/').removeSuffix("/v1beta")

    fun geminiChat(base: String, model: String): String {
        val m = model.trim().removePrefix("models/")
        return "${geminiBase(base)}/v1beta/models/$m:streamGenerateContent?alt=sse"
    }

    fun geminiModels(base: String): String = geminiBase(base) + "/v1beta/models"
}
