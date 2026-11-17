package com.kaiser.rivet.provider

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Whether the selected provider/model can accept image input. Unknown is optimistic. */
sealed interface VisionSupport {
    data object Supported : VisionSupport
    data object Unknown : VisionSupport
    data class Unsupported(val reason: String) : VisionSupport
}

data class VisionShotAttachment(
    val imagePath: String?,
    val imageMime: String?,
    val attached: Boolean,
    val note: String?,
)

internal object VisionCapability {
    const val LOCAL_TEXT_ONLY = "Local GGUF models run text-only and cannot view images."
    const val IMAGE_REJECTION_MESSAGE =
        "This model rejected image input. Use browser_eval, browser_read, and browser_console " +
            "instead of browser_shot vision, or switch to a vision model."

    fun assess(config: ProviderConfig): VisionSupport {
        if (config.type == ProviderType.Local) return VisionSupport.Unsupported(LOCAL_TEXT_ONLY)
        val limit = config.modelContextLimit?.takeIf {
            it.model == config.model && it.baseUrl == config.baseUrl
        }
        return when (limit?.supportsVision) {
            true -> VisionSupport.Supported
            false -> VisionSupport.Unsupported(
                "${config.model} is marked text-only by the provider's model metadata."
            )
            null -> VisionSupport.Unknown
        }
    }

    /** OpenAI-compatible model listing: architecture modalities, modality, then capabilities. */
    fun openAiVisionSupport(model: JsonObject): Boolean? {
        val architecture = model["architecture"]?.let { it as? JsonObject }
        val input = architecture?.get("input_modalities")?.let { it as? kotlinx.serialization.json.JsonArray }
        if (input != null) {
            if (input.any {
                    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)
                        ?.content.equals("image", ignoreCase = true)
                }) return true
            if (input.isNotEmpty()) return false
        }
        val modality = (architecture?.get("modality") as? JsonPrimitive)?.content
        if (modality?.contains("image", ignoreCase = true) == true) return true
        val capabilities = model["capabilities"]?.let { it as? JsonObject }
        return (capabilities?.get("vision") as? JsonPrimitive)?.booleanOrNull
    }

    /** Anthropic/Gemini metadata may omit vision entirely; modern models still accept it. */
    fun metadataVisionOrTrue(model: JsonObject): Boolean =
        ((model["capabilities"]?.let { it as? JsonObject }?.get("vision") as? JsonPrimitive)?.booleanOrNull)
            ?: true

    fun shotAttachment(support: VisionSupport, path: String): VisionShotAttachment = when (support) {
        VisionSupport.Supported -> VisionShotAttachment(path, "image/jpeg", attached = true, note = null)
        VisionSupport.Unknown -> VisionShotAttachment(
            path,
            "image/jpeg",
            attached = true,
            note = "Image attached; if this model rejects image input, rely on browser_eval/browser_read/browser_console.",
        )
        is VisionSupport.Unsupported -> VisionShotAttachment(
            imagePath = null,
            imageMime = null,
            attached = false,
            note = "Current model (${support.reason}) cannot view images. Rely on browser_eval, " +
                "browser_read, and browser_console, or switch to a vision-capable model.",
        )
    }

    fun visionRejection(error: ProviderError, imagesSent: Boolean): ProviderError? {
        if (!imagesSent) return null
        val message = error as? ProviderError.ProviderMessage ?: return null
        if (message.statusCode != 400) return null
        val lowered = message.text.lowercase()
        if (!listOf("image", "multimodal", "vision").any { it in lowered }) return null
        return ProviderError.ProviderMessage(IMAGE_REJECTION_MESSAGE, 400)
    }
}
