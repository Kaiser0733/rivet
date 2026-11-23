package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class TurnDeliverable(val path: String, val description: String, val inlineImage: Boolean = false)

/** Keyed by the index of the user message that opened each turn, null for an initial assistant turn. */
internal fun presentedFilesForTurns(messages: List<AgentMessage>): Map<Int?, List<TurnDeliverable>> {
    val files = mutableMapOf<Int?, LinkedHashMap<String, TurnDeliverable>>()
    var turn: Int? = null
    messages.forEachIndexed { index, message ->
        if (message.internalContext != null) return@forEachIndexed
        if (message.role == AgentRole.User) turn = index
        if (message.role == AgentRole.Tool) message.toolResults.forEach { result ->
            if (result.error) return@forEach
            when (result.name) {
                "present" -> result.presentedFiles.forEach {
                    val delivered = files.getOrPut(turn, ::LinkedHashMap)
                    if (delivered[it.path]?.inlineImage != true) {
                        delivered[it.path] = TurnDeliverable(it.path, it.description)
                    }
                }
                "phone_shot" -> {
                    val path = runCatching {
                        Json.parseToJsonElement(result.content).jsonObject["path"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()?.takeIf(String::isNotBlank) ?: return@forEach
                    files.getOrPut(turn, ::LinkedHashMap)[path] =
                        TurnDeliverable(path, "Phone screenshot", inlineImage = true)
                }
            }
        }
    }
    return files.mapValues { (_, delivered) -> delivered.values.toList() }
}
