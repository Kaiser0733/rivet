package com.kaiser.rivet.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class AgentStopReason {
    Completed,
    IterationLimit,
    ToolCallLimit,
    WorkspaceChanged,
    SessionLimit,
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
    private val workspaceIsCurrent: suspend () -> Boolean = { true },
    private val canPersistToolOutput: suspend (AgentMessage, AgentMessage) -> Boolean = { _, _ -> true },
) {
    suspend fun run(
        initial: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
        onText: (String) -> Unit = {},
        onMessage: suspend (AgentMessage) -> Unit = {},
    ): AgentRunResult {
        val messages = initial.toMutableList()
        suspend fun append(message: AgentMessage) {
            messages += message
            // Completed protocol events must remain durable when cancellation
            // arrives during their persistence callback.
            withContext(NonCancellable) { onMessage(message) }
        }
        val deniedMutations = mutableSetOf<String>()
        val outputBudget = ToolOutputBudget()
        var modelIterations = 0
        var toolCalls = 0
        while (modelIterations < MAX_MODEL_ITERATIONS) {
            currentCoroutineContext().ensureActive()
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
                val correlatedErrors = AgentMessage.tools(response.toolCalls.map { call ->
                    AgentToolResult(
                        callId = call.id,
                        name = call.name,
                        content = "{\"error\":\"output_limit\"}",
                        error = true,
                        summary = "Limited  ${call.name}".take(256),
                    )
                })
                if (!canPersistToolOutput(assistant, correlatedErrors)) {
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
            if (toolCalls + response.toolCalls.size > MAX_TOOL_CALLS) {
                val rejected = AgentMessage.tools(response.toolCalls.mapIndexed { index, call ->
                    outputBudget.accept(AgentToolResult(
                        call.id,
                        call.name,
                        "{\"error\":\"tool_call_limit\"}",
                        error = true,
                        summary = "Stopped  tool call limit",
                    ), response.toolCalls.lastIndex - index)
                })
                append(rejected)
                return AgentRunResult(
                    messages = messages,
                    stopReason = AgentStopReason.ToolCallLimit,
                    modelIterations = modelIterations,
                    toolCalls = toolCalls,
                )
            }
            val results = mutableListOf<AgentToolResult>()
            var workspaceChanged = false
            try {
                for ((index, call) in response.toolCalls.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (!workspaceIsCurrent()) {
                        response.toolCalls.drop(index).forEach { rejected ->
                            toolCalls++
                            results += outputBudget.accept(AgentToolResult(
                                callId = rejected.id,
                                name = rejected.name,
                                content = "{\"error\":\"workspace_changed\"}",
                                error = true,
                                summary = "Stopped  workspace changed",
                            ), MAX_TOOL_CALLS - toolCalls)
                        }
                        workspaceChanged = true
                        break
                    }
                    if (!outputBudget.canAcceptMaximumResult(MAX_TOOL_CALLS - toolCalls - 1)) {
                        toolCalls++
                        results += outputBudget.limit(call, MAX_TOOL_CALLS - toolCalls)
                        continue
                    }
                    toolCalls++
                    val prepared = try {
                        prepareTool(call)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        results += outputBudget.accept(failed(call), MAX_TOOL_CALLS - toolCalls)
                        continue
                    }
                    val denialKey = "${call.name}\n${call.arguments}"
                    val denied = prepared.approval != null &&
                        (denialKey in deniedMutations || !requestApproval(prepared.approval))
                    if (!workspaceIsCurrent()) {
                        response.toolCalls.drop(index).forEachIndexed { offset, rejected ->
                            if (offset > 0) toolCalls++
                            results += outputBudget.accept(AgentToolResult(
                                callId = rejected.id,
                                name = rejected.name,
                                content = "{\"error\":\"workspace_changed\"}",
                                error = true,
                                summary = "Stopped  workspace changed",
                            ), MAX_TOOL_CALLS - toolCalls)
                        }
                        workspaceChanged = true
                        break
                    }
                    if (denied) {
                        deniedMutations += denialKey
                        results += outputBudget.accept(AgentToolResult(
                            callId = call.id,
                            name = call.name,
                            content = "{\"error\":\"denied\"}",
                            error = true,
                            summary = "Denied  ${call.name}",
                        ), MAX_TOOL_CALLS - toolCalls)
                    } else {
                        val result = try {
                            prepared.execute()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failed(call)
                        }
                        results += outputBudget.accept(result, MAX_TOOL_CALLS - toolCalls)
                    }
                }
            } catch (e: CancellationException) {
                val remaining = response.toolCalls.drop(results.size)
                remaining.forEachIndexed { index, call ->
                    results += outputBudget.accept(AgentToolResult(
                        callId = call.id,
                        name = call.name,
                        content = "{\"error\":\"cancelled\"}",
                        error = true,
                        summary = "Stopped  ${call.name}",
                    ), remaining.lastIndex - index)
                }
                append(AgentMessage.tools(results))
                throw e
            }
            val toolMessage = AgentMessage.tools(results)
            // A SAF mutation can finish inside its non-cancellable commit section.
            // Persist its correlated result even if cancellation arrived at that boundary.
            append(toolMessage)
            if (workspaceChanged) {
                return AgentRunResult(
                    messages = messages,
                    stopReason = AgentStopReason.WorkspaceChanged,
                    modelIterations = modelIterations,
                    toolCalls = toolCalls,
                )
            }
        }
        return AgentRunResult(
            messages = messages,
            stopReason = AgentStopReason.IterationLimit,
            modelIterations = modelIterations,
            toolCalls = toolCalls,
        )
    }

    companion object {
        const val MAX_MODEL_ITERATIONS = 20
        const val MAX_TOOL_CALLS = 50
        // Result limits count UTF-8 bytes of the exact structured content
        // passed to providers, after tool JSON encoding.
        const val MAX_TOOL_RESULT_BYTES = 24 * 1024
        const val MAX_TOOL_OUTPUT_BYTES_PER_TURN = 64 * 1024
        // Tool content is JSON embedded in transcript JSON, so quotes and
        // backslashes may double. The remainder covers bounded summaries.
        const val MAX_ENCODED_TOOL_OUTPUT_RESERVE_BYTES =
            MAX_TOOL_OUTPUT_BYTES_PER_TURN * 2 + 32 * 1024
    }

    private fun failed(call: AgentToolCall) = AgentToolResult(
        callId = call.id,
        name = call.name,
        content = "{\"error\":\"tool_failed\"}",
        error = true,
        summary = "Failed  ${call.name}",
    )

    private class ToolOutputBudget {
        private var usedBytes = 0

        fun canAcceptMaximumResult(remainingResults: Int): Boolean =
            usedBytes + MAX_TOOL_RESULT_BYTES +
                remainingResults.coerceAtLeast(0) * CORRELATED_ERROR_RESERVE_BYTES <=
                MAX_TOOL_OUTPUT_BYTES_PER_TURN

        fun limit(call: AgentToolCall, remainingResults: Int): AgentToolResult = accept(
            AgentToolResult(call.id, call.name, OUTPUT_LIMIT_CONTENT, error = true),
            remainingResults,
        )

        fun accept(result: AgentToolResult, remainingResults: Int): AgentToolResult {
            val candidate = result.copy(summary = result.summary.take(MAX_RESULT_SUMMARY_CHARS))
            val candidateBytes = candidate.content.toByteArray(Charsets.UTF_8).size
            val reservedBytes = remainingResults.coerceAtLeast(0) * CORRELATED_ERROR_RESERVE_BYTES
            val accepted = if (
                candidateBytes <= MAX_TOOL_RESULT_BYTES &&
                usedBytes + candidateBytes + reservedBytes <= MAX_TOOL_OUTPUT_BYTES_PER_TURN
            ) candidate else outputLimit(result)
            usedBytes += accepted.content.toByteArray(Charsets.UTF_8).size
            check(usedBytes + reservedBytes <= MAX_TOOL_OUTPUT_BYTES_PER_TURN)
            return accepted
        }

        private fun outputLimit(result: AgentToolResult) = AgentToolResult(
            callId = result.callId,
            name = result.name,
            content = OUTPUT_LIMIT_CONTENT,
            error = true,
            summary = "Limited  ${result.name}".take(MAX_RESULT_SUMMARY_CHARS),
        )

        private companion object {
            const val OUTPUT_LIMIT_CONTENT = "{\"error\":\"output_limit\"}"
            const val CORRELATED_ERROR_RESERVE_BYTES = 64
            const val MAX_RESULT_SUMMARY_CHARS = 256
        }
    }
}
