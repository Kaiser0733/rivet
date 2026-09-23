package com.kaiser.rivet.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class AgentStopReason {
    Completed,
    RunawayGuard,
    WorkspaceChanged,
    SessionLimit,
    CheckpointUnavailable,
    ContextUnavailable,
}

data class AgentRunResult(
    val messages: List<AgentMessage>,
    val stopReason: AgentStopReason,
    val modelIterations: Int,
    val toolCalls: Int,
)

class AgentLoop(
    private val requestModel: suspend (
        messages: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
        onText: (String) -> Unit,
    ) -> AgentResponse,
    private val prepareTool: suspend (AgentToolCall) -> PreparedAgentTool,
    private val requestApproval: suspend (AgentApprovalRequest) -> Boolean,
    private val describeDestructive: suspend (AgentApprovalRequest) -> AgentApprovalRequest = { it },
    private val workspaceIsCurrent: suspend () -> Boolean = { true },
    private val canPersistToolOutput: suspend (List<AgentMessage>, Int) -> Boolean = { _, _ -> true },
    private val beforeMutation: suspend (AgentToolCall) -> Boolean = { true },
    private val compactContext: suspend (List<AgentMessage>, Boolean) -> List<AgentMessage> = { messages, _ -> messages },
) {
    suspend fun run(
        initial: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
        onText: (String) -> Unit = {},
        onMessage: suspend (AgentMessage) -> Unit = {},
    ): AgentRunResult {
        val messages = initial.toMutableList()
        suspend fun append(message: AgentMessage) {
            // Completed protocol events must remain durable when cancellation
            // arrives during their persistence callback.
            withContext(NonCancellable) { onMessage(message) }
            messages += message
        }
        val deniedMutations = mutableSetOf<String>()
        val createdPaths = mutableSetOf<String>()
        val movedExistingPaths = mutableSetOf<String>()
        var modelIterations = 0
        var toolCalls = 0
        while (modelIterations < RUNAWAY_MODEL_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            val active = try { compactContext(messages.toList(), false) }
                catch (e: CancellationException) { throw e
                } catch (_: Exception) {
                    return AgentRunResult(messages, AgentStopReason.ContextUnavailable, modelIterations, toolCalls)
                }
            if (active != messages) {
                messages.clear()
                messages.addAll(active)
            }
            val streamed = StringBuffer()
            val response = try {
                requestModel(messages.toList(), tools) { delta ->
                    streamed.append(delta)
                    onText(delta)
                }
            } catch (e: CancellationException) {
                val partial = streamed.toString()
                if (partial.isNotBlank()) {
                    val assistant = AgentMessage.assistant(partial)
                    append(assistant)
                }
                throw e
            }
            modelIterations++
            val assistant = AgentMessage.assistant(
                text = response.text,
                toolCalls = response.toolCalls,
                transportState = response.transportState,
            )
            if (response.toolCalls.isNotEmpty()) {
                val correlatedErrors = AgentMessage.tools(response.toolCalls.map { stopped(it, "workspace_changed") })
                if (!canPersistToolOutput(messages + assistant + correlatedErrors, 0)) {
                    return AgentRunResult(
                        messages = messages,
                        stopReason = AgentStopReason.SessionLimit,
                        modelIterations = modelIterations,
                        toolCalls = toolCalls,
                    )
                }
            }
            append(assistant)
            if (response.toolCalls.isEmpty()) {
                return AgentRunResult(
                    messages = messages,
                    stopReason = AgentStopReason.Completed,
                    modelIterations = modelIterations,
                    toolCalls = toolCalls,
                )
            }
            if (toolCalls + response.toolCalls.size > RUNAWAY_TOOL_CALLS) {
                val rejected = AgentMessage.tools(response.toolCalls.map { stopped(it, "runaway_guard") })
                append(rejected)
                return AgentRunResult(
                    messages = messages,
                    stopReason = AgentStopReason.RunawayGuard,
                    modelIterations = modelIterations,
                    toolCalls = toolCalls,
                )
            }
            val results = mutableListOf<AgentToolResult>()
            var stopReason: AgentStopReason? = null
            fun pending(code: String) = response.toolCalls.drop(results.size).map { stopped(it, code) }
            suspend fun fits(candidate: List<AgentToolResult>, reserve: Int = 0): Boolean =
                canPersistToolOutput(messages + AgentMessage.tools(candidate), reserve)
            try {
                for (call in response.toolCalls) {
                    currentCoroutineContext().ensureActive()
                    if (!workspaceIsCurrent()) {
                        results += pending("workspace_changed")
                        stopReason = AgentStopReason.WorkspaceChanged
                        break
                    }
                    toolCalls++
                    val prepared = try {
                        prepareTool(call)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        PreparedAgentTool(call, null) { failed(call) }
                    }
                    val denialKey = "${call.name}\n${call.arguments}"
                    val previouslyDenied = denialKey in deniedMutations
                    // Only mutations need prospective headroom. Include completed results
                    // and a correlated error for every unstarted call in this batch.
                    if (prepared.approval != null && !previouslyDenied && !fits(
                            results + pending("workspace_changed"),
                            // Result JSON is escaped once more inside session JSON.
                            // 2048 also covers the bounded summary and fixed fields.
                            prepared.resultContentLimitBytes * 2 + 2048,
                        )) {
                        results += pending("session_limit")
                        stopReason = AgentStopReason.SessionLimit
                        break
                    }
                    val approval = prepared.approval?.let { request ->
                        val target = request.destructivePath
                        if (target != null &&
                            (target !in createdPaths || movedExistingPaths.any { it == target || it.startsWith("$target/") })) {
                            describeDestructive(request)
                        } else request
                    }
                    val denied = approval != null &&
                        (previouslyDenied || !requestApproval(approval))
                    currentCoroutineContext().ensureActive()
                    if (!workspaceIsCurrent()) {
                        results += pending("workspace_changed")
                        stopReason = AgentStopReason.WorkspaceChanged
                        break
                    }
                    if (!denied && prepared.approval != null) {
                        val protected = try { beforeMutation(call) }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { false }
                        if (!protected) {
                            results += pending("checkpoint_unavailable")
                            stopReason = AgentStopReason.CheckpointUnavailable
                            break
                        }
                    }
                    val result = if (denied) {
                        deniedMutations += denialKey
                        stopped(call, "denied")
                    } else {
                        try {
                            prepared.execute()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failed(call)
                        }
                    }
                    val bounded = boundResult(result, prepared.resultContentLimitBytes)
                    val remaining = response.toolCalls.drop(results.size + 1).map { stopped(it, "workspace_changed") }
                    if (prepared.approval == null && !fits(results + bounded + remaining)) {
                        results += pending("session_limit")
                        stopReason = AgentStopReason.SessionLimit
                        break
                    }
                    results += bounded
                    if (!denied && !bounded.error) recordProvenance(call, bounded, createdPaths, movedExistingPaths)
                }
            } catch (e: CancellationException) {
                results += pending("cancelled")
                append(AgentMessage.tools(results))
                throw e
            }
            val toolMessage = AgentMessage.tools(results)
            // A SAF mutation can finish inside its non-cancellable commit section.
            // Persist its correlated result even if cancellation arrived at that boundary.
            append(toolMessage)
            if (stopReason != null) {
                return AgentRunResult(
                    messages = messages,
                    stopReason = stopReason,
                    modelIterations = modelIterations,
                    toolCalls = toolCalls,
                )
            }
        }
        return AgentRunResult(
            messages = messages,
            stopReason = AgentStopReason.RunawayGuard,
            modelIterations = modelIterations,
            toolCalls = toolCalls,
        )
    }

    companion object {
        // Emergency ceilings for broken model loops, not normal workflow quotas.
        const val RUNAWAY_MODEL_ITERATIONS = 200
        const val RUNAWAY_TOOL_CALLS = 1000
        // UTF-8 bytes of structured result content after tool JSON encoding.
        const val MAX_TOOL_RESULT_BYTES = 24 * 1024
        const val OUTPUT_LIMIT_CONTENT =
            "{\"error\":\"output_limit\",\"scope\":\"result\",\"limit_bytes\":24576}"
    }

    private fun stopped(call: AgentToolCall, code: String) = AgentToolResult(
        call.id, call.name, "{\"error\":\"$code\"}", error = true,
        summary = when (code) {
            "denied" -> "Denied  ${call.name}"
            "tool_failed" -> "Failed  ${call.name}"
            else -> "Stopped  ${call.name}"
        }.take(256).dropLastWhile { it.isHighSurrogate() },
    )

    private fun failed(call: AgentToolCall) = stopped(call, "tool_failed")

    private fun boundResult(result: AgentToolResult, contentLimit: Int): AgentToolResult {
        val limit = minOf(contentLimit, MAX_TOOL_RESULT_BYTES)
        if (result.content.toByteArray(Charsets.UTF_8).size > limit) {
            return result.copy(
                content = "{\"error\":\"output_limit\",\"scope\":\"result\",\"limit_bytes\":$limit}",
                error = true,
                summary = "Limited",
            )
        }
        return result.copy(summary = result.summary.take(256).dropLastWhile { it.isHighSurrogate() })
    }

    private fun recordProvenance(
        call: AgentToolCall,
        result: AgentToolResult,
        created: MutableSet<String>,
        movedExisting: MutableSet<String>,
    ) {
        if (call.name !in setOf("create_file", "create_directory", "delete_path", "rename_path", "move_path")) return
        val value = try { Json.parseToJsonElement(result.content).jsonObject }
            catch (_: SerializationException) { return }
            catch (_: IllegalArgumentException) { return }
        fun field(name: String) = value[name]?.jsonPrimitive?.content
        val path = field("path") ?: return
        when (call.name) {
            "create_file", "create_directory" -> created += path
            "delete_path" -> {
                created.removeAll { it == path || it.startsWith("$path/") }
                movedExisting.removeAll { it == path || it.startsWith("$path/") }
            }
            "rename_path", "move_path" -> {
                val source = field("source_path") ?: return
                val wasCreated = source in created
                fun relocate(paths: MutableSet<String>) {
                    val affected = paths.filter { it == source || it.startsWith("$source/") }
                    paths.removeAll(affected.toSet())
                    paths.addAll(affected.map { path + it.removePrefix(source) })
                }
                relocate(created)
                relocate(movedExisting)
                if (!wasCreated) movedExisting += path
            }
        }
    }
}
