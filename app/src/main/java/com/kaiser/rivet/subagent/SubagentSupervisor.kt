package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.RunPauseReason
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.agent.Skill
import com.kaiser.rivet.chat.ProviderRuntimeResult
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Manages concurrent subagents for one coordinator turn.
 * Owns worker admission scheduling, file-scope reservation, result envelopes,
 * and completion holds.
 */
internal class SubagentSupervisor(
    private val services: RunServices,
    private val scope: CoroutineScope,
) {
    private val workers = ConcurrentHashMap<String, WorkerRecord>()
    private val _state = MutableStateFlow<List<WorkerRecord>>(emptyList())
    val state: StateFlow<List<WorkerRecord>> = _state.asStateFlow()

    private val queueLock = Mutex()
    private val queue = mutableListOf<QueuedWorker>()
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val userStoppedWorkers = ConcurrentHashMap.newKeySet<String>()
    private val deliveredWorkerResults = ConcurrentHashMap.newKeySet<String>()
    private val liveCloudWorkers = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var currentPauseReason: RunPauseReason? = null

    private data class QueuedWorker(
        val worker: WorkerRecord,
        val brief: WorkerBrief,
        val entry: ResolvedPoolEntry,
        val skill: Skill?,
    )

    private fun updateWorker(worker: WorkerRecord) {
        workers[worker.id] = worker
        _state.value = workers.values.sortedBy { it.createdAt }
    }

    suspend fun validateSpawn(arguments: String): SpawnValidation {
        val parsedBrief = WorkerBrief.parse(arguments)
        if (parsedBrief is BriefParse.Invalid) {
            return SpawnValidation.Rejected("invalid_brief", parsedBrief.line)
        }
        val brief = (parsedBrief as BriefParse.Valid).brief

        if (brief.retryOf != null) {
            val target = workers[brief.retryOf] ?: services.workerStore.loadWorker(brief.retryOf)
            if (target == null) {
                return SpawnValidation.Rejected("invalid_retry", "Worker '${brief.retryOf}' does not exist.")
            }
            if (target.state != RunPhase.Failed) {
                return SpawnValidation.Rejected(
                    "invalid_retry",
                    "Worker '${brief.retryOf}' did not fail (current phase: ${target.state.name.lowercase(Locale.ROOT)}). Only failed workers can be retried.",
                )
            }
            val alreadyRetried = workers.values.any { it.retryOf == brief.retryOf }
            if (alreadyRetried) {
                return SpawnValidation.Rejected(
                    "invalid_retry",
                    "Worker '${brief.retryOf}' has already been retried once.",
                )
            }
        }

        val skill = if (brief.skill != null) {
            val catalog = services.skills()
            val found = catalog?.byName(brief.skill)
                ?: catalog?.all?.firstOrNull { it.name.equals(brief.skill, ignoreCase = true) }
            if (found == null) {
                return SpawnValidation.Rejected("invalid_skill", "Skill '${brief.skill}' does not exist.")
            }
            if (!found.modelInvocable) {
                return SpawnValidation.Rejected("invalid_skill", "Skill '${brief.skill}' is not model-invocable.")
            }
            found
        } else null

        val rawModel = runCatching {
            (Json.parseToJsonElement(arguments).jsonObject["model"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull()

        val poolState = services.poolState()
        val configs = services.providerSource.configs()
        val poolValidation = SubagentPool.validate(rawModel.orEmpty(), poolState, configs)
        if (poolValidation is PoolValidation.Refused) {
            return SpawnValidation.Rejected("invalid_model", poolValidation.line)
        }
        val entry = (poolValidation as PoolValidation.Valid).entry

        val loadResult = services.providerSource.loadFor(entry.config.id)
        if (loadResult is ProviderRuntimeResult.Failure) {
            return SpawnValidation.Rejected(
                "provider_unavailable",
                "Cannot load credentials for provider '${entry.config.name}': ${loadResult.message}",
            )
        }

        return SpawnValidation.Valid(brief, entry, skill)
    }

    suspend fun executeSpawn(
        brief: WorkerBrief,
        entry: ResolvedPoolEntry,
        skill: Skill?,
    ): SpawnExecutionResult {
        val workerId = UUID.randomUUID().toString()
        val request = SubagentRequest(
            job = brief.job,
            model = entry.config,
            writeScope = brief.writeScope,
        )
        val live = workers.values.map {
            SubagentLive(
                job = it.job,
                model = services.providerSource.configs().firstOrNull { c -> c.id == it.configId }
                    ?: entry.config,
                writeScope = it.writeScope,
                running = (it.state == RunPhase.Running),
            )
        }
        val coordinator = services.coordinatorConfig() ?: ProviderConfig(
            id = "coord",
            name = "Coordinator",
            type = ProviderType.OpenAiCompat,
            model = "default",
            apiKey = "",
            baseUrl = "",
        )

        when (val admission = SubagentScheduler.admit(request, live, coordinator)) {
            is Admission.Refuse -> {
                return SpawnExecutionResult(
                    json = buildJsonObject {
                        put("error", "spawn_refused")
                        put("retryable", false)
                        put("required_action", admission.line)
                    }.toString(),
                    error = true,
                )
            }
            is Admission.Queue -> {
                val worker = WorkerRecord(
                    id = workerId,
                    runId = services.runId,
                    parentSessionId = services.parentSessionId,
                    workspaceId = services.workspaceId,
                    job = brief.job,
                    brief = brief.brief,
                    skill = skill?.name ?: brief.skill,
                    configId = entry.config.id,
                    model = entry.entry.model,
                    modelLabel = entry.label,
                    writeScope = brief.writeScope,
                    state = RunPhase.Queued,
                    waitingReason = admission.detail,
                    retryOf = brief.retryOf,
                    createdAt = System.currentTimeMillis(),
                )
                updateWorker(worker)
                services.workerStore.saveWorker(worker)
                queueLock.withLock {
                    queue += QueuedWorker(worker, brief, entry, skill)
                }
                return SpawnExecutionResult(
                    json = buildJsonObject {
                        put("worker_id", workerId)
                        put("state", "waiting")
                        put("model_label", entry.label)
                        put("reason", admission.detail)
                    }.toString(),
                    error = false,
                )
            }
            is Admission.Start -> {
                val worker = WorkerRecord(
                    id = workerId,
                    runId = services.runId,
                    parentSessionId = services.parentSessionId,
                    workspaceId = services.workspaceId,
                    job = brief.job,
                    brief = brief.brief,
                    skill = skill?.name ?: brief.skill,
                    configId = entry.config.id,
                    model = entry.entry.model,
                    modelLabel = entry.label,
                    writeScope = brief.writeScope,
                    state = RunPhase.Running,
                    retryOf = brief.retryOf,
                    createdAt = System.currentTimeMillis(),
                )
                updateWorker(worker)
                services.workerStore.saveWorker(worker)
                services.checkpointGate.beginRunBoundary("Before subagent: ${worker.job}")
                services.checkpointGate.scopeRegistry.registerScope(worker.id, worker.writeScope)
                launchWorker(worker, brief, entry, skill)
                return SpawnExecutionResult(
                    json = buildJsonObject {
                        put("worker_id", workerId)
                        put("state", "running")
                        put("model_label", entry.label)
                    }.toString(),
                    error = false,
                )
            }
        }
    }

    private fun launchWorker(
        worker: WorkerRecord,
        brief: WorkerBrief,
        entry: ResolvedPoolEntry,
        skill: Skill?,
        resuming: Boolean = false,
    ) {
        val job = scope.launch {
            if (!entry.local) {
                liveCloudWorkers.add(worker.id)
            }
            try {
                val loaded = services.providerSource.loadFor(entry.config.id)
                if (loaded is ProviderRuntimeResult.Failure) {
                    val failedRun = worker.toWorkerRun().finish(RunPhase.Failed, error = loaded.message)
                    val failedRecord = worker.withWorkerRun(failedRun).copy(
                        activity = null,
                        waitingReason = null,
                        summary = loaded.message,
                        error = loaded.message,
                        envelope = ResultEnvelope.build(
                            workerId = worker.id,
                            state = RunPhase.Failed,
                            claim = WorkerClaim(loaded.message),
                            stopReason = "provider_error",
                            error = loaded.message,
                        ),
                        interrupted = true,
                        updatedAt = System.currentTimeMillis(),
                    )
                    updateWorker(failedRecord)
                    services.workerStore.saveWorker(failedRecord)
                    return@launch
                }
                val apiKey = (loaded as ProviderRuntimeResult.Ready).apiKey
                val runner = WorkerRunner(services)
                val finalRecord = runner.run(
                    initialWorker = worker,
                    brief = brief,
                    config = entry.config,
                    apiKey = apiKey,
                    skill = skill,
                    resuming = resuming,
                    pauseSignal = { currentPauseReason },
                    stoppedByUser = { userStoppedWorkers.contains(worker.id) },
                    onUpdate = { updated ->
                        updateWorker(updated)
                    },
                )
                updateWorker(finalRecord)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val failedRun = worker.toWorkerRun().finish(RunPhase.Failed, error = e.message)
                val failedRecord = worker.withWorkerRun(failedRun).copy(
                    activity = null,
                    waitingReason = null,
                    summary = e.message ?: "Unexpected error",
                    error = e.message,
                    envelope = ResultEnvelope.build(
                        workerId = worker.id,
                        state = RunPhase.Failed,
                        claim = WorkerClaim(e.message ?: "Unexpected error"),
                        stopReason = "internal_error",
                        error = e.message,
                    ),
                    interrupted = true,
                    updatedAt = System.currentTimeMillis(),
                )
                updateWorker(failedRecord)
                services.workerStore.saveWorker(failedRecord)
            } finally {
                liveCloudWorkers.remove(worker.id)
                services.checkpointGate.scopeRegistry.releaseScope(worker.id)
                activeJobs.remove(worker.id)
                drainQueue()
            }
        }
        activeJobs[worker.id] = job
    }

    private suspend fun drainQueue() {
        queueLock.withLock {
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                val request = SubagentRequest(
                    job = queued.brief.job,
                    model = queued.entry.config,
                    writeScope = queued.brief.writeScope,
                )
                val live = workers.values.map {
                    SubagentLive(
                        job = it.job,
                        model = services.providerSource.configs().firstOrNull { c -> c.id == it.configId }
                            ?: queued.entry.config,
                        writeScope = it.writeScope,
                        running = (it.state == RunPhase.Running),
                    )
                }
                val coordinator = services.coordinatorConfig() ?: ProviderConfig(
                    id = "coord",
                    name = "Coordinator",
                    type = ProviderType.OpenAiCompat,
                    model = "default",
                    apiKey = "",
                    baseUrl = "",
                )
                when (val admission = SubagentScheduler.admit(request, live, coordinator)) {
                    is Admission.Start -> {
                        iterator.remove()
                        val starting = queued.worker.copy(
                            state = RunPhase.Running,
                            waitingReason = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                        updateWorker(starting)
                        services.workerStore.saveWorker(starting)
                        services.checkpointGate.beginRunBoundary("Before subagent: ${starting.job}")
                        services.checkpointGate.scopeRegistry.registerScope(starting.id, starting.writeScope)
                        launchWorker(starting, queued.brief, queued.entry, queued.skill)
                    }
                    is Admission.Queue -> {
                        if (queued.worker.waitingReason != admission.detail) {
                            val updated = queued.worker.copy(
                                waitingReason = admission.detail,
                                updatedAt = System.currentTimeMillis(),
                            )
                            updateWorker(updated)
                            services.workerStore.saveWorker(updated)
                        }
                    }
                    is Admission.Refuse -> {
                        // Admission refused due to coordinator/environment change
                    }
                }
            }
        }
    }

    suspend fun awaitTerminal(workerIds: List<String>, timeoutMs: Long): String {
        val idsToWatch = if (workerIds.isEmpty()) {
            workers.keys.toList()
        } else {
            workerIds
        }

        if (idsToWatch.isNotEmpty()) {
            val allFinishedInitially = idsToWatch.all { id ->
                val w = workers[id] ?: services.workerStore.loadWorker(id)?.also { updateWorker(it) }
                w != null && w.isTerminal
            }
            if (!allFinishedInitially && timeoutMs > 0) {
                withTimeoutOrNull(timeoutMs) {
                    state.first { currentList ->
                        val map = currentList.associateBy { it.id }
                        idsToWatch.all { id ->
                            val w = map[id]
                            w != null && w.isTerminal
                        }
                    }
                }
            }
        }

        val currentWorkers = if (workerIds.isEmpty()) {
            workers.values.toList()
        } else {
            workerIds.mapNotNull { id ->
                workers[id] ?: services.workerStore.loadWorker(id)?.also { updateWorker(it) }
            }
        }

        val envelopes = mutableListOf<String>()
        val timedOut = mutableListOf<String>()
        val stillRunning = mutableListOf<String>()

        for (w in currentWorkers) {
            if (w.isTerminal) {
                w.envelope?.let { envelopes.add(it) }
            } else {
                if (w.id in idsToWatch) {
                    timedOut.add(w.id)
                } else {
                    stillRunning.add(w.id)
                }
            }
        }

        return ResultEnvelope.assembleAwaitResult(
            envelopes = envelopes,
            timedOut = timedOut,
            stillRunning = stillRunning,
        )
    }

    fun drainUndeliveredResults(workspaceId: String): List<AgentMessage> {
        val results = mutableListOf<AgentMessage>()
        for (worker in workers.values) {
            if (worker.isTerminal && worker.envelope != null) {
                if (deliveredWorkerResults.add(worker.id)) {
                    results += SubagentContext.resultEvent(
                        workspace = workspaceId,
                        workerId = worker.id,
                        job = worker.job,
                        envelopeJson = worker.envelope,
                    )
                }
            }
        }
        return results
    }

    suspend fun beforeCompletionHold(workspaceId: String): List<AgentMessage> {
        if (hasLiveWorkers()) {
            awaitTerminal(workerIds = emptyList(), timeoutMs = 600_000L)
        }
        return drainUndeliveredResults(workspaceId)
    }

    suspend fun stop(workerId: String) {
        userStoppedWorkers.add(workerId)
        var queuedWorker: QueuedWorker? = null
        queueLock.withLock {
            val idx = queue.indexOfFirst { it.worker.id == workerId }
            if (idx >= 0) {
                queuedWorker = queue.removeAt(idx)
            }
        }
        if (queuedWorker != null) {
            val worker = workers[workerId] ?: queuedWorker!!.worker
            val stoppedRun = worker.toWorkerRun().finish(RunPhase.Stopped)
            val envelope = ResultEnvelope.build(
                workerId = worker.id,
                state = RunPhase.Stopped,
                claim = WorkerClaim("Stopped before starting"),
                stopReason = "stopped",
            )
            val stoppedRecord = worker.withWorkerRun(stoppedRun).copy(
                activity = null,
                waitingReason = null,
                summary = "Stopped",
                envelope = envelope,
                interrupted = true,
                updatedAt = System.currentTimeMillis(),
            )
            updateWorker(stoppedRecord)
            services.workerStore.saveWorker(stoppedRecord)
            services.checkpointGate.beginRunBoundary("Stopped subagent: ${worker.job} — partial")
            drainQueue()
            return
        }

        val runningJob = activeJobs[workerId]
        if (runningJob != null) {
            val worker = workers[workerId]
            services.approvals.cancel(workerId)
            services.checkpointGate.beginRunBoundary("Stopped subagent: ${worker?.job ?: workerId} — partial")
            runningJob.cancel()
        }
    }

    fun pauseAll(reason: RunPauseReason) {
        currentPauseReason = reason
        activeJobs.values.forEach { it.cancel() }
    }

    suspend fun relaunchPaused(runId: String) {
        currentPauseReason = null
        val storedWorkers = services.workerStore.workersForRun(runId)
        for (record in storedWorkers) {
            updateWorker(record)
            if (record.state == RunPhase.Paused) {
                val brief = WorkerBrief(
                    job = record.job,
                    brief = record.brief,
                    writeScope = record.writeScope,
                    skill = record.skill,
                    retryOf = record.retryOf,
                )
                val config = services.providerSource.configs().firstOrNull { it.id == record.configId }
                    ?: continue
                val skill = record.skill?.let { s -> services.skills()?.byName(s) }
                val entry = ResolvedPoolEntry(
                    alias = "resumed",
                    entry = PoolEntry(config.id, record.model),
                    config = config,
                    label = record.modelLabel,
                    local = config.type == ProviderType.Local,
                    windowTokens = 262_000,
                    priceTier = PriceTier.Unknown,
                )
                val resumedWorker = record.copy(
                    state = RunPhase.Running,
                    pauseReason = null,
                    waitingReason = null,
                    updatedAt = System.currentTimeMillis(),
                )
                updateWorker(resumedWorker)
                services.workerStore.saveWorker(resumedWorker)
                services.checkpointGate.scopeRegistry.registerScope(resumedWorker.id, resumedWorker.writeScope)
                launchWorker(resumedWorker, brief, entry, skill, resuming = true)
            } else if (record.state == RunPhase.Queued) {
                val brief = WorkerBrief(
                    job = record.job,
                    brief = record.brief,
                    writeScope = record.writeScope,
                    skill = record.skill,
                    retryOf = record.retryOf,
                )
                val config = services.providerSource.configs().firstOrNull { it.id == record.configId }
                    ?: continue
                val skill = record.skill?.let { s -> services.skills()?.byName(s) }
                val entry = ResolvedPoolEntry(
                    alias = "resumed",
                    entry = PoolEntry(config.id, record.model),
                    config = config,
                    label = record.modelLabel,
                    local = config.type == ProviderType.Local,
                    windowTokens = 262_000,
                    priceTier = PriceTier.Unknown,
                )
                queueLock.withLock {
                    queue += QueuedWorker(record, brief, entry, skill)
                }
            }
        }
        drainQueue()
    }

    suspend fun dismissPaused(runId: String) {
        val storedWorkers = services.workerStore.workersForRun(runId)
        for (record in storedWorkers) {
            if (record.state == RunPhase.Paused || record.state == RunPhase.Queued) {
                val stoppedRun = record.toWorkerRun().finish(RunPhase.Stopped)
                val envelope = record.envelope ?: ResultEnvelope.build(
                    workerId = record.id,
                    state = RunPhase.Stopped,
                    claim = WorkerClaim(record.summary.ifBlank { "Dismissed" }),
                    stopReason = "stopped",
                )
                val stoppedRecord = record.withWorkerRun(stoppedRun).copy(
                    activity = null,
                    waitingReason = null,
                    summary = "Stopped",
                    envelope = envelope,
                    interrupted = true,
                    updatedAt = System.currentTimeMillis(),
                )
                updateWorker(stoppedRecord)
                services.workerStore.saveWorker(stoppedRecord)
            }
        }
    }

    fun hasLiveWorkers(): Boolean = workers.values.any { !it.isTerminal }

    fun hasLiveCloudWorker(): Boolean = liveCloudWorkers.isNotEmpty()

    suspend fun joinAll() {
        activeJobs.values.toList().joinAll()
    }
}
