package com.kaiser.rivet.local

import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class ParsedLocalOutput(val text: String, val calls: List<AgentToolCall>)

/**
 * Small models do not all have a native tool-call parser. They get a compact
 * prompt contract, then the same JSON objects are parsed here. A GBNF grammar
 * can constrain a retry to exactly that shape.
 */
object LocalToolProtocol {
    val coreTools = linkedSetOf(
        "read_file", "write_file", "create_file", "apply_patch",
        "search_files", "list_directory", "run_command",
    )

    fun profile(tools: List<AgentToolDefinition>): List<AgentToolDefinition> =
        tools.filter { it.name in coreTools }

    fun systemSuffix(tools: List<AgentToolDefinition>): String {
        if (tools.isEmpty()) return ""
        return buildString {
            append("\n\n## Tools on this phone\n")
            append("Use a tool only when it is needed. To call one, reply with a JSON object ")
            append("inside <tool_call> tags, and nothing else on those lines:\n")
            append("<tool_call>{\"name\":\"read_file\",\"arguments\":{\"path\":\"src/Main.kt\"}}</tool_call>\n")
            append("Available tools:\n")
            for (tool in tools) {
                append("- ").append(tool.name).append(": ")
                append(tool.description.take(240).replace('\n', ' ')).append('\n')
                append("  arguments: ").append(tool.parameters).append('\n')
            }
            append("After a tool result, continue the task. If no tool is needed, answer normally.")
        }
    }

    fun parse(output: String, allowed: Set<String> = coreTools): ParsedLocalOutput {
        val calls = mutableListOf<AgentToolCall>()
        val ranges = mutableListOf<IntRange>()
        val tagRegex = Regex("<tool_call>\\s*(.*?)\\s*</tool_call>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (match in tagRegex.findAll(output)) {
            parseObject(match.groupValues[1], allowed)?.let { calls += it }
            ranges += match.range
        }
        if (calls.isEmpty()) {
            for ((start, end) in jsonObjectRanges(output)) {
                parseObject(output.substring(start, end + 1), allowed)?.let {
                    calls += it
                    ranges += start..end
                }
            }
        }
        if (calls.isEmpty()) return ParsedLocalOutput(output.trim(), emptyList())
        val clean = buildString {
            var cursor = 0
            for (range in ranges.sortedBy { it.first }) {
                if (range.first > cursor) append(output.substring(cursor, range.first))
                cursor = maxOf(cursor, range.last + 1)
            }
            if (cursor < output.length) append(output.substring(cursor))
        }.replace(Regex("<\\|?/?tool_call\\|?>", RegexOption.IGNORE_CASE), "")
            .trim()
        return ParsedLocalOutput(clean, calls)
    }

    fun looksLikeToolAttempt(text: String): Boolean =
        text.contains("<tool_call", ignoreCase = true) ||
            (text.contains("\"name\"") && text.contains("\"arguments\""))

    private fun parseObject(raw: String, allowed: Set<String>): AgentToolCall? = runCatching {
        val objectValue = Json.parseToJsonElement(raw.trim()).jsonObject
        val name = objectValue["name"]?.jsonPrimitive?.content ?: return@runCatching null
        if (name !in allowed) return@runCatching null
        val arguments = objectValue["arguments"] ?: objectValue["parameters"] ?: return@runCatching null
        val argumentsText = when (arguments) {
            is JsonObject, is JsonArray -> arguments.toString()
            is JsonPrimitive -> arguments.content
            else -> arguments.toString()
        }
        AgentToolCall(id = "local-${UUID.randomUUID()}", name = name, arguments = argumentsText)
    }.getOrNull()

    /** Balanced-brace scan that ignores braces inside JSON strings. */
    private fun jsonObjectRanges(text: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (index < text.length) {
            if (text[index] != '{') { index++; continue }
            var depth = 0
            var inString = false
            var escaped = false
            var end = -1
            for (i in index until text.length) {
                val c = text[i]
                if (inString) {
                    if (escaped) escaped = false
                    else if (c == '\\') escaped = true
                    else if (c == '"') inString = false
                    continue
                }
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { end = i; break }
                    }
                }
            }
            if (end < 0) break
            result += index to end
            index = end + 1
        }
        return result
    }

    /** GBNF used only on a retry after a malformed prompted tool call. */
    fun grammar(tools: List<AgentToolDefinition>): String {
        val names = tools.joinToString(" | ") { "\"${it.name.replace("\"", "\\\"")}\"" }
        return """
            root ::= ws "{" ws "\"name\"" ws ":" ws name ws "," ws "\"arguments\"" ws ":" ws value ws "}" ws
            name ::= $names
            value ::= object | array | string | number | "true" | "false" | "null"
            object ::= "{" ws (string ws ":" ws value ws ("," ws string ws ":" ws value ws)*)? "}"
            array ::= "[" ws (value ws ("," ws value ws)*)? "]"
            string ::= "\"" ([^"\\] | "\\" ["\\/bfnrt] | "\\u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])* "\""
            number ::= "-"? ([0-9] | [1-9][0-9]*) ("." [0-9]+)? ([eE] [+-]? [0-9]+)?
            ws ::= [ \t\n\r]*
        """.trimIndent()
    }
}
