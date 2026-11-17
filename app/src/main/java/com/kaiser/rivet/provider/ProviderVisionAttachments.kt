package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolResult

/** Base64 travels only in the transient request body, never in AgentToolResult.content. */
data class EncodedImage(val mime: String, val base64: String)

internal object ProviderVisionAttachments {
    /**
     * Only the tool message at the very end of the active loop may hydrate images.
     * Older screenshots keep their JSON path so a cleaned shot file cannot break a turn.
     */
    fun trailingImageResults(messages: List<AgentMessage>): List<AgentToolResult> {
        val last = messages.lastOrNull()?.takeIf { it.role == AgentRole.Tool } ?: return emptyList()
        return last.toolResults.filter { !it.imagePath.isNullOrBlank() }
    }

    fun trailingImages(
        messages: List<AgentMessage>,
        encode: (String) -> EncodedImage? = ImageAttachmentEncoder::encodeForWire,
    ): Map<String, EncodedImage> = buildMap {
        for (result in trailingImageResults(messages)) {
            val path = result.imagePath ?: continue
            encode(path)?.let { put(result.callId, it) }
        }
    }

    fun hasTrailingImages(messages: List<AgentMessage>): Boolean =
        trailingImageResults(messages).isNotEmpty()
}
