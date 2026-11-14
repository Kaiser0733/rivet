package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentApprovalGate
import com.kaiser.rivet.agent.AgentLoop
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentRunResult
import com.kaiser.rivet.agent.AgentStopReason
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolEffect
import com.kaiser.rivet.agent.AgentToolError
import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.AgentToolLifecycleStage
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.agent.AutonomyMode
import com.kaiser.rivet.agent.GoalMemoryContext
import com.kaiser.rivet.agent.ProjectContext
import com.kaiser.rivet.agent.ProjectInstructionSet
import com.kaiser.rivet.agent.ProjectInstructions
import com.kaiser.rivet.agent.PreparedAgentTool
import com.kaiser.rivet.agent.RunContext
import com.kaiser.rivet.agent.RunMachine
import com.kaiser.rivet.agent.RunPauseReason
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.agent.Skill
import com.kaiser.rivet.agent.SkillCatalog
import com.kaiser.rivet.agent.SkillContext
import com.kaiser.rivet.agent.ToolchainContext
import com.kaiser.rivet.agent.modelMessages
import com.kaiser.rivet.browser.BrowserDrivingLease
import com.kaiser.rivet.browser.BrowserTools
import com.kaiser.rivet.chat.ContextPreparation
import com.kaiser.rivet.chat.ProviderRuntimeSource
import com.kaiser.rivet.local.LocalPrompt
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ProviderClient
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderError
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.agent.SpendGuard
import com.kaiser.rivet.provider.resolvedContextWindowTokens
import com.kaiser.rivet.runtime.RunCheckpointGate
import com.kaiser.rivet.runtime.ToolchainAvailability
import com.kaiser.rivet.workspace.WorkspacePath
import java.util.Collections
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Shared run-level dependencies passed from `ChatViewModel` (or tests) into
 * `SubagentSupervisor` and `WorkerRunner`.
 */
internal class RunServices(
    val workspaceId: String,
    val runId: String,
    val parentSessionId: String?,
    val workerStore: WorkerSessionStore,
    val providerSource: ProviderRuntimeSource,
    val clientFactory: (ProviderConfig, String) -> ProviderClient,
    val approvals: AgentApprovalGate,
    val checkpointGate: RunCheckpointGate,
    val poolState: () -> SubagentPoolState = { SubagentPoolState() },
    val coordinatorConfig: () -> ProviderConfig? = { null },
    val browserTools: BrowserTools? = null,
    val browserLease: BrowserDrivingLease = BrowserDrivingLease(),
    val createExecutor: (loadedSkills: Set<String>) -> AgentToolExecutor? = { null },
    val extraTools: List<AgentToolDefinition> = emptyList(),
    val customPrepareTool: (suspend (workerId: String, call: AgentToolCall) -> PreparedAgentTool?)? = null,
    val projectInstructions: ProjectInstructions? = null,
    val skills: () -> SkillCatalog? = { null },
    val loadMemoryMarkdown: suspend () -> String = { "" },
    val toolchainAvailability: () -> ToolchainAvailability = { ToolchainAvailability() },
    val terminalStateLine: () -> String? = { null },
    val browserContextLine: () -> String? = { null },
    val systemInstruction: (workspace: Boolean, terminalState: String?, browserState: String?) -> String = { _, _, _ -> "" },
    val activityFor: (String) -> String = { tool ->
        when (tool) {
            "read_file", "list_directory", "search_files", "git_status", "git_diff" -> "Looking through the project…"
            "run_command" -> "Running a project command…"
            else -> "Updating the project…"
        }
    },
    val readAutonomyMode: suspend () -> AutonomyMode = { AutonomyMode.Ask },
    val workspaceIsCurrent: suspend () -> Boolean = { true },
    val commandBlocker: suspend () -> String? = { null },
    val canStartPreview: () -> Boolean = { true },
    val failureState: suspend () -> String = { "ready" },
    val isProjectLockBusy: () -> Boolean = { false },
    val approvedSkillScripts: () -> Set<String> = { emptySet() },
    val recordSkillScriptApproval: suspend (String) -> Unit = {},
    val recordParentUsage: suspend (turnId: String, config: ProviderConfig, usage: AgentUsage?, request: AgentRequest) -> Unit =
        { _, _, _, _ -> },
    val recordSpend: suspend (config: ProviderConfig, usage: AgentUsage?, estimated: Boolean) -> Unit =
        { _, _, _ -> },
)

