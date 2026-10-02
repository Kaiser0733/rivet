package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ReasoningLevel

/** Auxiliary request input is human-visible text only, never the agent transcript. */
internal object ConversationTitle {
    fun request(model: String, firstUser: String, answer: String) = AgentRequest(
        model = model,
        messages = listOf(AgentMessage.user(prefix(firstUser, 2048)),
            AgentMessage.assistant(prefix(answer, 1024))),
        system = "Generate a concise title for this coding conversation. Return only the title. " +
            "Use 2 to 6 words. No quotes. No markdown. No trailing punctuation.",
        reasoning = ReasoningLevel.Default,
        tools = emptyList(),
    )

    fun sanitize(output: String): String? {
        val line = output.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (line.startsWith("```") && !line.endsWith("```")) return null
        val clean = line.replace(Regex("^(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)"), "")
            .trim().trim('"', '\'', '`', '“', '”', '‘', '’').trim()
            .replace(Regex("^(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)"), "")
            .trimEnd('.', '!', '?', ':', ';', ',').trim()
            .replace(Regex("\\s+"), " ")
        if (Regex("^[\"'`]?(?:title|name|arguments|function)[\"'`]?\\s*:", RegexOption.IGNORE_CASE)
                .containsMatchIn(clean)) return null
        if (clean.isBlank() || clean.any { it.isISOControl() || it in "{}[]<>" } ||
            Regex("(?i)^(?:tool[_ ]?(?:call|result)|function[_ ]?call|assistant\\s*:|system\\s*:|json\\s*:)")
                .containsMatchIn(clean)) return null
        return prefix(clean, 80, 60).trim().takeIf { it.isNotBlank() }
    }

    private fun prefix(text: String, bytes: Int, characters: Int = Int.MAX_VALUE): String = buildString {
        var index = 0
        var used = 0
        var count = 0
        while (index < text.length && count < characters) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            if (codePoint in 0xD800..0xDFFF) { index += width; continue }
            val chunk = String(Character.toChars(codePoint))
            val size = chunk.toByteArray(Charsets.UTF_8).size
            if (used + size > bytes) break
            append(chunk)
            used += size
            count++
            index += width
        }
    }
}
