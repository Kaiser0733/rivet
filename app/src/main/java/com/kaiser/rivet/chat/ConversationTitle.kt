package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ReasoningLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Auxiliary request input is human-visible text only, never the agent transcript. */
internal object ConversationTitle {
    private val failureReport = Regex(
        """(?i)^(?:error|fatal|failure)\s*[:—-]|^(?:i\s+)?(?:could not|couldn['’]t|was unable to)\b|""" +
            """\b(?:failed|timed out|was denied|was cancelled)(?=\s*(?:[.!:]|$)|\s+(?:to|with|because)\b)""",
    )

    fun completedAnswer(messages: List<AgentMessage>): String? {
        val answer = messages.lastOrNull()?.takeIf {
            it.role == AgentRole.Assistant && it.toolCalls.isEmpty() && it.text.isNotBlank()
        }?.text ?: return null
        if (answer.lineSequence().any { failureReport.containsMatchIn(it.trimStart(' ', '\t', '#', '>', '*')) }) {
            return null
        }
        val latestResults = messages.asReversed().asSequence().takeWhile { it.role != AgentRole.User }
            .firstOrNull { it.role == AgentRole.Tool }?.toolResults.orEmpty()
        return answer.takeUnless { latestResults.isNotEmpty() && latestResults.all(::failed) }
    }

    private fun failed(result: AgentToolResult): Boolean {
        if (result.error) return true
        if (result.name != "termux_command" && result.name != "run_command") return false
        val code = runCatching {
            Json.parseToJsonElement(result.content).jsonObject["exit_code"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
        return code != null && code != 0
    }

    fun request(model: String, firstUser: String, answer: String) = AgentRequest(
        model = model,
        messages = listOf(AgentMessage.user("First user message:\n" + prefix(firstUser, 2048) +
            "\n\nCompleted assistant answer:\n" + prefix(answer, 1024))),
        system = "Generate a concise title for this coding conversation. Return only the title. " +
            "Use the user's goal or topic. Use 2 to 6 words. No quotes. No markdown. No trailing punctuation.",
        reasoning = ReasoningLevel.Default,
        tools = emptyList(),
    )

    fun sanitize(output: String): String? {
        val withoutThinking = output.replace(Regex("(?is)<think>.*?</think>"), "")
        val line = withoutThinking.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return null
        if (line.startsWith("```") && !line.endsWith("```")) return null
        val clean = line.replace(Regex("^(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)"), "")
            .trim().trim('"', '\'', '`', '“', '”', '‘', '’', '*', '_').trim()
            .replace(Regex("^(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+)"), "")
            .replace(Regex("^(?i)title\\s*:\\s*"), "")
            .trim().trim('"', '\'', '`', '“', '”', '‘', '’', '*', '_').trim()
            .trimEnd('.', '!', '?', ':', ';', ',').trim()
            .replace(Regex("\\s+"), " ")
        // A JSON-style key ("title": "Bad") is structured output, not a title. By this
        // point the surrounding quote may already be trimmed, so both quotes are optional.
        if (Regex("^[\"'`]?(?:title|name|arguments|function)[\"'`]?\\s*:", RegexOption.IGNORE_CASE)
                .containsMatchIn(clean)) return null
        if (clean.isBlank() || failureReport.containsMatchIn(clean) ||
            clean.any { it.isISOControl() || it in "{}[]<>" } ||
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
