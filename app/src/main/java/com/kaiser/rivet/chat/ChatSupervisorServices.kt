package com.kaiser.rivet.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import com.kaiser.rivet.agent.AgentApprovalGate
import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.agent.AutonomyMode
import com.kaiser.rivet.agent.ProjectInstructions
import com.kaiser.rivet.agent.ProjectStateFiles
import com.kaiser.rivet.runtime.ManagedProcesses
import com.kaiser.rivet.runtime.MirrorFailure
import com.kaiser.rivet.runtime.RunCheckpointGate
import com.kaiser.rivet.runtime.RuntimeController
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.agent.SkillCatalog
import com.kaiser.rivet.browser.BrowserDrivingLease
import com.kaiser.rivet.browser.BrowserSession
import com.kaiser.rivet.browser.BrowserTools
import com.kaiser.rivet.provider.ProviderClient
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.storage.CodingSessions
import com.kaiser.rivet.storage.SkillScriptApprovalStore
import com.kaiser.rivet.subagent.ResultEnvelope
import com.kaiser.rivet.subagent.RunServices
import com.kaiser.rivet.subagent.SubagentPoolState
import com.kaiser.rivet.subagent.SubagentSupervisor
import com.kaiser.rivet.subagent.SubagentTools
import com.kaiser.rivet.subagent.WorkerClaim
import com.kaiser.rivet.subagent.WorkerRecord
import com.kaiser.rivet.subagent.WorkerSessionStore
import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.WorkspaceSelection

internal object ChatSupervisorServices {
    fun createServices(
        workspaceId: String,
        runId: String,
        parentSessionId: String?,
        workerStore: WorkerSessionStore,
        providerSource: ProviderRuntimeSource,
        clientFactory: (ProviderConfig, String) -> ProviderClient,
        approvals: AgentApprovalGate,
        checkpointGate: RunCheckpointGate,
        coordinatorConfig: () -> ProviderConfig,
        browserTools: BrowserTools,
        browserLease: BrowserDrivingLease,
        createExecutor: (Set<String>) -> AgentToolExecutor?,
        projectInstructions: ProjectInstructions?,
        skills: () -> SkillCatalog?,
        workspace: SafWorkspace,
        runtime: RuntimeController,
        managedProcesses: ManagedProcesses,
        browser: BrowserSession,
        workspaceSelection: WorkspaceSelection,
        approvedSkillScripts: () -> Set<String>,
        skillScriptApprovals: SkillScriptApprovalStore,
        turnSessionId: () -> String?,
        sessions: () -> CodingSessions?,
        recordSpend: (ProviderConfig, AgentUsage, Boolean) -> Unit,
        readAutonomyMode: suspend () -> AutonomyMode,
        systemInstruction: (Boolean, String?, String?) -> String,
        activityFor: (String) -> String,
    ): RunServices = RunServices(
        workspaceId = workspaceId,
        runId = runId,
        parentSessionId = parentSessionId,
        workerStore = workerStore,
        providerSource = providerSource,
        clientFactory = clientFactory,
        approvals = approvals,
        checkpointGate = checkpointGate,
        poolState = { SubagentPoolState() },
        coordinatorConfig = coordinatorConfig,
        browserTools = browserTools,
        browserLease = browserLease,
        createExecutor = createExecutor,
        projectInstructions = projectInstructions,
        skills = skills,
        loadMemoryMarkdown = {
            val files = ProjectStateFiles(workspace)
            files.loadMemory()
        },
        toolchainAvailability = { runtime.toolchainAvailability() },
        terminalStateLine = { runtime.terminalStateLine(managedProcesses.activeCount()) },
        browserContextLine = { browser.contextLine() },
        systemInstruction = systemInstruction,
        activityFor = activityFor,
        readAutonomyMode = readAutonomyMode,
        workspaceIsCurrent = { workspaceSelection.currentIdentity() == workspaceId },
        commandBlocker = {
            try {
                runtime.commandBlocker()
            } catch (e: MirrorFailure) {
                e.code
            }
        },
        canStartPreview = { managedProcesses.canStartPreview() },
        failureState = { runtime.agentFailureState() },
        isProjectLockBusy = { runtime.isOperationsLocked() },
        approvedSkillScripts = approvedSkillScripts,
        recordSkillScriptApproval = { skillScriptApprovals.approve(it) },
        recordParentUsage = { wTurnId, wConfig, wUsage, wReq ->
            turnSessionId()?.let { sId ->
                sessions()?.recordUsage(
                    sessionId = sId,
                    turnId = wTurnId,
                    providerId = wConfig.id,
                    model = wConfig.model,
                    usage = wUsage,
                    requestMessages = wReq.messages,
                    system = wReq.system,
                    tools = wReq.tools,
                    endpoint = wConfig.baseUrl,
                    providerType = wConfig.type,
                    reasoning = wReq.reasoning,
                )
            }
        },
        recordSpend = { config, usage, estimated ->
            if (usage != null) recordSpend(config, usage, estimated)
        },
    )

    suspend fun initSupervisor(
        services: RunServices,
        parentScope: CoroutineScope,
        retryingInterrupted: Boolean,
        currentRunId: String?,
        onStateUpdate: (List<WorkerRecord>) -> Unit,
        sessionId: () -> String?,
    ): Triple<SubagentSupervisor, SubagentTools, Job> {
        val supervisor = SubagentSupervisor(services, parentScope)
        val collectorJob = parentScope.launch {
            supervisor.state.collect { list -> onStateUpdate(list) }
        }
        if (retryingInterrupted) {
            currentRunId?.let { supervisor.relaunchPaused(it) }
        }
        val tools = SubagentTools(supervisor, services.workerStore, sessionId)
        return Triple(supervisor, tools, collectorJob)
    }

    suspend fun dismissInterruptedWorkers(workerStore: WorkerSessionStore, runId: String) {
        workerStore.workersForRun(runId).forEach { worker ->
            if (worker.state == RunPhase.Paused || worker.state == RunPhase.Queued) {
                val stoppedRun = worker.toWorkerRun().finish(RunPhase.Stopped)
                val stopped = worker.withWorkerRun(stoppedRun).copy(
                    activity = null,
                    waitingReason = null,
                    summary = "Stopped",
                    envelope = worker.envelope ?: ResultEnvelope.build(
                        workerId = worker.id,
                        state = RunPhase.Stopped,
                        claim = WorkerClaim(worker.summary.ifBlank { "Stopped" }),
                        stopReason = "stopped",
                    ),
                    interrupted = true,
                    updatedAt = System.currentTimeMillis(),
                )
                workerStore.saveWorker(stopped)
            }
        }
    }
}
