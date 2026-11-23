package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.ProjectImageInput
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Base64 travels only in the transient request body, never in AgentToolResult.content. */
data class EncodedImage(val mime: String, val base64: String)
internal data class ToolImage(val label: String, val image: EncodedImage)

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
        val projectImages = projectImageInputs(messages)
        if (support != VisionSupport.Supported) {
            return messages.map { message ->
                if (message.role == AgentRole.Tool && projectImages.isNotEmpty()) {
                    withProjectImageStatus(message, projectImages, "This model cannot view project images.")
                } else if (message.role != AgentRole.User || message.imagePaths.isEmpty()) message else {
                    val note = if (support is VisionSupport.Unsupported) "This model cannot view images."
                        else "Rivet could not confirm that this model accepts images."
                    withImageNotes(message, message.imagePaths, note, emptyList())
                }
            } to trailingImages(messages, encode)
        }
        val projectEncoded = projectImages.mapNotNull { (key, input) ->
            encode(input.cachePath)?.let { key to it }
        }.toMap()
        val unavailable = projectImages.filterKeys { it !in projectEncoded }
        val withProjectStatus = if (unavailable.isEmpty()) messages else messages.map { message ->
            if (message.role == AgentRole.Tool) {
                withProjectImageStatus(message, unavailable, "Rivet couldn't load these images for the model.")
            } else message
        }
        val last = messages.lastOrNull()?.takeIf { it.role == AgentRole.User && it.imagePaths.isNotEmpty() }
            ?: return withProjectStatus to (trailingImages(messages, encode) + projectEncoded)
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

    fun toolImages(
        result: AgentToolResult, images: Map<String, EncodedImage>, screenshotPrefix: String = "Screenshot from",
    ): List<ToolImage> = buildList {
        images[result.callId]?.let { add(ToolImage("[$screenshotPrefix ${result.name}]", it)) }
        result.projectImages.forEach { input ->
            images[projectKey(result, input)]?.let { add(ToolImage("[Project image: ${input.path}]", it)) }
        }
    }

    private fun projectKey(result: AgentToolResult, input: ProjectImageInput): String =
        "\u0000project-image:${result.callId}:${input.path}"

    internal fun projectImageInputs(messages: List<AgentMessage>): Map<String, ProjectImageInput> =
        messages.asReversed().asSequence().takeWhile { it.role != AgentRole.User }
            .filter { it.role == AgentRole.Tool }
            .flatMap { it.toolResults.asReversed().asSequence() }
            .flatMap { result -> result.projectImages.asReversed().asSequence().map { projectKey(result, it) to it } }
            .distinctBy { (_, input) -> input.path }
            .take(4)
            .toMap()

    private fun withProjectImageStatus(
        message: AgentMessage, unavailable: Map<String, ProjectImageInput>, note: String,
    ): AgentMessage = message.copy(toolResults = message.toolResults.map { result ->
        val missing = result.projectImages.filter { projectKey(result, it) in unavailable }
        if (missing.isEmpty()) result else {
            val original = parseJsonObject(result.content) ?: JsonObject(mapOf("output" to JsonPrimitive(result.content)))
            result.copy(content = JsonObject(original + ("vision_note" to JsonPrimitive(
                "$note Files: ${missing.joinToString { it.path }}",
            ))).toString())
        }
    })

    fun hasTrailingImages(messages: List<AgentMessage>): Boolean =
        messages.lastOrNull()?.let { it.role == AgentRole.User && it.imagePaths.isNotEmpty() } == true ||
            trailingImageResults(messages).isNotEmpty() || projectImageInputs(messages).isNotEmpty()

    fun needsPreparation(messages: List<AgentMessage>) =
        hasTrailingImages(messages) || messages.any { it.imagePaths.isNotEmpty() }
}
