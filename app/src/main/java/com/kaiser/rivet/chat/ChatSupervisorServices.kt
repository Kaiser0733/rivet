package com.kaiser.rivet.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

import com.kaiser.rivet.agent.AgentApprovalGate
import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.AutonomyMode
import com.kaiser.rivet.agent.ProjectStateFiles
import com.kaiser.rivet.runtime.RunCheckpointGate
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.agent.SkillCatalog
import com.kaiser.rivet.agent.Usage
import com.kaiser.rivet.agent.WorkspaceRoot
import com.kaiser.rivet.browser.BrowserDrivingLease
import com.kaiser.rivet.browser.BrowserSession
import com.kaiser.rivet.browser.BrowserTools
import com.kaiser.rivet.provider.ProviderClient
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.service.ManagedProcesses
import com.kaiser.rivet.service.RuntimeController
import com.kaiser.rivet.service.SkillScriptApprovalStore
import com.kaiser.rivet.service.WorkspaceSelection
import com.kaiser.rivet.storage.CodingSessions
import com.kaiser.rivet.subagent.ResultEnvelope
import com.kaiser.rivet.subagent.RunServices
import com.kaiser.rivet.subagent.SubagentPoolState
import com.kaiser.rivet.subagent.WorkerClaim
import com.kaiser.rivet.subagent.WorkerSessionStore

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
        skills: () -> SkillCatalog?,
        workspace: WorkspaceRoot,
        runtime: RuntimeController,
        managedProcesses: ManagedProcesses,
        browser: BrowserSession,
        workspaceSelection: WorkspaceSelection,
        approvedSkillScripts: () -> Set<String>,
        skillScriptApprovals: SkillScriptApprovalStore,
        turnSessionId: () -> String?,
        sessions: () -> CodingSessions?,
        recordSpend: (ProviderConfig, Usage, Double?) -> Unit,
        readAutonomyMode: () -> AutonomyMode,
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
        commandBlocker = { runtime.commandBlocker() },
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
        recordSpend = recordSpend,
    )

    fun initSupervisor(
        services: RunServices,
        parentScope: kotlinx.coroutines.CoroutineScope,
        retryingInterrupted: Boolean,
        currentRunId: String?,
        onStateUpdate: (List<com.kaiser.rivet.subagent.WorkerRecord>) -> Unit,
        sessionId: () -> String?,
    ): Pair<com.kaiser.rivet.subagent.SubagentSupervisor, com.kaiser.rivet.subagent.SubagentTools> {
        val supervisor = com.kaiser.rivet.subagent.SubagentSupervisor(services, parentScope)
        kotlinx.coroutines.CoroutineScope(parentScope.coroutineContext).launch {
            supervisor.state.collect { list -> onStateUpdate(list) }
        }
        if (retryingInterrupted) {
            currentRunId?.let { supervisor.relaunchPaused(it) }
        }
        val tools = com.kaiser.rivet.subagent.SubagentTools(supervisor, services.workerStore, sessionId)
        return supervisor to tools
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
