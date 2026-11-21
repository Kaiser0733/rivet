package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.PresentedFile

/** Keyed by the index of the user message that opened each turn, null for an initial assistant turn. */
internal fun presentedFilesForTurns(messages: List<AgentMessage>): Map<Int?, List<PresentedFile>> {
    val files = mutableMapOf<Int?, LinkedHashMap<String, PresentedFile>>()
    var turn: Int? = null
    messages.forEachIndexed { index, message ->
        if (message.internalContext != null) return@forEachIndexed
        if (message.role == AgentRole.User) turn = index
        if (message.role == AgentRole.Tool) message.toolResults.forEach { result ->
            if (result.name == "present" && !result.error && result.presentedFiles.isNotEmpty()) {
                val delivered = files.getOrPut(turn, ::LinkedHashMap)
                result.presentedFiles.forEach { delivered[it.path] = it }
            }
        }
    }
    return files.mapValues { (_, delivered) -> delivered.values.toList() }
}
