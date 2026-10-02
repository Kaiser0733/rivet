package com.kaiser.rivet.agent

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

data class ContextPlan(
    val retained: List<AgentMessage>,
    val summaryInput: String,
    val originalBytes: Int,
    val retainedBytes: Int,
    val removed: List<AgentMessage> = emptyList(),
)

/** Plans a smaller active transcript without changing the durable event history. */
internal object AgentContext {
    private const val SUMMARY_INPUT_BYTES = 96 * 1024
    private val serializer = ListSerializer(AgentMessage.serializer())
    private val secretPattern = Regex("(?i)(sk-[a-z0-9_-]{12,}|gh[pousr]_[a-z0-9]{20,}|AIza[a-z0-9_-]{20,}|AKIA[A-Z0-9]{16})")
    private val bearerPattern = Regex("(?i)\\bBearer\\s+[a-z0-9._~+/-]{12,}")
    private val assignedSecretPattern = Regex("(?i)\\b(?:api[_-]?key|access[_-]?token|password|secret)\\s*[:=]\\s*['\"]?[a-z0-9._~+/-]{12,}")
    private val privateKeyPattern = Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----",
        RegexOption.DOT_MATCHES_ALL)

    fun serializedBytes(messages: List<AgentMessage>): Int =
        Json.encodeToString(serializer, messages).toByteArray(Charsets.UTF_8).size

    fun pruneOldResults(messages: List<AgentMessage>, protectedTailBytes: Int): List<AgentMessage>? {
        require(protectedTailBytes >= 0)
        val groups = completeGroups(messages) ?: return null
        if (groups.size < 2) return null
        val sizes = groups.map { serializedBytes(it) - 2 }
        val protected = mutableSetOf(groups.lastIndex)
        val latestUser = groups.indexOfLast { it.singleOrNull()?.role == AgentRole.User }
        if (latestUser >= 0) protected += latestUser
        var tailBytes = sizes.last()
        for (index in groups.lastIndex - 1 downTo 0) {
            if (tailBytes + sizes[index] > protectedTailBytes) break
            protected += index
            tailBytes += sizes[index]
        }
        val projected = groups.flatMapIndexed { index, group ->
            if (index in protected || group.size != 2) group else {
                val tool = group[1]
                listOf(group[0], tool.copy(toolResults = tool.toolResults.map { result ->
                    if (result.error || result.content.toByteArray(Charsets.UTF_8).size < 2048 ||
                        result.name == "run_command" && !runCommandCanPrune(result)) result
                    else result.copy(content = prunedContent(result))
                }))
            }
        }
        return projected.takeIf { serializedBytes(messages) - serializedBytes(it) >= 4096 }
    }

    private fun prunedContent(result: AgentToolResult): String {
        val old = try { Json.parseToJsonElement(result.content).jsonObject }
            catch (_: IllegalArgumentException) { null }
        return buildJsonObject {
            put("output_pruned", true)
            put("original_bytes", result.content.toByteArray(Charsets.UTF_8).size)
            put("summary", clipped(result.summary, 180))
            for (key in listOf("path", "sha256", "size", "exit_code", "timed_out", "sync", "sync_path",
                    "stdout_truncated", "stderr_truncated", "limited", "files_scanned", "entries_visited",
                    "bytes_scanned", "eof", "next_offset", "truncated")) {
                val value = old?.get(key) as? JsonPrimitive ?: continue
                if (value.isString) put(key, clipped(value.content, 200)) else put(key, value)
            }
        }.toString()
    }

    fun plan(
        messages: List<AgentMessage>,
        targetBytes: Int,
        minimumSavingsBytes: Int = 4096,
    ): ContextPlan? {
        require(targetBytes > 0)
        require(minimumSavingsBytes >= 0)
        val originalBytes = serializedBytes(messages)
        if (originalBytes <= targetBytes) return null
        val groups = completeGroups(messages)?.filter { it.singleOrNull()?.role != AgentRole.Context } ?: return null
        if (groups.size < 2) return null
        val contextBytes = serializedBytes(ProjectContext.projection(messages, emptyList(), "")) - 2
        val sizes = groups.map { serializedBytes(it) - 2 }
        val latestUser = groups.indexOfLast { it.singleOrNull()?.role == AgentRole.User }
        var first = groups.lastIndex
        var tailBytes = 2 + contextBytes + sizes[first]
        while (first > 0 && tailBytes + sizes[first - 1] + 1 <= targetBytes) {
            first--
            tailBytes += sizes[first] + 1
        }
        fun selectedIndices(): Set<Int> = (first..groups.lastIndex).toSet() +
            if (latestUser >= 0) setOf(latestUser) else emptySet()
        var selected = selectedIndices()
        var retained = selected.sorted().flatMap(groups::get)
        while (serializedBytes(ProjectContext.projection(messages, retained, "")) > targetBytes && first < groups.lastIndex) {
            first++
            selected = selectedIndices()
            retained = selected.sorted().flatMap(groups::get)
        }
        val retainedBytes = serializedBytes(ProjectContext.projection(messages, retained, ""))
        if (retainedBytes > targetBytes || originalBytes - retainedBytes < minimumSavingsBytes) return null
        val removed = groups.indices.filterNot { it in selected }.flatMap(groups::get)
        if (removed.isEmpty()) return null
        return ContextPlan(retained, summarizeInput(removed, SUMMARY_INPUT_BYTES), originalBytes, retainedBytes, removed)
    }

    fun minimumProjection(messages: List<AgentMessage>): List<AgentMessage>? {
        val groups = completeGroups(messages)?.filter { it.singleOrNull()?.role != AgentRole.Context } ?: return null
        if (groups.isEmpty()) return ProjectContext.projection(messages, emptyList())
        val newest = groups.lastIndex
        val latestUser = groups.indexOfLast { it.singleOrNull()?.role == AgentRole.User }
        return ProjectContext.projection(messages,
            (setOfNotNull(latestUser.takeIf { it >= 0 }, newest).sorted()).flatMap(groups::get))
    }

    private fun completeGroups(messages: List<AgentMessage>): List<List<AgentMessage>>? {
        val groups = mutableListOf<List<AgentMessage>>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            if (message.role == AgentRole.Tool) return null
            if (message.role == AgentRole.Context && (message.toolCalls.isNotEmpty() || message.toolResults.isNotEmpty() ||
                    message.text.isNotEmpty() || message.transportState != null)) return null
            if (message.role == AgentRole.Assistant && message.toolCalls.isNotEmpty()) {
                val result = messages.getOrNull(index + 1)?.takeIf { it.role == AgentRole.Tool } ?: return null
                if (message.toolCalls.any { it.id.isBlank() } ||
                    message.toolCalls.map { it.id }.toSet().size != message.toolCalls.size) return null
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

    fun validGroups(messages: List<AgentMessage>): Boolean = completeGroups(messages) != null

    fun summarizeInput(messages: List<AgentMessage>, maxBytes: Int = SUMMARY_INPUT_BYTES): String {
        require(maxBytes > 0)
        val groups = completeGroups(messages)
            ?: throw IllegalArgumentException("summary_input_limit")
        if (groups.isEmpty()) return ""

        val selected = mutableListOf<String>()
        var firstSelected = groups.size
        for (index in groups.lastIndex downTo 0) {
            val marker = if (index > 0) omittedMarker(maxBytes) ?: break else ""
            var row: String? = null
            for (detailLimit in listOf(1200, 600, 240, 80, 24, 0)) {
                val candidateRow = summarizeGroup(groups[index], detailLimit)
                val candidate = marker + candidateRow + selected.joinToString("")
                if (candidate.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                    row = candidateRow
                    break
                }
            }
            if (row == null) break
            selected.add(0, row)
            firstSelected = index
        }

        val marker = if (firstSelected == 0) ""
            else omittedMarker(maxBytes) ?: throw IllegalArgumentException("summary_input_limit")
        val result = marker + selected.joinToString("")
        if (result.toByteArray(Charsets.UTF_8).size > maxBytes) {
            throw IllegalArgumentException("summary_input_limit")
        }
        return result
    }

    private fun summarizeGroup(group: List<AgentMessage>, detailLimit: Int): String = buildString {
        for (message in group) {
            when (message.role) {
                AgentRole.Context -> append("INTERNAL CONTEXT: ").append(clipped(message.internalContext?.scope.orEmpty(), 160)).append('\n')
                AgentRole.User -> append("USER: ").append(clipped(message.text, detailLimit)).append('\n')
                AgentRole.Assistant -> {
                    if (message.text.isNotBlank()) {
                        append("ASSISTANT: ").append(clipped(message.text, detailLimit)).append('\n')
                    } else if (message.toolCalls.isEmpty()) {
                        append("ASSISTANT: [empty response]\n")
                    }
                    message.toolCalls.forEach { call ->
                        append("TOOL CALL: ").append(clipped(call.name, 160))
                        toolTarget(call, detailLimit)?.let { append(' ').append(it) }
                        append('\n')
                    }
                }
                AgentRole.Tool -> message.toolResults.forEach { result ->
                    append("TOOL RESULT: ").append(clipped(result.name, 160)).append(' ')
                    if (result.name == "run_command") append(commandSummary(result))
                    else append(if (result.error) "error " else "ok ")
                        .append(clipped(result.summary, minOf(180, detailLimit)))
                    append('\n')
                }
            }
        }
    }

    private fun omittedMarker(maxBytes: Int): String? {
        val markers = listOf(
            "[Earlier removed events omitted; complete history remains stored.]\n",
            "[Earlier removed events omitted.]\n",
            "[older omitted]\n",
        )
        return markers.firstOrNull { it.toByteArray(Charsets.UTF_8).size <= maxBytes }
    }

    private fun commandSummary(result: AgentToolResult): String {
        val value = parseObject(result.content)
        val exitCode = (value?.get("exit_code") as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val timedOut = (value?.get("timed_out") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val sync = (value?.get("sync") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val stdoutTruncated = (value?.get("stdout_truncated") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val stderrTruncated = (value?.get("stderr_truncated") as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val healthy = !result.error && value != null && "error" !in value && "sync_path" !in value &&
            exitCode == 0 && timedOut == false && sync in setOf("ok", "no_changes") &&
            stdoutTruncated != null && stderrTruncated != null
        val failed = result.error || exitCode?.let { it != 0 } == true || timedOut == true
        return buildString {
            append(when { healthy -> "ok"; failed -> "failed"; else -> "unresolved" })
            if (value == null) append(" status=malformed")
            append(" exit_code=").append(exitCode ?: "unknown")
            append(" timed_out=").append(timedOut ?: "unknown")
            append(" sync=").append(sync?.let { clipped(it, 40) } ?: "unknown")
            append(" stdout_truncated=").append(stdoutTruncated ?: "unknown")
            append(" stderr_truncated=").append(stderrTruncated ?: "unknown")
            (value?.get("sync_path") as? JsonPrimitive)?.content?.let {
                append(" sync_path=").append(clipped(it, 120))
            }
        }
    }

    private fun runCommandCanPrune(result: AgentToolResult): Boolean {
        if (result.error) return false
        val value = parseObject(result.content) ?: return false
        val exitCode = (value["exit_code"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
        val timedOut = (value["timed_out"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val sync = (value["sync"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val stdoutTruncated = (value["stdout_truncated"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        val stderrTruncated = (value["stderr_truncated"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        return "error" !in value && "sync_path" !in value && exitCode == 0 && timedOut == false &&
            sync in setOf("ok", "no_changes") && stdoutTruncated != null && stderrTruncated != null
    }

    private fun parseObject(content: String) = try {
        Json.parseToJsonElement(content).jsonObject
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun toolTarget(call: AgentToolCall, maxChars: Int = 200): String? {
        val fields = try { Json.parseToJsonElement(call.arguments).jsonObject }
            catch (_: IllegalArgumentException) { return null }
        val path = listOf("path", "cwd", "destination").firstNotNullOfOrNull { key ->
            (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        if (path != null) return clipped(path, maxChars)
        return null
    }

    private fun clipped(value: String, length: Int): String {
        if (length <= 0) return ""
        val safe = redact(value)
        return safe.take(length).dropLastWhile { it.isHighSurrogate() } +
            if (safe.length > length) " [truncated]" else ""
    }

    fun redact(value: String): String = value.replace(privateKeyPattern, "[redacted private key]")
        .replace(bearerPattern, "Bearer [redacted]")
        .replace(assignedSecretPattern, "[redacted credential]")
        .replace(secretPattern, "[redacted]")
}
