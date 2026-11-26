package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.ProviderError
import com.kaiser.rivet.provider.jsonNestingWithinLimit
import com.kaiser.rivet.provider.requestRetryDelay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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

/** Visible in the transcript and in later model turns, so a cut reply cannot pass for a finished one. */
internal const val INCOMPLETE_REPLY_SUFFIX = "\n\n[Reply incomplete — the stream ended before it finished.]"

private fun markedIncomplete(text: String): String =
    if (text.endsWith(INCOMPLETE_REPLY_SUFFIX)) text else text + INCOMPLETE_REPLY_SUFFIX

enum class AgentStopReason {
    Completed,
    EmptyResponse,
    WorkspaceChanged,
    SessionLimit,
    CheckpointUnavailable,
    ContextUnavailable,
    ContextTooSmall,
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
    private val compactContext: suspend (List<AgentMessage>, Boolean) -> List<AgentMessage> = { messages, _ -> messages },
    private val contextFootprint: (List<AgentMessage>) -> Long = { AgentContext.serializedBytes(it).toLong() },
    private val contextUpdates: suspend (List<AgentMessage>) -> List<AgentMessage> = { emptyList() },
    private val approvedSkillScripts: Set<String> = emptySet(),
    private val secretValues: List<String> = emptyList(),
    private val recordSkillScriptApproval: suspend (String) -> Unit = {},
    private val afterMutation: suspend (AgentToolCall, AgentToolResult?) -> Unit = { _, _ -> },
    private val onContextLimitObserved: suspend (ProviderError.ContextOverflow, Long) -> Unit = { _, _ -> },
    private val onOutputLimitObserved: suspend (ProviderError.OutputLimitExceeded) -> Unit = { },
    private val onRequestRetry: () -> Unit = {},
    private val beforeCompletion: suspend (List<AgentMessage>) -> List<AgentMessage> = { emptyList() },
    private val writeMemoryBatch: (suspend (AgentToolCall, List<String>) -> AgentToolResult)? = null,
    private val resumeReceipts: Map<String, RunToolReceipt> = emptyMap(),
    private val journalTool: suspend (String, RunToolReceipt) -> Unit = { _, _ -> },
) {
    private data class MemoryProposal(val tool: PreparedAgentTool, val fact: String, val asksUser: Boolean)

    suspend fun run(
        initial: List<AgentMessage>,
        tools: List<AgentToolDefinition>,
        onText: (String) -> Unit = {},
        onMessage: suspend (AgentMessage) -> Unit = {},
        // Read at every tool decision. Capturing the mode when the loop starts is the
        // bug this parameter exists to prevent: a Settings flip must reach this turn.
        currentAutonomyMode: suspend () -> AutonomyMode = { AutonomyMode.Ask },
        onToolLifecycle: (AgentToolLifecycle) -> Unit = {},
    ): AgentRunResult {
        val messages = initial.toMutableList()
        val priorAttempts = previousToolReceipts(initial) + resumeReceipts
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
        val scriptApprovals = approvedSkillScripts.toMutableSet()
        val createdPaths = mutableSetOf<String>()
        val createdDirectories = mutableSetOf<String>()
        val movedExistingPaths = mutableSetOf<String>()
        var lastCallKey: String? = null
        var consecutiveCalls = 0
        val repeatReminders = mutableListOf<AgentMessage>()
        fun observeRepeat(call: AgentToolCall) {
            val key = operationKey(call)
            if (key == lastCallKey) {
                consecutiveCalls++
            } else {
                lastCallKey = key
                consecutiveCalls = 1
            }
            if (consecutiveCalls in REPEAT_REMINDER_THRESHOLDS) {
                repeatReminders += repeatReminder(call, consecutiveCalls).event()
            }
        }
        var modelIterations = 0
        var toolCalls = 0
        var mutationsAttempted = 0
        var mutationsCompleted = 0
        val memoryProposals = mutableListOf<MemoryProposal>()
        suspend fun finishMemoryProposals(): AgentStopReason {
            val write = writeMemoryBatch ?: return AgentStopReason.Completed
            val first = memoryProposals.firstOrNull()?.tool ?: return AgentStopReason.Completed
            val facts = memoryProposals.map { it.fact }.distinct()
            val key = operationKey(first.call.copy(arguments = JsonArray(facts.map(::JsonPrimitive)).toString()))
            fun stage(value: AgentToolLifecycleStage) {
                memoryProposals.forEach { lifecycle(it.tool.call, value) }
            }
            if (key in priorAttempts) {
                stage(AgentToolLifecycleStage.Blocked)
                return AgentStopReason.Completed
            }
            try {
                if (memoryProposals.any { it.asksUser }) {
                    stage(AgentToolLifecycleStage.AwaitingApproval)
                    val approval = requireNotNull(first.approval).copy(
                        detail = requireNotNull(first.approval).detail +
                            facts.joinToString("\n") { "\n• $it" },
                    )
                    val approved = try { requestApproval(approval) }
                    catch (e: ApprovalUnavailable) {
                        stage(AgentToolLifecycleStage.Blocked)
                        append(InternalContext.commandReply("Project memory",
                            AgentToolError.action(e.code).orEmpty(), workspace = null))
                        return AgentStopReason.Completed
                    }
                    if (!approved) {
                        stage(AgentToolLifecycleStage.Denied)
                        append(MemoryProposalDeclined.event())
                        return AgentStopReason.Completed
                    }
                }
                currentCoroutineContext().ensureActive()
                if (!workspaceIsCurrent()) {
                    stage(AgentToolLifecycleStage.Blocked)
                    return AgentStopReason.WorkspaceChanged
                }
                // Undo is best-effort. A checkpoint that cannot be prepared leaves
                // the write without an undo row; it does not turn a durable memory
                // fact into a failed turn.
                val acquiredCheckpoint = beforeMutation(first.call) == null
                mutationsAttempted++
                var result: AgentToolResult? = null
                try {
                    try { journalTool(key, RunToolReceipt(first.call.name)) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        stage(AgentToolLifecycleStage.Blocked)
                        return AgentStopReason.CheckpointUnavailable
                    }
                    stage(AgentToolLifecycleStage.Started)
                    result = write(first.call, facts).also {
                        if (!it.error) mutationsCompleted++
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    result = failed(first.call)
                } finally {
                    withContext(NonCancellable) {
                        try {
                            result?.let { journalTool(key, RunToolReceipt(first.call.name, it.error)) }
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { /* The start marker is still durable. */ }
                        finally { if (acquiredCheckpoint) afterMutation(first.call, result) }
                    }
                }
                stage(if (result?.error == false) AgentToolLifecycleStage.Completed else AgentToolLifecycleStage.Failed)
                return AgentStopReason.Completed
            } catch (e: CancellationException) {
                stage(AgentToolLifecycleStage.Cancelled)
                throw e
            }
        }

        suspend fun appendContextEvents(events: List<AgentMessage>): AgentRunResult? {
            if (events.isEmpty()) return null
            require(events.all {
                it.role == AgentRole.Context && it.internalContext?.valid() == true &&
                    it.text.isEmpty() && it.toolCalls.isEmpty() && it.toolResults.isEmpty() && it.transportState == null
            })
            if (!AgentContext.validGroups(messages)) return AgentRunResult(
                messages, AgentStopReason.ContextUnavailable, modelIterations, toolCalls,
                mutationsAttempted = mutationsAttempted, mutationsCompleted = mutationsCompleted,
            )
            if (!canPersistToolOutput(messages + events, 0)) return AgentRunResult(
                messages, AgentStopReason.SessionLimit, modelIterations, toolCalls,
                mutationsAttempted = mutationsAttempted, mutationsCompleted = mutationsCompleted,
            )
            for (event in events) append(event)
            return null
        }
        // dsh's agent loop is deliberately unbounded: a turn ends on completion,
        // a workspace change, a real context/storage limit, an empty final answer,
        // cancellation, or a thrown provider error. It never counts iterations.
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!workspaceIsCurrent()) return AgentRunResult(messages, AgentStopReason.WorkspaceChanged,
                modelIterations, toolCalls, mutationsAttempted = mutationsAttempted, mutationsCompleted = mutationsCompleted)
            appendContextEvents(contextUpdates(messages.toList()))?.let { return it }
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
            suspend fun requestOnce(): AgentResponse {
                val dsml = DsmlToolCalls { text ->
                    if (text.isNotEmpty()) {
                        streamed.append(text)
                        onText(text)
                    }
                }
                return dsml.finish(requestModel(messages.toList(), tools, dsml::delta), tools)
            }
            suspend fun requestWithRetry(): AgentResponse {
                var retries = 0
                while (true) {
                    try {
                        return requestOnce().also {
                            if (it.toolCalls.isEmpty() && it.text.isBlank()) throw ProviderError.EmptyResponse
                        }
                    } catch (error: ProviderError) {
                        val backoff = error.requestRetryDelay(retries++) ?: throw error
                        streamed.setLength(0)
                        onRequestRetry()
                        delay(backoff)
                        if (!workspaceIsCurrent()) throw WorkspaceChangedDuringRetry()
                    }
                }
            }
            val response = try {
                try { requestWithRetry() }
                catch (overflow: ProviderError.ContextOverflow) {
                    try {
                        onContextLimitObserved(overflow, ContextBudget.estimateHistoryTokens(messages.toList()))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Learning is best-effort; the turn still compacts and retries.
                    }
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
                    if (contextFootprint(messages.toList()) >= before) throw overflow
                    requestWithRetry()
                }
            } catch (_: WorkspaceChangedDuringRetry) {
                return AgentRunResult(messages, AgentStopReason.WorkspaceChanged, modelIterations, toolCalls,
                    mutationsAttempted = mutationsAttempted, mutationsCompleted = mutationsCompleted)
            } catch (_: ProviderError.EmptyResponse) {
                return AgentRunResult(messages, AgentStopReason.EmptyResponse, modelIterations, toolCalls,
                    mutationsAttempted = mutationsAttempted, mutationsCompleted = mutationsCompleted)
            } catch (limit: ProviderError.OutputLimitExceeded) {
                try { onOutputLimitObserved(limit) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
                throw limit
            } catch (incomplete: ProviderError.IncompleteGeneration) {
                if (!incomplete.isLengthCut || incomplete.truncatedToolCalls.isEmpty()) {
                    val partial = streamed.toString().ifEmpty { DsmlToolCalls.hideIncomplete(incomplete.partialText) }
                    if (partial.isNotBlank()) {
                        append(AgentMessage.assistant(markedIncomplete(partial)))
                    }
                    throw incomplete
                }
                modelIterations++
                val seenIds = mutableSetOf<String>()
                val truncatedCalls = incomplete.truncatedToolCalls.mapIndexed { index, call ->
                    val baseId = call.id.takeIf { it.isNotBlank() } ?: "_rivet_truncated_${index + 1}"
                    var safeId = baseId
                    var suffix = 2
                    while (!seenIds.add(safeId)) {
                        safeId = "${baseId}_${suffix++}"
                    }
                    val safeName = call.name.takeIf { it.isNotBlank() } ?: "unknown_tool"
                    val safeArgs = call.arguments.takeIf {
                        it.isNotBlank() && jsonNestingWithinLimit(it, 32)
                    } ?: "{}"
                    AgentToolCall(safeId, safeName, safeArgs)
                }
                val assistant = AgentMessage.assistant(
                    text = streamed.toString().ifEmpty { DsmlToolCalls.hideIncomplete(incomplete.partialText) },
                    toolCalls = truncatedCalls,
                    transportState = incomplete.transportState,
                )
                val recoveryResults = AgentMessage.tools(truncatedCalls.map { call ->
                    AgentToolResult(
                        callId = call.id,
                        name = call.name,
                        content = LENGTH_CUT_TOOL_RESULT_CONTENT,
                        error = true,
                        summary = "Limited  ${call.name}".take(256).dropLastWhile { it.isHighSurrogate() },
                    )
                })
                if (!canPersistToolOutput(messages + assistant + recoveryResults, 0)) {
                    return AgentRunResult(
                        messages = messages,
                        stopReason = AgentStopReason.SessionLimit,
                        modelIterations = modelIterations,
                        toolCalls = toolCalls,
                        mutationsAttempted = mutationsAttempted,
                        mutationsCompleted = mutationsCompleted,
                    )
                }
                append(assistant)
                append(recoveryResults)
                continue
            } catch (e: Throwable) {
                val partial = streamed.toString()
                if (partial.isNotBlank()) {
                    // Cancellation is a user/run decision, not a broken stream; leave the
                    // completed text untouched so the existing stop UI can explain it.
                    val assistant = AgentMessage.assistant(
                        if (e is CancellationException) partial else markedIncomplete(partial),
                    )
                    append(assistant)
                }
                throw e
            }
            modelIterations++
            val idCounts = response.toolCalls.groupingBy { it.id }.eachCount()
            val usedIds = idCounts.keys.toMutableSet()
            val invalid = mutableMapOf<String, String>()
            val calls = response.toolCalls.mapIndexed { index, call ->
                val ambiguous = call.id.isBlank() || idCounts.getValue(call.id) > 1
                val tooDeep = !jsonNestingWithinLimit(call.arguments, 32)
                if (!ambiguous && !tooDeep) return@mapIndexed call
                var id = call.id
                if (ambiguous) {
                    var suffix = index + 1
                    do { id = "_rivet_invalid_${suffix++}" } while (!usedIds.add(id))
                }
                invalid[id] = if (ambiguous) "invalid_tool_call_id" else "invalid_arguments"
                call.copy(id = id, arguments = if (tooDeep) "{}" else call.arguments)
            }
            val assistant = AgentMessage.assistant(
                text = response.text,
                toolCalls = calls,
                transportState = response.transportState,
            )
            if (calls.isNotEmpty()) {
                val correlatedErrors = AgentMessage.tools(calls.map { stopped(it, "workspace_changed") })
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
            if (calls.isEmpty()) {
                val holdEvents = beforeCompletion(messages.toList())
                if (holdEvents.isNotEmpty()) {
                    appendContextEvents(holdEvents)?.let { return it }
                    continue
                }
                val reason = finishMemoryProposals()
                return AgentRunResult(
                    messages = messages,
                    stopReason = reason,
                    modelIterations = modelIterations,
                    toolCalls = toolCalls,
                    mutationsAttempted = mutationsAttempted,
                    mutationsCompleted = mutationsCompleted,
                )
            }
            val results = mutableListOf<AgentToolResult>()
            var stopReason: AgentStopReason? = null
            fun pending(code: String) = calls.drop(results.size).map { stopped(it, code) }
            suspend fun fits(candidate: List<AgentToolResult>, reserve: Int = 0): Boolean =
                canPersistToolOutput(messages + AgentMessage.tools(candidate), reserve)
            try {
                for (call in calls) {
                    currentCoroutineContext().ensureActive()
                    if (!workspaceIsCurrent()) {
                        results += pending("workspace_changed")
                        stopReason = AgentStopReason.WorkspaceChanged
                        break
                    }
                    toolCalls++
                    lifecycle(call, AgentToolLifecycleStage.Queued)
                    val invalidCode = invalid[call.id]
                    if (invalidCode != null) {
                        lifecycle(call, AgentToolLifecycleStage.Blocked)
                        results += stopped(call, invalidCode)
                        continue
                    }
                    val replayKey = operationKey(call, replay = true)
                    val receipt = priorAttempts[replayKey]
                    if (receipt != null) {
                        lifecycle(call, AgentToolLifecycleStage.Blocked)
                        results += resumedResult(call, receipt)
                        continue
                    }
                    val prepared = try {
                        prepareTool(call)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) { failed(call) }
                    }
                    observeRepeat(call)
                    val mode = currentAutonomyMode()
                    val decision = ApprovalPolicy.decide(mode, prepared)
                    if (decision is ApprovalDecision.Blocked) {
                        val rejected = stopped(call, decision.reason)
                        val remaining = calls.drop(results.size + 1).map { stopped(it, "not_executed") }
                        if (!fits(results + rejected + remaining)) {
                            results += pending("session_limit")
                            stopReason = AgentStopReason.SessionLimit
                            break
                        }
                        lifecycle(call, AgentToolLifecycleStage.Blocked)
                        results += rejected
                        // A policy refusal is this call's outcome. The model gets it
                        // like any other tool error and decides what to do next.
                        continue
                    }
                    // A bundled skill script needs a person's first yes. When the mode
                    // already asks for this command the existing prompt carries the skill
                    // line; YOLO removes approvals entirely, so the gate is inert there.
                    val allScriptHits = if (prepared.skillScripts.isNotEmpty()) {
                        prepared.skillScripts
                    } else {
                        listOfNotNull(prepared.skillScript)
                    }
                    val unapprovedScripts = allScriptHits.filter { !it.isApproved(scriptApprovals) }
                    val scriptHit = unapprovedScripts.firstOrNull() ?: allScriptHits.firstOrNull()
                    val scriptGate = skillScriptGate(allScriptHits, scriptApprovals,
                        baseAsks = decision == ApprovalDecision.AskUser)
                    val skillInserted = scriptGate == SkillScriptGate.InsertApproval &&
                        prepared.approval != null
                    val blocked = if (prepared.effect.requiresRuntimeBlocker) {
                        try { mutationBlocker(call) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { "runtime_unavailable" }
                    } else null
                    if (blocked != null) {
                        val rejected = stopped(call, blocked)
                        val remaining = calls.drop(results.size + 1).map { stopped(it, "not_executed") }
                        if (!fits(results + rejected + remaining)) {
                            results += pending("session_limit")
                            stopReason = AgentStopReason.SessionLimit
                            break
                        }
                        lifecycle(call, AgentToolLifecycleStage.Blocked)
                        results += rejected
                        // The blocker refused this call, not the turn.
                        continue
                    }
                    // Reserve the bounded correlated event before any side effect, whether
                    // or not this autonomy mode asks a person to approve it.
                    if (prepared.effect != AgentToolEffect.ReadOnly && !fits(
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
                    val asking = decision == ApprovalDecision.AskUser || skillInserted
                    val memoryFact = prepared.memoryFact
                    if (writeMemoryBatch != null && memoryFact != null) {
                        val queued = AgentToolResult(call.id, call.name,
                            """{"status":"proposed","note":"This memory write is proposed. The user decides whether to save it. Nothing is saved if the user declines."}""",
                            summary = "Memory proposed")
                        val remaining = calls.drop(results.size + 1).map { stopped(it, "not_executed") }
                        if (!fits(results + queued + remaining)) {
                            results += pending("session_limit")
                            stopReason = AgentStopReason.SessionLimit
                            break
                        }
                        memoryProposals += MemoryProposal(prepared, memoryFact, asking)
                        results += queued
                        continue
                    }
                    val approval = if (asking) prepared.approval?.let { request ->
                        val target = request.destructivePath
                        val createdFolder = target != null && call.name == "delete_path" && target in createdDirectories
                        val describeLive = target != null && !createdFolder &&
                            (target !in createdPaths || movedExistingPaths.any { it == target || it.startsWith("$target/") })
                        val described = when {
                            createdFolder -> request.copy(
                                title = "Delete folder?",
                                detail = "${request.detail}\nThis folder may contain files added outside Rivet.",
                                dangerous = true,
                            )
                            describeLive -> describeDestructive(request)
                            else -> request
                        }
                        val describedHits = unapprovedScripts.ifEmpty { listOfNotNull(scriptHit) }
                        val reviewed = described.copy(reviewAtGrant = {
                            workspaceIsCurrent() && (!describeLive ||
                                describeDestructive(request).let { it.title == described.title && it.detail == described.detail })
                        })
                        if (describedHits.isNotEmpty() && scriptGate != SkillScriptGate.None) reviewed.copy(
                            detail = described.detail +
                                "\nBundled skill: ${describedHits.joinToString(", ") { it.skill.name }}" +
                                "\nScript: ${describedHits.joinToString(", ") { it.path }}" +
                                "\nApproving lets this skill's scripts run without asking again.",
                        ) else reviewed
                    } else null
                    val rejection = if (!asking) null else {
                        try {
                            lifecycle(call, AgentToolLifecycleStage.AwaitingApproval)
                            if (requestApproval(requireNotNull(approval))) null else "denied"
                        } catch (e: ApprovalUnavailable) {
                            e.code
                        }
                    }
                    if (rejection != null) {
                        lifecycle(call, AgentToolLifecycleStage.Denied)
                        results += stopped(call, rejection)
                        continue
                    }
                    currentCoroutineContext().ensureActive()
                    if (!workspaceIsCurrent()) {
                        results += pending("workspace_changed")
                        stopReason = AgentStopReason.WorkspaceChanged
                        break
                    }
                    val checkpointBlocker = if (prepared.effect.requiresCheckpoint) {
                        try { beforeMutation(call) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { "checkpoint_unavailable" }
                    } else null
                    // Undo is a convenience layer. If it cannot be prepared, run the
                    // operation without an undo row instead of refusing the work.
                    val mutationBlocker = checkpointBlocker?.takeUnless { it == "checkpoint_unavailable" }
                    val acquiredMutation = prepared.effect.requiresCheckpoint &&
                        checkpointBlocker == null
                    var executedResult: AgentToolResult? = null
                    val result = if (mutationBlocker != null) {
                        lifecycle(call, AgentToolLifecycleStage.Blocked)
                        stopped(call, mutationBlocker)
                    } else {
                        val hasEffect = prepared.effect != AgentToolEffect.ReadOnly
                        try {
                            if (hasEffect) {
                                try {
                                    journalTool(replayKey, RunToolReceipt(call.name))
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) {
                                    lifecycle(call, AgentToolLifecycleStage.Blocked)
                                    results += pending("journal_unavailable")
                                    stopReason = AgentStopReason.CheckpointUnavailable
                                    break
                                }
                            }
                            if (prepared.effect.changesWorkspace) mutationsAttempted++
                            // Record standing grants only after recording that execution may start.
                            if (scriptGate != SkillScriptGate.None) {
                                val toApprove = unapprovedScripts.ifEmpty { listOfNotNull(scriptHit) }
                                toApprove.forEach { hit ->
                                    val approvalKey = hit.approvalKey()
                                    scriptApprovals += approvalKey
                                    try {
                                        recordSkillScriptApproval(approvalKey)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        // The grant still covers this turn if the store cannot persist it.
                                    }
                                }
                            }
                            lifecycle(call, AgentToolLifecycleStage.Started)
                            prepared.execute().also { executedResult = it }
                        } catch (e: CancellationException) {
                            lifecycle(call, AgentToolLifecycleStage.Cancelled)
                            if (prepared.effect != AgentToolEffect.ReadOnly) results += stopped(call, "interrupted")
                            throw e
                        } catch (_: Exception) {
                            failed(call)
                        } finally {
                            if (hasEffect || acquiredMutation) {
                                withContext(NonCancellable) {
                                    try {
                                        val recorded = executedResult
                                        if (hasEffect && recorded != null) {
                                            try {
                                                journalTool(replayKey, RunToolReceipt(call.name, recorded.error))
                                            } catch (e: CancellationException) { throw e }
                                            catch (_: Exception) {
                                                // The durable start marker still prevents replay.
                                            }
                                        }
                                    } finally {
                                        if (acquiredMutation) afterMutation(call, executedResult)
                                    }
                                }
                            }
                        }
                    }
                    if (mutationBlocker == null) lifecycle(call,
                        if (result.error) AgentToolLifecycleStage.Failed else AgentToolLifecycleStage.Completed)
                    if (prepared.effect.changesWorkspace && mutationBlocker == null && !result.error) {
                        mutationsCompleted++
                    }
                    val redacted = SecretRedactor.redact(result, secretValues)
                    val bounded = boundResult(redacted, prepared.resultContentLimitBytes)
                    val remaining = calls.drop(results.size + 1).map { stopped(it, "workspace_changed") }
                    if (prepared.effect == AgentToolEffect.ReadOnly && !fits(results + bounded + remaining)) {
                        results += pending("session_limit")
                        stopReason = AgentStopReason.SessionLimit
                        break
                    }
                    results += bounded
                    if (!bounded.error) recordProvenance(
                        call, redacted, createdPaths, createdDirectories, movedExistingPaths,
                    )
                }
            } catch (e: CancellationException) {
                results += pending("cancelled")
                append(SecretRedactor.redact(AgentMessage.tools(results), secretValues))
                throw e
            }
            val toolMessage = SecretRedactor.redact(AgentMessage.tools(results), secretValues)
            // A SAF mutation can finish inside its non-cancellable commit section.
            // Persist its correlated result even if cancellation arrived at that boundary.
            append(toolMessage)
            if (repeatReminders.isNotEmpty()) {
                repeatReminders.forEach { append(it) }
                repeatReminders.clear()
            }
            if (stopReason != null) {
                return AgentRunResult(
                    messages = messages,
                    stopReason = stopReason,
                    modelIterations = modelIterations,
                    toolCalls = toolCalls,
                    mutationsAttempted = mutationsAttempted,
                    mutationsCompleted = mutationsCompleted,
                )
            }
        }
    }

    private class WorkspaceChangedDuringRetry : Exception()

    companion object {
        // dsh-repeat-tool-reminder thresholds. Advisory only.
        private val REPEAT_REMINDER_THRESHOLDS = setOf(3, 5, 8)
        // UTF-8 bytes of structured result content after tool JSON encoding.
        const val MAX_TOOL_RESULT_BYTES = 24 * 1024
        const val OUTPUT_LIMIT_CONTENT =
            "{\"error\":\"output_limit\",\"scope\":\"result\",\"limit_bytes\":24576}"
        const val LENGTH_CUT_TOOL_RESULT_CONTENT =
            "{\"error\":\"output_token_limit\",\"retryable\":true,\"required_action\":\"The tool call was cut off because the response hit the output token limit. Write the change in smaller pieces.\"}"
    }

    private fun repeatReminder(call: AgentToolCall, count: Int): InternalContext {
        val content = if (count == REPEAT_REMINDER_THRESHOLDS.first()) {
            "You are repeating the exact same tool call with identical arguments. " +
                "Carefully analyze the previous result before calling again: if the task is " +
                "not complete, try a different approach or different arguments instead of " +
                "repeating the call."
        } else {
            "Repeated tool call detected:\n" +
                "- tool: ${call.name}\n" +
                "- consecutive_calls: $count\n" +
                "- arguments: ${call.arguments.take(500)}\n" +
                "The repeated calls are not making progress. Inspect the latest result and " +
                "choose a different action, different arguments, or finish the task if enough " +
                "evidence has been gathered."
        }
        return InternalContext(
            kind = REPEAT_REMINDER_KIND,
            content = content,
            digest = InternalContext.digest(content),
        )
    }

    private fun previousToolReceipts(initial: List<AgentMessage>): Map<String, RunToolReceipt> {
        val lastUser = initial.indexOfLast { it.role == AgentRole.User }
        if (lastUser < 0) return emptyMap()
        val receipts = mutableMapOf<String, RunToolReceipt>()
        var pending = emptyMap<String, AgentToolCall>()
        for (message in initial.drop(lastUser + 1)) when (message.role) {
            AgentRole.Assistant -> pending = message.toolCalls.associateBy { it.id }
            AgentRole.Tool -> for (result in message.toolResults) {
                val call = pending[result.callId] ?: continue
                if (defaultEffect(call.name) == AgentToolEffect.ReadOnly) continue
                val receipt = when {
                    !result.error -> RunToolReceipt(call.name, failed = false)
                    result.content.startsWith("{\"error\":\"interrupted\"") -> RunToolReceipt(call.name)
                    else -> continue
                }
                receipts[operationKey(call, replay = true)] = receipt
            }
            else -> Unit
        }
        return receipts
    }

    private fun resumedResult(call: AgentToolCall, receipt: RunToolReceipt): AgentToolResult = when (receipt.failed) {
        null -> stopped(call, "outcome_unknown").copy(summary = "Outcome unknown · ${call.name}")
        true -> stopped(call, "already_attempted").copy(summary = "Already attempted · ${call.name}")
        false -> AgentToolResult(call.id, call.name,
            """{"status":"already_done","note":"This action already completed before the interruption. Rivet did not repeat it."}""",
            summary = "Already done · ${call.name}")
    }

    private fun operationKey(call: AgentToolCall, replay: Boolean = false): String {
        if (!jsonNestingWithinLimit(call.arguments, 32)) {
            return "${call.name}\ninvalid_arguments"
        }
        val canonical = try {
            val args = Json.parseToJsonElement(call.arguments)
            val effectArgs = if (replay && args is JsonObject) {
                JsonObject(args - setOf("ask_user", "expected_sha256", "timeout_ms"))
            } else args
            canonicalArguments(effectArgs)
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
            "approval_expired" -> "Approval expired · ${call.name}"
            "approval_changed" -> "Approval changed · ${call.name}"
            "tool_failed" -> "Failed  ${call.name}"
            else -> "Stopped  ${call.name}"
        }.take(256).dropLastWhile { it.isHighSurrogate() },
    )

    private fun failed(call: AgentToolCall) = stopped(call, "tool_failed")

    private fun boundResult(result: AgentToolResult, contentLimit: Int): AgentToolResult {
        val limit = minOf(contentLimit, MAX_TOOL_RESULT_BYTES)
        val content = result.content
        val summary = result.summary.take(256).dropLastWhile { it.isHighSurrogate() }
        if (content.toByteArray(Charsets.UTF_8).size <= limit) return result.copy(summary = summary)
        var low = 0
        var high = minOf(content.length / 2, limit / 2)
        var retained = "{\"truncated\":true}"
        while (low <= high) {
            val size = (low + high) / 2
            val head = content.take(size).dropLastWhile(Char::isHighSurrogate)
            val tail = content.takeLast(size).dropWhile(Char::isLowSurrogate)
            val candidate = JsonObject(mapOf(
                "truncated" to JsonPrimitive(true),
                "head" to JsonPrimitive(head),
                "tail" to JsonPrimitive(tail),
            )).toString()
            if (candidate.toByteArray(Charsets.UTF_8).size <= limit) {
                retained = candidate
                low = size + 1
            } else {
                high = size - 1
            }
        }
        return result.copy(content = retained, summary = summary)
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
            "download_file" -> if (field("created") == "true") created += path
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
