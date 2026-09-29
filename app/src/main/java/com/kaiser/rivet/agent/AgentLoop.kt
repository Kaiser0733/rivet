package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.ProviderError
import com.kaiser.rivet.provider.jsonNestingWithinLimit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

enum class AgentStopReason {
    Completed,
    RunawayGuard,
    WorkspaceChanged,
    SessionLimit,
    CheckpointUnavailable,
    ContextUnavailable,
    ContextTooSmall,
    NoProgress,
    RuntimeBlocked,
    CapabilityBlocked,
}

data class AgentRunResult(
    val messages: List<AgentMessage>,
    val stopReason: AgentStopReason,
    val modelIterations: Int,
    val toolCalls: Int,
    val failureCode: String? = null,
    val mutationsAttempted: Int = 0,
    val mutationsCompleted: Int = 0,
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
    private val mutationBlocker: suspend (AgentToolCall) -> String? = { null },
    private val beforeMutation: suspend (AgentToolCall) -> String? = { null },
    private val failureState: suspend () -> String = { "" },
    private val compactContext: suspend (List<AgentMessage>, Boolean) -> List<AgentMessage> = { messages, _ -> messages },
    private val contextFootprint: (List<AgentMessage>) -> Long = { AgentContext.serializedBytes(it).toLong() },
) {
    suspend fun run(
        initial: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
        onText: (String) -> Unit = {},
        onMessage: suspend (AgentMessage) -> Unit = {},
        autonomyMode: AutonomyMode = AutonomyMode.Ask,
        onToolLifecycle: (AgentToolLifecycle) -> Unit = {},
    ): AgentRunResult {
        val messages = initial.toMutableList()
        suspend fun append(message: AgentMessage) {
            // Completed protocol events must remain durable when cancellation
            // arrives during their persistence callback.
            withContext(NonCancellable) { onMessage(message) }
            messages += message
        }
        fun lifecycle(call: AgentToolCall, stage: AgentToolLifecycleStage) {
            try { onToolLifecycle(AgentToolLifecycle(AgentToolLifecycle.keyFor(call.id), stage)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Presentation telemetry never controls execution. */ }
        }
        val deniedMutations = mutableSetOf<String>()
        val createdPaths = mutableSetOf<String>()
        val createdDirectories = mutableSetOf<String>()
        val movedExistingPaths = mutableSetOf<String>()
        val deterministicFailures = linkedSetOf<Triple<String, String, String>>()
        val unchangedReads = linkedMapOf<Triple<String, String, String>, Int>()
        var modelIterations = 0
        var toolCalls = 0
        var mutationsAttempted = 0
        var mutationsCompleted = 0
        while (modelIterations < RUNAWAY_MODEL_ITERATIONS) {
            currentCoroutineContext().ensureActive()
            val active = try { compactContext(messages.toList(), false) }
                catch (e: CancellationException) { throw e
                } catch (e: ProviderError) { throw e
                } catch (_: ContextCapacityTooSmall) {
                    return AgentRunResult(messages, AgentStopReason.ContextTooSmall, modelIterations, toolCalls,
                        mutationsAttempted = mutationsAttempted, mutationsCompleted = mutationsCompleted)
                } catch (_: Exception) {
                    return AgentRunResult(messages, AgentStopReason.ContextUnavailable, modelIterations, toolCalls,
                        mutationsAttempted = mutationsAttempted, mutationsCompleted = mutationsCompleted)
                }
            if (active != messages) {
                messages.clear()
                messages.addAll(active)
            }
            val streamed = StringBuffer()
            suspend fun requestOnce(): AgentResponse = requestModel(messages.toList(), tools) { delta ->
                streamed.append(delta)
                onText(delta)
            }
            val response = try {
                try { requestOnce() }
                catch (overflow: ProviderError.ContextOverflow) {
                    if (streamed.isNotEmpty()) throw overflow
                    val before = contextFootprint(messages.toList())
                    val reduced = try { compactContext(messages.toList(), true) }
                        catch (e: CancellationException) { throw e
                        } catch (e: ProviderError) { throw e
                        } catch (_: ContextCapacityTooSmall) {
                            return AgentRunResult(messages, AgentStopReason.ContextTooSmall, modelIterations, toolCalls,
                                mutationsAttempted = mutationsAttempted, mutationsCompleted = mutationsCompleted)
                        } catch (_: Exception) { throw overflow }
                    if (reduced != messages) {
                        messages.clear()
                        messages.addAll(reduced)
                    }
                    if (contextFootprint(messages.toList()) >= before * 9 / 10) throw overflow
                    requestOnce()
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
            val callIds = response.toolCalls.map { it.id }
            if (callIds.any(String::isBlank) || callIds.toSet().size != callIds.size) {
                throw ProviderError.InvalidResponse("ambiguous tool call ids")
            }
            val operationKeys = response.toolCalls.map(::operationKey)
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
                        mutationsAttempted = mutationsAttempted,
                        mutationsCompleted = mutationsCompleted,
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
                    mutationsAttempted = mutationsAttempted,
                    mutationsCompleted = mutationsCompleted,
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
                    mutationsAttempted = mutationsAttempted,
                    mutationsCompleted = mutationsCompleted,
                )
            }
            val results = mutableListOf<AgentToolResult>()
            var stopReason: AgentStopReason? = null
            var failureCode: String? = null
            fun pending(code: String) = response.toolCalls.drop(results.size).map { stopped(it, code) }
            suspend fun fits(candidate: List<AgentToolResult>, reserve: Int = 0): Boolean =
                canPersistToolOutput(messages + AgentMessage.tools(candidate), reserve)
            try {
                for ((callIndex, call) in response.toolCalls.withIndex()) {
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
                        PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) { failed(call) }
                    }
                    lifecycle(call, AgentToolLifecycleStage.Queued)
                    val denialKey = operationKeys[callIndex]
                    val previouslyDenied = denialKey in deniedMutations
                    val previous = deterministicFailures.firstOrNull {
                        it.first == denialKey
                    }
                    if (previous != null &&
                        (previous.second == "denied" || previous.third == failureState())) {
                        lifecycle(call, AgentToolLifecycleStage.Blocked)
                        results += AgentToolResult(call.id, call.name,
                            AgentToolError.noProgress(previous.second), true, "Stopped  ${call.name}")
                        results += pending("no_progress")
                        stopReason = AgentStopReason.NoProgress
                        break
                    }
                    if (previous != null) deterministicFailures.removeAll { it.first == denialKey }
                    val decision = ApprovalPolicy.decide(autonomyMode, prepared)
                    if (decision is ApprovalDecision.Blocked) {
                        val rejected = stopped(call, decision.reason)
                        val remaining = response.toolCalls.drop(results.size + 1).map { stopped(it, "not_executed") }
                        if (!fits(results + rejected + remaining)) {
                            results += pending("session_limit")
                            stopReason = AgentStopReason.SessionLimit
                            break
                        }
                        lifecycle(call, AgentToolLifecycleStage.Blocked)
                        results += rejected
                        results += remaining
                        val runtimeStop = AgentToolError.runtimeStopCode(rejected)
                        stopReason = if (runtimeStop != null) AgentStopReason.RuntimeBlocked
                            else AgentStopReason.CapabilityBlocked
                        failureCode = runtimeStop ?: decision.reason
                        break
                    }
                    val blocked = if (prepared.effect.requiresRuntimeBlocker && !previouslyDenied) {
                        try { mutationBlocker(call) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { "runtime_unavailable" }
                    } else null
                    if (blocked != null) {
                        val rejected = stopped(call, blocked)
                        val remaining = response.toolCalls.drop(results.size + 1).map { stopped(it, "not_executed") }
                        if (!fits(results + rejected + remaining)) {
                            results += pending("session_limit")
                            stopReason = AgentStopReason.SessionLimit
                            break
                        }
                        lifecycle(call, AgentToolLifecycleStage.Blocked)
                        results += rejected
                        if (AgentToolError.runtimeStopCode(rejected) != null) {
                            results += response.toolCalls.drop(results.size).map { stopped(it, "not_executed") }
                            stopReason = AgentStopReason.RuntimeBlocked
                            failureCode = blocked
                            break
                        }
                        val deterministic = AgentToolError.deterministicCode(rejected)
                        if (deterministic != null) {
                            deterministicFailures += Triple(denialKey, deterministic, failureState())
                            if (deterministicFailures.size > 64) deterministicFailures.remove(deterministicFailures.first())
                        }
                        continue
                    }
                    // Reserve the bounded correlated event before any side effect, whether
                    // or not this autonomy mode asks a person to approve it.
                    if (prepared.effect != AgentToolEffect.ReadOnly && !previouslyDenied && !fits(
                            results + pending("workspace_changed"),
                            // Result JSON is escaped once more inside session JSON.
                            // 2048 also covers the bounded summary and fixed fields.
                            prepared.resultContentLimitBytes * 2 + 2048,
                        )) {
                        lifecycle(call, AgentToolLifecycleStage.Blocked)
                        results += pending("session_limit")
                        stopReason = AgentStopReason.SessionLimit
                        break
                    }
                    val approval = if (decision == ApprovalDecision.AskUser) prepared.approval?.let { request ->
                        val target = request.destructivePath
                        when {
                            target != null && target in createdDirectories -> request.copy(
                                title = "Delete folder?",
                                detail = "${request.detail}\nThis folder may contain files added outside Rivet.",
                                dangerous = true,
                            )
                            target != null &&
                                (target !in createdPaths || movedExistingPaths.any { it == target || it.startsWith("$target/") }) ->
                                describeDestructive(request)
                            else -> request
                        }
                    } else null
                    val denied = decision == ApprovalDecision.AskUser &&
                        (previouslyDenied || run {
                            lifecycle(call, AgentToolLifecycleStage.AwaitingApproval)
                            !requestApproval(requireNotNull(approval))
                        })
                    if (denied) {
                        deniedMutations += denialKey
                        lifecycle(call, AgentToolLifecycleStage.Denied)
                        results += stopped(call, "denied")
                        deterministicFailures += Triple(denialKey, "denied", "")
                        if (deterministicFailures.size > 64) deterministicFailures.remove(deterministicFailures.first())
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    if (!workspaceIsCurrent()) {
                        results += pending("workspace_changed")
                        stopReason = AgentStopReason.WorkspaceChanged
                        break
                    }
                    val mutationBlocker = if (prepared.effect.requiresCheckpoint) {
                        try { beforeMutation(call) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { "checkpoint_unavailable" }
                    } else null
                    if (mutationBlocker == "checkpoint_unavailable") {
                        lifecycle(call, AgentToolLifecycleStage.Blocked)
                        results += pending("checkpoint_unavailable")
                        stopReason = AgentStopReason.CheckpointUnavailable
                        break
                    }
                    val result = if (mutationBlocker != null) {
                        lifecycle(call, AgentToolLifecycleStage.Blocked)
                        stopped(call, mutationBlocker)
                    } else {
                        if (prepared.effect.changesWorkspace) mutationsAttempted++
                        lifecycle(call, AgentToolLifecycleStage.Started)
                        try {
                            prepared.execute()
                        } catch (e: CancellationException) {
                            lifecycle(call, AgentToolLifecycleStage.Cancelled)
                            if (prepared.effect != AgentToolEffect.ReadOnly) results += stopped(call, "interrupted")
                            throw e
                        } catch (_: Exception) {
                            failed(call)
                        }
                    }
                    if (mutationBlocker == null) lifecycle(call,
                        if (result.error) AgentToolLifecycleStage.Failed else AgentToolLifecycleStage.Completed)
                    if (prepared.effect.changesWorkspace && mutationBlocker == null && !result.error) {
                        mutationsCompleted++
                    }
                    val bounded = boundResult(result, prepared.resultContentLimitBytes)
                    val remaining = response.toolCalls.drop(results.size + 1).map { stopped(it, "workspace_changed") }
                    if (prepared.effect == AgentToolEffect.ReadOnly && !fits(results + bounded + remaining)) {
                        results += pending("session_limit")
                        stopReason = AgentStopReason.SessionLimit
                        break
                    }
                    results += bounded
                    val runtimeStop = AgentToolError.runtimeStopCode(bounded)
                    if (runtimeStop != null) {
                        results += response.toolCalls.drop(results.size).map { stopped(it, "not_executed") }
                        stopReason = AgentStopReason.RuntimeBlocked
                        failureCode = runtimeStop
                        break
                    }
                    val deterministic = AgentToolError.deterministicCode(bounded)
                    if (deterministic != null) {
                        deterministicFailures += Triple(denialKey, deterministic, failureState())
                        if (deterministicFailures.size > 64) deterministicFailures.remove(deterministicFailures.first())
                    }
                    if (!bounded.error) recordProvenance(
                        call, bounded, createdPaths, createdDirectories, movedExistingPaths,
                    )
                    if (!bounded.error && prepared.effect.changesWorkspace) {
                        deterministicFailures.clear()
                        unchangedReads.clear()
                    }
                    if (!bounded.error && response.toolCalls.size == 1 &&
                        call.name == "read_file" && hasFileFingerprint(bounded.content)) {
                        val hash = MessageDigest.getInstance("SHA-256")
                            .digest(bounded.content.toByteArray(Charsets.UTF_8))
                            .joinToString("") { "%02x".format(it.toInt() and 255) }
                        val key = Triple(denialKey, hash, "")
                        unchangedReads.keys.removeAll { it.first == denialKey && it.second != hash }
                        val count = (unchangedReads[key] ?: 0) + 1
                        unchangedReads[key] = count
                        if (unchangedReads.size > 64) unchangedReads.remove(unchangedReads.keys.first())
                        if (count >= 16) {
                            results += pending("no_progress")
                            stopReason = AgentStopReason.NoProgress
                            break
                        }
                    }
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
                    failureCode = failureCode,
                    mutationsAttempted = mutationsAttempted,
                    mutationsCompleted = mutationsCompleted,
                )
            }
        }
        return AgentRunResult(
            messages = messages,
            stopReason = AgentStopReason.RunawayGuard,
            modelIterations = modelIterations,
            toolCalls = toolCalls,
            mutationsAttempted = mutationsAttempted,
            mutationsCompleted = mutationsCompleted,
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

    private fun hasFileFingerprint(content: String): Boolean = try {
        Json.parseToJsonElement(content).jsonObject["sha256"]?.jsonPrimitive?.content?.isNotBlank() == true
    } catch (_: SerializationException) { false
    } catch (_: IllegalArgumentException) { false }

    private fun operationKey(call: AgentToolCall): String {
        if (!jsonNestingWithinLimit(call.arguments, 32)) {
            throw ProviderError.InvalidResponse("tool arguments too deeply nested")
        }
        val canonical = try {
            canonicalArguments(Json.parseToJsonElement(call.arguments))
        } catch (_: IllegalArgumentException) { call.arguments }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return "${call.name}\n$digest"
    }

    private fun canonicalArguments(value: JsonElement): String {
        return when (value) {
            is JsonObject -> value.entries.sortedBy { it.key }.joinToString(",", "{", "}") {
                JsonPrimitive(it.key).toString() + ":" + canonicalArguments(it.value)
            }
            is JsonArray -> value.joinToString(",", "[", "]") { canonicalArguments(it) }
            else -> value.toString()
        }
    }

    private fun stopped(call: AgentToolCall, code: String) = AgentToolResult(
        call.id, call.name, AgentToolError.content(code), error = true,
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
        createdDirectories: MutableSet<String>,
        movedExisting: MutableSet<String>,
    ) {
        if (call.name !in setOf("create_file", "create_directory", "download_file", "delete_path", "rename_path", "move_path")) return
        val value = try { Json.parseToJsonElement(result.content).jsonObject }
            catch (_: SerializationException) { return }
            catch (_: IllegalArgumentException) { return }
        fun field(name: String) = value[name]?.jsonPrimitive?.content
        val path = field("path") ?: return
        when (call.name) {
            "create_file" -> created += path
            "create_directory" -> {
                created += path
                createdDirectories += path
            }
            "download_file" -> created += path
            "delete_path" -> {
                created.removeAll { it == path || it.startsWith("$path/") }
                createdDirectories.removeAll { it == path || it.startsWith("$path/") }
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
                relocate(createdDirectories)
                relocate(movedExisting)
                if (!wasCreated) movedExisting += path
            }
        }
    }
}
