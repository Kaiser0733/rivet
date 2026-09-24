package com.kaiser.rivet.agent

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class ContextPlan(
    val retained: List<AgentMessage>,
    val summaryInput: String,
    val originalBytes: Int,
    val retainedBytes: Int,
)

/** Plans a smaller active transcript without changing the durable event history. */
internal object AgentContext {
    private const val PRESSURE_BYTES = 384 * 1024
    private const val NORMAL_TAIL_BYTES = 160 * 1024
    private const val FORCED_TAIL_BYTES = 96 * 1024
    private const val MAX_REQUIRED_TAIL_BYTES = 256 * 1024
    private const val SUMMARY_INPUT_BYTES = 96 * 1024
    private val serializer = ListSerializer(AgentMessage.serializer())
    private val secretPattern = Regex("(?i)(sk-[a-z0-9_-]{12,}|ghp_[a-z0-9]{20,}|AIza[a-z0-9_-]{20,})")

    fun serializedBytes(messages: List<AgentMessage>): Int =
        Json.encodeToString(serializer, messages).toByteArray(Charsets.UTF_8).size

    fun plan(messages: List<AgentMessage>, force: Boolean = false): ContextPlan? {
        val originalBytes = serializedBytes(messages)
        if (!force && originalBytes < PRESSURE_BYTES) return null
        val groups = completeGroups(messages) ?: return null
        if (groups.size < 3) return null
        val groupBytes = groups.map { serializedBytes(it) - 2 }
        fun size(indices: Set<Int>): Int = 2 + indices.sumOf { groupBytes[it] } + maxOf(0, indices.size - 1)
        val latestUser = groups.indexOfLast { group -> group.singleOrNull()?.role == AgentRole.User }
        val required = mutableSetOf(groups.lastIndex)
        if (latestUser >= 0) required += latestUser
        val requiredBytes = size(required)
        if (requiredBytes > MAX_REQUIRED_TAIL_BYTES) return null
        val selected = required.toMutableSet()
        val target = if (force) minOf(FORCED_TAIL_BYTES, originalBytes / 2) else NORMAL_TAIL_BYTES
        for (index in groups.indices.reversed()) {
            if (index in selected) continue
            if (size(selected) + groupBytes[index] + 1 <= target) selected += index
        }
        val retained = selected.sorted().flatMap(groups::get)
        val removed = groups.indices.filterNot { it in selected }.flatMap(groups::get)
        if (removed.isEmpty()) return null
        val retainedBytes = serializedBytes(retained)
        if (retainedBytes >= originalBytes * 3 / 4) return null
        return ContextPlan(retained, summarizeInput(removed), originalBytes, retainedBytes)
    }

    private fun completeGroups(messages: List<AgentMessage>): List<List<AgentMessage>>? {
        val groups = mutableListOf<List<AgentMessage>>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message.role == AgentRole.Tool) return null
            if (message.role == AgentRole.Assistant && message.toolCalls.isNotEmpty()) {
                val result = messages.getOrNull(index + 1)?.takeIf { it.role == AgentRole.Tool } ?: return null
                if (message.toolCalls.map { it.id } != result.toolResults.map { it.callId }) return null
                groups += listOf(message, result)
                index += 2
            } else {
                groups += listOf(message)
                index++
            }
        }
        return groups
    }

    private fun summarizeInput(messages: List<AgentMessage>): String {
        val body = buildString {
            for (message in messages) {
                when (message.role) {
                    AgentRole.User -> append("USER: ").append(clipped(message.text, 1200)).append('\n')
                    AgentRole.Assistant -> {
                        if (message.text.isNotBlank()) append("ASSISTANT: ").append(clipped(message.text, 1200)).append('\n')
                        message.toolCalls.forEach { call ->
                            append("TOOL CALL: ").append(call.name)
                            toolTarget(call)?.let { append(' ').append(it) }
                            append('\n')
                        }
                    }
                    AgentRole.Tool -> message.toolResults.forEach { result ->
                        append("TOOL RESULT: ").append(result.name).append(' ')
                            .append(if (result.error) "error" else "ok").append(' ')
                            .append(clipped(result.summary, 180)).append('\n')
                    }
                }
            }
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        if (bytes.size <= SUMMARY_INPUT_BYTES) return body
        var head = clipped(body, 24_000)
        while (head.toByteArray(Charsets.UTF_8).size > 32 * 1024) {
            head = head.take(head.length / 2).dropLastWhile { it.isHighSurrogate() }
        }
        var tail = body.takeLast(56_000).dropWhile { it.isLowSurrogate() }
        while (tail.toByteArray(Charsets.UTF_8).size > 60 * 1024) {
            tail = tail.takeLast(tail.length / 2).dropWhile { it.isLowSurrogate() }
        }
        return head + "\n[Earlier projected events omitted; complete history remains stored.]\n" + tail
    }

    private fun toolTarget(call: AgentToolCall): String? {
        val fields = try { Json.parseToJsonElement(call.arguments).jsonObject }
            catch (_: Exception) { return null }
        val path = listOf("path", "cwd", "destination").firstNotNullOfOrNull { key ->
            (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        if (path != null) return clipped(path, 200)
        return null
    }

    private fun clipped(value: String, length: Int): String {
        val safe = redact(value)
        return safe.take(length).dropLastWhile { it.isHighSurrogate() } +
            if (safe.length > length) " [truncated]" else ""
    }

    fun redact(value: String): String = value.replace(secretPattern, "[redacted]")
}
