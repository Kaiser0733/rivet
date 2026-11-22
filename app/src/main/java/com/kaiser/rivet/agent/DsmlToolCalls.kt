package com.kaiser.rivet.agent

import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Some compatible endpoints emit DeepSeek tool calls as text instead of tool-call deltas. */
internal class DsmlToolCalls(private val emitText: (String) -> Unit) {
    private var pending = ""
    private var inCall = false

    fun delta(text: String) {
        pending += text
        while (pending.isNotEmpty()) {
            if (inCall) {
                val end = endTag.find(pending)
                if (end != null) {
                    pending = pending.substring(end.range.last + 1)
                    inCall = false
                    continue
                }
                pending = pending.substringAfterLast('<', "").let { if (it.length < 96) "<$it" else "" }
                return
            }
            val marker = startTag.find(pending)
            if (marker != null) {
                emitText(pending.substring(0, marker.range.first))
                pending = pending.substring(marker.range.last + 1)
                inCall = true
                continue
            }
            val possibleStart = pending.lastIndexOf('<')
            if (possibleStart >= 0 && isMarkerPrefix(pending.substring(possibleStart))) {
                emitText(pending.substring(0, possibleStart))
                pending = pending.substring(possibleStart)
            } else {
                emitText(pending)
                pending = ""
            }
            return
        }
    }

    fun finish(response: AgentResponse, tools: List<AgentToolDefinition>): AgentResponse {
        if (!inCall && pending.isNotEmpty() && !isMarkerPrefix(pending, strict = true)) emitText(pending)
        pending = ""
        val allowed = tools.mapTo(mutableSetOf()) { it.name }
        val calls = mutableListOf<AgentToolCall>()
        val text = envelope.replace(response.text) { match ->
            if (response.toolCalls.isEmpty()) {
                invocation.findAll(match.groupValues[1]).take(32 - calls.size).forEach { call ->
                    val name = call.groupValues[1].let {
                        if (it == "list_files" && it !in allowed && "list_directory" in allowed) "list_directory" else it
                    }
                    val parameters = call.groupValues[2]
                    if (name in allowed && parameters.length < 65_536) {
                        parseArguments(parameters)?.let {
                            calls += AgentToolCall("call_" + UUID.randomUUID().toString().replace("-", ""), name, it)
                        }
                    }
                }
            }
            ""
        }.let(::hideIncomplete)
        val next = if (response.toolCalls.isEmpty()) calls else response.toolCalls
        return response.copy(text = text, toolCalls = next)
    }

    companion object {
        private const val MARK = """[|｜]\s*DSML\s*[|｜]\s*"""
        private const val GROUP = """(?:function_calls|tool_calls|calls)"""
        private val spaces = Regex("\\s+")
        private val startTag = Regex("""<\s*${MARK}${GROUP}\s*>""", RegexOption.IGNORE_CASE)
        private val endTag = Regex("""<\s*/\s*${MARK}${GROUP}\s*>""", RegexOption.IGNORE_CASE)
        private val envelope = Regex("${startTag.pattern}(.*?)${endTag.pattern}",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val invocation = Regex("""<\s*${MARK}invoke\s+name\s*=\s*"([A-Za-z0-9_-]+)"\s*>(.*?)<\s*/\s*${MARK}invoke\s*>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val parameter = Regex("""<\s*${MARK}parameter\s+name\s*=\s*"([A-Za-z0-9_-]+)"\s+string\s*=\s*"(true|false)"\s*>(.*?)<\s*/\s*${MARK}parameter\s*>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val openMarkers = listOf("<|DSML|function_calls>", "<|DSML|tool_calls>", "<|DSML|calls>")

        private fun isMarkerPrefix(text: String, strict: Boolean = false): Boolean {
            if (text.length > 96) return false
            val candidate = text.replace(spaces, "").replace('｜', '|')
            return (!strict || candidate.length >= 3) &&
                openMarkers.any { it.startsWith(candidate, ignoreCase = true) }
        }

        internal fun hideIncomplete(text: String): String {
            val first = startTag.find(text)
            if (first != null) return text.substring(0, first.range.first)
            val start = text.lastIndexOf('<')
            return if (start >= 0 && isMarkerPrefix(text.substring(start), strict = true)) text.substring(0, start)
                else text
        }

        private fun parseArguments(body: String): String? {
            if (parameter.replace(body, "").isNotBlank()) return null
            return runCatching {
                buildJsonObject {
                    parameter.findAll(body).forEach { p ->
                        val (_, key, string, value) = p.groupValues
                        put(key, if (string.equals("true", true)) JsonPrimitive(value)
                            else Json.parseToJsonElement(value.trim()))
                    }
                }.toString()
            }.getOrNull()
        }
    }
}