/**
 * Builds and runs a single worker's isolated `AgentLoop` and `ContextPreparation`,
 * and assembles its verified `ResultEnvelope` on completion, failure, pause, or stop.
 */
internal class WorkerRunner(
    private val services: RunServices,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(
        initialWorker: WorkerRecord,
        brief: WorkerBrief,
        config: ProviderConfig,
        apiKey: String,
        skill: Skill?,
        resuming: Boolean,
        pauseSignal: () -> RunPauseReason?,
        stoppedByUser: () -> Boolean,
        onUpdate: suspend (WorkerRecord) -> Unit,
    ): WorkerRecord {
        var currentWorker = initialWorker
        val observedCommands = Collections.synchronizedList(mutableListOf<ObservedCommand>())
        val loadedSkills = setOfNotNull(skill?.name ?: initialWorker.skill)
        val executor = services.createExecutor(loadedSkills)
        val tools = buildList {
            if (services.browserTools != null) addAll(BrowserTools.definitions)
            if (executor != null) addAll(AgentToolExecutor.definitions)
            addAll(services.extraTools)
        }.filterNot {
            it.name == SubagentTools.SPAWN_TOOL || it.name == SubagentTools.AWAIT_TOOL
        }

        val client = services.clientFactory(config, apiKey)
        val turnId = UUID.randomUUID().toString()
        val observedPaths = linkedMapOf(WorkspacePath.ROOT to true)
        var instructions: ProjectInstructionSet? = services.projectInstructions?.load(observedPaths)

        suspend fun contextEventsFor(messages: List<AgentMessage>): List<AgentMessage> {
            val projectEvents = if (services.projectInstructions != null && instructions != null) {
                ProjectContext.updates(messages, services.workspaceId, instructions!!)
            } else emptyList()
            val skillEvents = SkillContext.updates(messages, services.workspaceId, services.skills())
            val toolchainEvents = ToolchainContext.updates(
                messages,
                services.workspaceId,
                services.toolchainAvailability(),
            )
            val memoryMarkdown = try {
                services.loadMemoryMarkdown()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ""
            }
            // Goal is deliberately excluded from workers (§3.7); only project memory is passed.
            val memoryEvents = GoalMemoryContext.updates(
                messages = messages,
                workspace = services.workspaceId,
                goal = null,
                memory = memoryMarkdown,
            )
            return projectEvents + skillEvents + toolchainEvents + memoryEvents
        }

        val loadedTranscript = if (resuming) {
            try {
                services.workerStore.loadTranscript(currentWorker.id)
            } catch (_: Exception) {
                null
            }
        } else null

        var activeSummary = loadedTranscript?.summary ?: currentWorker.summary
        val context = ContextPreparation(
            store = services.workerStore,
            sessionId = currentWorker.id,
            client = client,
            config = config,
            turnId = "worker-${currentWorker.id}-$turnId",
            initialSummary = activeSummary,
            workspaceId = services.workspaceId,
        ) { messages, _ ->
            val baseSystem = services.systemInstruction(
                true,
                services.terminalStateLine()?.takeIf { it != "Terminal: ready" },
                services.browserContextLine(),
            )
            val wire = if (config.type == ProviderType.Local) {
                LocalPrompt.wire(
                    baseSystem,
                    tools,
                    config.resolvedContextWindowTokens(),
                    workspace = true,
                )
            } else null
            AgentRequest(
                model = config.model,
                messages = modelMessages(messages, services.workspaceId, enforceWorkspace = true),
                system = wire?.system ?: baseSystem,
                reasoning = config.reasoning,
                tools = wire?.tools ?: tools,
            )
        }

        val durable = mutableListOf<AgentMessage>()
        if (loadedTranscript != null && loadedTranscript.messages.isNotEmpty()) {
            durable.addAll(loadedTranscript.messages)
            durable.addAll(contextEventsFor(durable))
            durable.addAll(RunContext.updates(durable, services.workspaceId, currentWorker.toWorkerRun().record))
        } else {
            durable.addAll(contextEventsFor(emptyList()))
            if (skill != null) {
                durable += SkillContext.invocation(skill, services.workspaceId)
            }
            val renderedBrief = brief.withSkillBody(skill?.body).render()
            durable += AgentMessage.user(renderedBrief)
        }

        suspend fun publishUpdate(transform: (WorkerRecord) -> WorkerRecord) {
            currentWorker = transform(currentWorker)
            services.workerStore.saveWorker(currentWorker)
            onUpdate(currentWorker)
        }

        try {
            services.workerStore.saveMessages(currentWorker.id, durable, interrupted = true)
            publishUpdate { it.copy(activity = "Working on it…", updatedAt = System.currentTimeMillis()) }

            val loop = AgentLoop(
                requestModel = { messages, _, onText ->
                    val request = context.requestFor(messages)
                    val response = client.streamAgent(
                        request = request,
                        onReasoning = {},
                        onDelta = onText,
                    )
                    try {
                        services.workerStore.recordUsage(
                            sessionId = currentWorker.id,
                            turnId = turnId,
                            providerId = config.id,
                            model = config.model,
                            usage = response.usage,
                            requestMessages = request.messages,
                            system = request.system,
                            tools = request.tools,
                            endpoint = config.baseUrl,
                            providerType = config.type,
                            reasoning = request.reasoning,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    try {
                        services.recordParentUsage(
                            "worker-${currentWorker.id}-$turnId",
                            config,
                            response.usage,
                            request,
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                    }
                    val responseUsage = response.usage ?: SpendGuard.estimatedUsage(
                        inputChars = request.messages.sumOf { message ->
                            message.text.length.toLong() +
                                message.toolCalls.sumOf { call ->
                                    call.name.length.toLong() + call.arguments.length
                                } +
                                message.toolResults.sumOf { result -> result.content.length.toLong() }
                        } + request.system.length + request.tools.sumOf { tool ->
                            tool.name.length.toLong() + tool.description.length +
                                tool.parameters.toString().length
                        },
                        outputChars = response.text.length.toLong() +
                            response.toolCalls.sumOf { call -> call.arguments.length.toLong() },
                    )
                    services.recordSpend(config, responseUsage, response.usage == null)
                    response
                },
                prepareTool = { call ->
                    // Structural depth-1 enforcement: workers can never call spawn_subagent or await_subagents.
                    if (call.name == SubagentTools.SPAWN_TOOL || call.name == SubagentTools.AWAIT_TOOL) {
                        PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) {
                            AgentToolResult(
                                call.id,
                                call.name,
                                AgentToolError.content("unknown_tool"),
                                error = true,
                                summary = "Failed  ${call.name}",
                            )
                        }
                    } else {
                        if (call.name == "delete_path") {
                            services.checkpointGate.beginRunBoundary("Before deleting a file")
                        }
                        val basePrepared = services.browserTools?.prepare(
                            call = call,
                            owner = currentWorker.id,
                            lease = services.browserLease,
                        ) ?: services.customPrepareTool?.invoke(currentWorker.id, call)
                            ?: executor?.prepare(call)
                            ?: PreparedAgentTool(
                                call,
                                null,
                                effect = AgentToolEffect.ReadOnly,
                            ) {
                                AgentToolResult(
                                    call.id,
                                    call.name,
                                    AgentToolError.content("unknown_tool"),
                                    error = true,
                                    summary = "Failed  ${call.name}",
                                )
                            }

                        val wrappedPrepared = if (call.name == "run_command") {
                            wrapCommandExecution(call, basePrepared, observedCommands) { activityLine ->
                                publishUpdate { it.copy(activity = activityLine, updatedAt = System.currentTimeMillis()) }
                            }
                        } else {
                            basePrepared
                        }

                        val project = services.projectInstructions
                        if (project == null) {
                            wrappedPrepared
                        } else {
                            val additions = project.targets(call)
                            additions.forEach { (path, directory) ->
                                if (path != WorkspacePath.ROOT) {
                                    observedPaths.remove(path)
                                    observedPaths[path] = directory
                                }
                            }
                            while (observedPaths.size > 16) {
                                val oldest = observedPaths.keys.firstOrNull { it != WorkspacePath.ROOT } ?: break
                                observedPaths.remove(oldest)
                            }
                            instructions = project.load(observedPaths)
                            val changed = ProjectContext.updates(
                                durable,
                                services.workspaceId,
                                requireNotNull(instructions),
                            ).isNotEmpty()
                            if (changed && call.name in MUTATION_TOOLS) {
                                PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) {
                                    AgentToolResult(
                                        call.id,
                                        call.name,
                                        "{\"error\":\"project_instructions_loaded\",\"required_action\":\"Review applicable AGENTS.md instructions, then retry.\"}",
                                        error = true,
                                        summary = "Project instructions loaded",
                                    )
                                }
                            } else {
                                wrappedPrepared
                            }
                        }
                    }
                },
                requestApproval = { request ->
                    publishUpdate {
                        it.copy(
                            activity = "waiting for your approval",
                            waitingReason = "waiting for your approval",
                            updatedAt = System.currentTimeMillis(),
                        )
                    }
                    try {
                        services.approvals.await(
                            request = request.copy(requester = "Subagent · ${currentWorker.job}"),
                            owner = currentWorker.id,
                        )
                    } finally {
                        withContext(NonCancellable) {
                            publishUpdate {
                                it.copy(
                                    activity = services.activityFor(request.call.name),
                                    waitingReason = null,
                                    updatedAt = System.currentTimeMillis(),
                                )
                            }
                        }
                    }
                },
                describeDestructive = { req -> executor?.describeDestructive(req) ?: req },
                workspaceIsCurrent = services.workspaceIsCurrent,
                canPersistToolOutput = { candidate, reserve ->
                    services.workerStore.canSaveWithReserve(currentWorker.id, candidate, reserve)
                },
                mutationBlocker = { call ->
                    when (call.name) {
                        "run_command" -> services.commandBlocker()
                        "start_preview" -> if (services.canStartPreview()) null else "process_limit"
                        else -> null
                    }
                },
                beforeMutation = { call ->
                    services.checkpointGate.beforeMutation(currentWorker.id, call)
                },
                afterMutation = { call, result ->
                    services.checkpointGate.afterMutation(currentWorker.id, call, result)
                },
                failureState = services.failureState,
                compactContext = { candidate, force ->
                    val compacted = context.prepare(candidate, force)
                    if (compacted != candidate) {
                        durable.clear()
                        durable.addAll(compacted)
                        activeSummary = context.summary
                    }
                    compacted
                },
                contextFootprint = { candidate -> context.footprint(candidate) },
                contextUpdates = { candidate ->
                    if (services.projectInstructions != null) {
                        instructions = services.projectInstructions.load(observedPaths)
                    }
                    contextEventsFor(candidate)
                },
                approvedSkillScripts = services.approvedSkillScripts(),
                recordSkillScriptApproval = services.recordSkillScriptApproval,
            )

            val runResult = loop.run(
                initial = durable.toList(),
                tools = tools,
                currentAutonomyMode = services.readAutonomyMode,
                onToolLifecycle = { event ->
                    if (event.stage == AgentToolLifecycleStage.AwaitingApproval) {
                        // Activity is already updated inside requestApproval.
                    }
                },
                onMessage = { message ->
                    val candidate = durable + message
                    services.workerStore.saveMessages(currentWorker.id, candidate, interrupted = true)
                    durable += message
                    val nextActivity = message.toolCalls.firstOrNull()?.let { call ->
                        services.activityFor(call.name)
                    } ?: if (message.role == AgentRole.Tool) "Working on it…" else currentWorker.activity

                    var updatedRun = currentWorker.toWorkerRun().record
                    if (message.role == AgentRole.Tool && message.toolResults.any { !it.error }) {
                        val stepSummary = message.toolResults.filterNot { it.error }
                            .joinToString("; ") { "${it.name}: ${it.summary.ifBlank { "done" }}" }
                        updatedRun = RunMachine.step(updatedRun, stepSummary)
                    }
                    publishUpdate {
                        it.withWorkerRun(WorkerRun(updatedRun, it.waitingReason))
                            .copy(activity = nextActivity, updatedAt = System.currentTimeMillis())
                    }
                },
            )

            services.workerStore.saveMessages(
                currentWorker.id,
                runResult.messages,
                interrupted = runResult.stopReason != AgentStopReason.Completed,
            )
            return finalizeFromResult(currentWorker, runResult, observedCommands, onUpdate)
        } catch (e: CancellationException) {
            return withContext(NonCancellable) {
                finalizeCancelled(
                    worker = currentWorker,
                    messages = durable,
                    observedCommands = observedCommands,
                    pauseReason = pauseSignal(),
                    stoppedByUser = stoppedByUser(),
                    onUpdate = onUpdate,
                )
            }
        } catch (e: Throwable) {
            return withContext(NonCancellable) {
                val errorMsg = when (e) {
                    is ProviderError -> e.message ?: "The worker model request failed."
                    else -> e.message ?: "The worker stopped unexpectedly."
                }
                finalizeFailed(
                    worker = currentWorker,
                    messages = durable,
                    observedCommands = observedCommands,
                    stopReason = "provider_error",
                    error = errorMsg,
                    onUpdate = onUpdate,
                )
            }
        } finally {
            services.browserLease.release(initialWorker.id)
            services.approvals.cancel(initialWorker.id)
        }
    }

    private fun wrapCommandExecution(
        call: AgentToolCall,
        base: PreparedAgentTool,
        observedCommands: MutableList<ObservedCommand>,
        onActivity: suspend (String) -> Unit,
    ): PreparedAgentTool {
        val commandText = try {
            (json.parseToJsonElement(call.arguments).jsonObject["command"] as? JsonPrimitive)
                ?.takeIf { it.isString }?.content.orEmpty()
        } catch (_: Exception) {
            ""
        }
        return PreparedAgentTool(
            call = base.call,
            approval = base.approval,
            resultContentLimitBytes = base.resultContentLimitBytes,
            effect = base.effect,
            basicAutonomyRisk = base.basicAutonomyRisk,
            modelRequestsApproval = base.modelRequestsApproval,
            blockedReason = base.blockedReason,
            skillScript = base.skillScript,
            skillScripts = base.skillScripts,
            execute = {
                if (services.isProjectLockBusy()) {
                    onActivity("waiting for the project (another command is running)")
                }
                onActivity(services.activityFor(call.name))
                try {
                    val result = base.execute()
                    val parsed = runCatching { json.parseToJsonElement(result.content).jsonObject }.getOrNull()
                    val exitCode = (parsed?.get("exit_code") as? JsonPrimitive)?.intOrNull
                    val sync = (parsed?.get("sync") as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (commandText.isNotBlank()) {
                        observedCommands += ObservedCommand(commandText, exitCode, sync)
                    }
                    result
                } catch (e: CancellationException) {
                    if (commandText.isNotBlank()) {
                        observedCommands += ObservedCommand(commandText, null, "interrupted")
                    }
                    throw e
                }
            },
        )
    }

    private suspend fun finalizeFromResult(
        worker: WorkerRecord,
        result: AgentRunResult,
        observedCommands: List<ObservedCommand>,
        onUpdate: suspend (WorkerRecord) -> Unit,
    ): WorkerRecord {
        val finalText = result.messages.lastOrNull {
            it.role == AgentRole.Assistant && it.toolCalls.isEmpty()
        }?.text.orEmpty()
        val claim = WorkerResultParser.parse(finalText)
        val attribution = services.checkpointGate.writerAttribution(worker.id)
        val completed = result.stopReason == AgentStopReason.Completed
        val finalRun = if (completed) {
            worker.toWorkerRun().finish(RunPhase.Done)
        } else {
            val err = result.failureCode ?: result.stopReason.name.lowercase()
            worker.toWorkerRun().finish(RunPhase.Failed, failure = err)
        }
        val errorLine = finalRun.record.failure
        val envelope = ResultEnvelope.build(
            workerId = worker.id,
            state = finalRun.record.phase,
            claim = claim,
            stopReason = result.stopReason.name.lowercase(),
            error = errorLine,
            files = attribution.files,
            outOfScopeChanges = attribution.outOfScopeChanges,
            commands = observedCommands.toList(),
            diff = attribution.diff,
        )
        val summaryLine = if (completed) {
            claim.summary.ifBlank { "Completed ${worker.job}" }
        } else {
            errorLine ?: claim.summary.ifBlank { "Failed ${worker.job}" }
        }
        val updated = worker.withWorkerRun(finalRun).copy(
            activity = null,
            waitingReason = null,
            summary = summaryLine,
            envelope = envelope,
            error = errorLine,
            interrupted = !completed,
            updatedAt = System.currentTimeMillis(),
        )
        services.workerStore.saveWorker(updated)
        onUpdate(updated)
        return updated
    }

    private suspend fun finalizeCancelled(
        worker: WorkerRecord,
        messages: List<AgentMessage>,
        observedCommands: List<ObservedCommand>,
        pauseReason: RunPauseReason?,
        stoppedByUser: Boolean,
        onUpdate: suspend (WorkerRecord) -> Unit,
    ): WorkerRecord {
        val finalText = messages.lastOrNull {
            it.role == AgentRole.Assistant && it.toolCalls.isEmpty()
        }?.text.orEmpty()
        val claim = WorkerResultParser.parse(finalText)
        val attribution = services.checkpointGate.writerAttribution(worker.id)

        val nextWorkerRun = if (pauseReason != null && !stoppedByUser) {
            worker.toWorkerRun().pause(pauseReason)
        } else {
            worker.toWorkerRun().finish(RunPhase.Stopped)
        }
        val phase = nextWorkerRun.record.phase
        val stopReasonWire = if (phase == RunPhase.Paused) {
            pauseReason?.name?.lowercase(Locale.ROOT) ?: "paused"
        } else {
            "stopped"
        }
        val envelope = ResultEnvelope.build(
            workerId = worker.id,
            state = phase,
            claim = claim,
            stopReason = stopReasonWire,
            error = null,
            files = attribution.files,
            outOfScopeChanges = attribution.outOfScopeChanges,
            commands = observedCommands.toList(),
            diff = attribution.diff,
        )
        val summaryLine = claim.summary.ifBlank {
            if (phase == RunPhase.Paused) "Paused (${pauseReason?.plain ?: "paused"})"
            else "Stopped"
        }
        val updated = worker.withWorkerRun(nextWorkerRun).copy(
            activity = null,
            waitingReason = if (phase == RunPhase.Paused) pauseReason?.plain else null,
            summary = summaryLine,
            envelope = envelope,
            interrupted = true,
            updatedAt = System.currentTimeMillis(),
        )
        services.workerStore.saveWorker(updated)
        onUpdate(updated)
        return updated
    }

    private suspend fun finalizeFailed(
        worker: WorkerRecord,
        messages: List<AgentMessage>,
        observedCommands: List<ObservedCommand>,
        stopReason: String,
        error: String,
        onUpdate: suspend (WorkerRecord) -> Unit,
    ): WorkerRecord {
        val finalText = messages.lastOrNull {
            it.role == AgentRole.Assistant && it.toolCalls.isEmpty()
        }?.text.orEmpty()
        val claim = WorkerResultParser.parse(finalText)
        val attribution = services.checkpointGate.writerAttribution(worker.id)
        val failedRun = worker.toWorkerRun().finish(RunPhase.Failed, failure = error)
        val envelope = ResultEnvelope.build(
            workerId = worker.id,
            state = RunPhase.Failed,
            claim = claim,
            stopReason = stopReason,
            error = error,
            files = attribution.files,
            outOfScopeChanges = attribution.outOfScopeChanges,
            commands = observedCommands.toList(),
            diff = attribution.diff,
        )
        val updated = worker.withWorkerRun(failedRun).copy(
            activity = null,
            waitingReason = null,
            summary = error,
            envelope = envelope,
            error = error,
            interrupted = true,
            updatedAt = System.currentTimeMillis(),
        )
        services.workerStore.saveWorker(updated)
        onUpdate(updated)
        return updated
    }

    companion object {
        private val MUTATION_TOOLS = setOf(
            "write_file",
            "apply_patch",
            "create_file",
            "create_directory",
            "rename_path",
            "move_path",
            "delete_path",
            "run_command",
            "download_file",
            "remember",
        )
    }
}
