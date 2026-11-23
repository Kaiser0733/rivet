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
                if (message.role != AgentRole.User || message.imagePaths.isEmpty()) message else {
                    val note = if (support is VisionSupport.Unsupported) "This model cannot view images."
                        else "Rivet could not confirm that this model accepts images."
                    withImageNotes(message, message.imagePaths, note, emptyList())
                }
            } to trailingImages(messages, encode)
        }
        val last = messages.lastOrNull()?.takeIf { it.role == AgentRole.User && it.imagePaths.isNotEmpty() }
            ?: return messages to trailingImages(messages, encode)
        val encoded = last.imagePaths.distinct().mapNotNull { path -> encode(path)?.let { path to it } }.toMap()
        val missing = last.imagePaths.filterNot { it in encoded }
        val prepared = if (missing.isEmpty()) messages else messages.dropLast(1) +
            withImageNotes(last, missing, "Shared image no longer available.", last.imagePaths.filter { it in encoded })
        return prepared to encoded.mapKeys { userKey(it.key) }
    }

    private fun withImageNotes(message: AgentMessage, paths: List<String>, note: String, available: List<String>) = message.copy(
        text = (listOf(message.text) + paths.map { "Shared image: inbox/${it.substringAfterLast('/')}. $note" })
            .filter(String::isNotBlank).joinToString("\n\n"),
        userImagePath = available.firstOrNull(),
        userImagePaths = available.drop(1),
    )

    private fun userKey(path: String) = "\u0000shared-image:$path"

    fun userImages(message: AgentMessage, images: Map<String, EncodedImage>): List<EncodedImage> =
        message.imagePaths.mapNotNull { images[userKey(it)] }

    fun hasTrailingImages(messages: List<AgentMessage>): Boolean =
        messages.lastOrNull()?.let { it.role == AgentRole.User && it.imagePaths.isNotEmpty() } == true ||
            trailingImageResults(messages).isNotEmpty()

    fun needsPreparation(messages: List<AgentMessage>) =
        hasTrailingImages(messages) || messages.any { it.imagePaths.isNotEmpty() }
}
