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

    fun prepare(
        messages: List<AgentMessage>,
        support: VisionSupport,
        encode: (String) -> EncodedImage? = ImageAttachmentEncoder::encodeForWire,
    ): Pair<List<AgentMessage>, Map<String, EncodedImage>> {
        if (support != VisionSupport.Supported) {
            return messages.map { message ->
                val path = message.userImagePath
                if (message.role != AgentRole.User || path == null) message else {
                    val note = if (support is VisionSupport.Unsupported) "This model cannot view images."
                        else "Rivet could not confirm that this model accepts images."
                    withoutUserImage(message, path, note)
                }
            } to trailingImages(messages, encode)
        }
        val last = messages.lastOrNull()
        val path = last?.takeIf { it.role == AgentRole.User }?.userImagePath
            ?: return messages to trailingImages(messages, encode)
        val image = encode(path)
        return if (image != null) messages to mapOf(userKey(path) to image)
        else (messages.dropLast(1) + withoutUserImage(last, path, "Shared image no longer available.")) to emptyMap()
    }

    private fun withoutUserImage(message: AgentMessage, path: String, note: String) = message.copy(
        text = "${message.text}\n\nShared image: inbox/${path.substringAfterLast('/')}. $note",
        userImagePath = null,
    )

    private fun userKey(path: String) = "\u0000shared-image:$path"

    fun userImage(message: AgentMessage, images: Map<String, EncodedImage>): EncodedImage? =
        message.userImagePath?.let { images[userKey(it)] }

    fun hasTrailingImages(messages: List<AgentMessage>): Boolean =
        messages.lastOrNull()?.let { it.role == AgentRole.User && it.userImagePath != null } == true ||
            trailingImageResults(messages).isNotEmpty()

    fun needsPreparation(messages: List<AgentMessage>) =
        hasTrailingImages(messages) || messages.any { it.userImagePath != null }
}
