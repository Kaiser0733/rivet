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
) {
    suspend fun run(
        initial: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
        onText: (String) -> Unit = {},
        onMessage: suspend (AgentMessage) -> Unit = {},
    ): AgentRunResult {
        val messages = initial.toMutableList()
        val deniedMutations = mutableSetOf<String>()
        var modelIterations = 0
        var toolCalls = 0
        while (modelIterations < MAX_MODEL_ITERATIONS) {
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
                    messages += assistant
                    withContext(NonCancellable) { onMessage(assistant) }
                }
                throw e
            }
            modelIterations++
            val assistant = AgentMessage.assistant(
                text = response.text,
                toolCalls = response.toolCalls,
                transportState = response.transportState,
            )
            messages += assistant
            onMessage(assistant)
            if (response.toolCalls.isEmpty()) {
                return AgentRunResult(
                    messages = messages,
                    stopReason = AgentStopReason.Completed,
                    modelIterations = modelIterations,
                    toolCalls = toolCalls,
                )
            }
            if (toolCalls + response.toolCalls.size > MAX_TOOL_CALLS) {
                val rejected = AgentMessage.tools(response.toolCalls.map { call ->
                    AgentToolResult(
                        call.id,
                        call.name,
                        "{\"error\":\"tool_call_limit\"}",
                        error = true,
                        summary = "Stopped  tool call limit",
                    )
                })
                messages += rejected
                onMessage(rejected)
                return AgentRunResult(
                    messages = messages,
                    stopReason = AgentStopReason.ToolCallLimit,
                    modelIterations = modelIterations,
                    toolCalls = toolCalls,
                )
            }
            val results = mutableListOf<AgentToolResult>()
            var workspaceChanged = false
            for ((index, call) in response.toolCalls.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!workspaceIsCurrent()) {
                    response.toolCalls.drop(index).forEach { rejected ->
                        toolCalls++
                        results += AgentToolResult(
                            callId = rejected.id,
                            name = rejected.name,
                            content = "{\"error\":\"workspace_changed\"}",
                            error = true,
                            summary = "Stopped  workspace changed",
                        )
                    }
                    workspaceChanged = true
                    break
                }
                toolCalls++
                val prepared = try {
                    prepareTool(call)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    results += failed(call)
                    continue
                }
                val denialKey = "${call.name}\n${call.arguments}"
                val denied = prepared.approval != null &&
                    (denialKey in deniedMutations || !requestApproval(prepared.approval))
                if (denied) {
                    deniedMutations += denialKey
                    results += AgentToolResult(
                        callId = call.id,
                        name = call.name,
                        content = "{\"error\":\"denied\"}",
                        error = true,
                        summary = "Denied  ${call.name}",
                    )
                } else {
                    if (!workspaceIsCurrent()) {
                        response.toolCalls.drop(index).forEach { rejected ->
                            if (rejected !== call) toolCalls++
                            results += AgentToolResult(
                                callId = rejected.id,
                                name = rejected.name,
                                content = "{\"error\":\"workspace_changed\"}",
                                error = true,
                                summary = "Stopped  workspace changed",
                            )
                        }
                        workspaceChanged = true
                        break
                    }
                    results += try {
                        prepared.execute()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        failed(call)
                    }
                }
            }
            val toolMessage = AgentMessage.tools(results)
            messages += toolMessage
            // A SAF mutation can finish inside its non-cancellable commit section.
            // Persist its correlated result even if cancellation arrived at that boundary.
            withContext(NonCancellable) { onMessage(toolMessage) }
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
    }

    private fun failed(call: AgentToolCall) = AgentToolResult(
        callId = call.id,
        name = call.name,
        content = "{\"error\":\"tool_failed\"}",
        error = true,
        summary = "Failed  ${call.name}",
    )
}
