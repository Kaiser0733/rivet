package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.RunPauseReason
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.agent.Skill
import com.kaiser.rivet.chat.ProviderRuntimeResult
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.withModelId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    private val workerIdleMs: Long = 300_000L,
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
        val resuming: Boolean = false,
    )

    private fun findSkill(name: String): Skill? {
        val catalog = services.skills() ?: return null
        return catalog.skills.firstOrNull { it.name == name }
            ?: catalog.skills.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun coordinatorOrFallback(): ProviderConfig =
        services.coordinatorConfig() ?: ProviderConfig(
            id = "coord",
            type = ProviderType.OpenAiCompatible,
            name = "Coordinator",
            baseUrl = "",
            model = "default",
        )

    @Synchronized
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
            val persistedWorkers = target.parentSessionId?.let {
                services.workerStore.workersForSession(it)
            } ?: services.workerStore.workersForRun(target.runId)
            val alreadyRetried = workers.values.any { it.retryOf == brief.retryOf } ||
                persistedWorkers.any { it.retryOf == brief.retryOf }
            if (alreadyRetried) {
                return SpawnValidation.Rejected(
                    "invalid_retry",
                    "Worker '${brief.retryOf}' has already been retried once.",
                )
            }
        }

        val skill = if (brief.skill != null) {
            val found = findSkill(brief.skill)
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
        return queueLock.withLock {
            val storedWorkers = services.workerStore.workersForRun(services.runId)
            val spawnedThisRun = maxOf(storedWorkers.size, workers.values.count { it.runId == services.runId })
            if (spawnedThisRun >= SubagentScheduler.MAX_SPAWNS_PER_RUN) {
                return@withLock SpawnExecutionResult(
                    json = buildJsonObject {
                        put("error", "spawn_limit")
                        put("retryable", false)
                        put("required_action",
                            "This run has reached its ${SubagentScheduler.MAX_SPAWNS_PER_RUN}-subagent limit. " +
                                "Finish or stop the run before spawning more.")
                    }.toString(),
                    error = true,
                )
            }
            val allConfigs = services.providerSource.configs()
            val live = workers.values.map {
                SubagentLive(
                    job = it.job,
                    model = allConfigs.firstOrNull { c -> c.id == it.configId }
                        ?: entry.config,
                    writeScope = it.writeScope,
                    running = (it.state == RunPhase.Running),
                )
            }
            val coordinator = coordinatorOrFallback()

            when (val admission = SubagentScheduler.admit(request, live, coordinator)) {
                is Admission.Refuse -> {
                    SpawnExecutionResult(
                        json = buildJsonObject {
                            put("error", "spawn_refused")
                            put("retryable", false)
                            put("required_action", admission.line)
                        }.toString(),
                        error = true,
                    )
                }
                is Admission.Queue -> {
                    val pausedReason = currentPauseReason
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
                        state = if (pausedReason != null) RunPhase.Paused else RunPhase.Queued,
                        waitingReason = if (pausedReason != null) null else admission.detail,
                        pauseReason = pausedReason,
                        interrupted = pausedReason != null,
                        retryOf = brief.retryOf,
                        createdAt = System.currentTimeMillis(),
                    )
                    updateWorker(worker)
                    services.workerStore.saveWorker(worker)
                    if (pausedReason == null) {
                        queue += QueuedWorker(worker, brief, entry, skill)
                    }
                    SpawnExecutionResult(
                        json = buildJsonObject {
                            put("worker_id", workerId)
                            put("state", if (pausedReason != null) "paused" else "waiting")
                            put("model_label", entry.label)
                            put("reason", admission.detail)
                        }.toString(),
                        error = false,
                    )
                }
                is Admission.Start -> {
                    val pausedReason = currentPauseReason
                    if (pausedReason != null) {
                        val pausedWorker = WorkerRecord(
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
                            state = RunPhase.Paused,
                            pauseReason = pausedReason,
                            interrupted = true,
                            retryOf = brief.retryOf,
                            createdAt = System.currentTimeMillis(),
                        )
                        updateWorker(pausedWorker)
                        services.workerStore.saveWorker(pausedWorker)
                        return@withLock SpawnExecutionResult(
                            json = buildJsonObject {
                                put("worker_id", workerId)
                                put("state", "paused")
                                put("model_label", entry.label)
                            }.toString(),
                            error = false,
                        )
                    }
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
                    services.checkpointGate.scopeRegistry.register(worker.id, worker.job, worker.writeScope)
                    launchWorker(worker, brief, entry, skill)
                    SpawnExecutionResult(
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
    }

    private fun launchWorker(
        worker: WorkerRecord,
        brief: WorkerBrief,
        entry: ResolvedPoolEntry,
        skill: Skill?,
        resuming: Boolean = false,
    ) {
        if (!entry.local) {
            liveCloudWorkers.add(worker.id)
        }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val workerJob = coroutineContext[Job]!!
            val events = Channel<Unit>(Channel.CONFLATED)
            val idleFailure = AtomicReference<String?>()
            val watchdog = launch {
                while (withTimeoutOrNull(workerIdleMs) { events.receive() } != null) {
                    // Each event renews the deadline, including streamed text and reasoning.
                }
                idleFailure.set("This worker produced no activity for ${workerIdleMs / 1_000} seconds.")
                workerJob.cancel()
            }
            try {
                val loaded = services.providerSource.loadFor(entry.config.id)
                if (loaded is ProviderRuntimeResult.Failure) {
                    val failedRun = worker.toWorkerRun().finish(RunPhase.Failed, failure = loaded.message)
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
                    onActivity = { events.trySend(Unit) },
                    idleFailure = idleFailure::get,
                    onUpdate = { updated ->
                        updateWorker(updated)
                    },
                )
                updateWorker(finalRecord)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val failedRun = worker.toWorkerRun().finish(RunPhase.Failed, failure = e.message)
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
                watchdog.cancel()
                withContext(NonCancellable) {
                    try {
                        val latest = workers[worker.id]
                        if (latest?.state == RunPhase.Running) {
                            val failed = latest.lostExecution(idleFailure.get()
                                ?: "This worker stopped before returning a result.")
                            updateWorker(failed)
                            services.workerStore.saveWorker(failed)
                        }
                    } finally {
                        liveCloudWorkers.remove(worker.id)
                        services.checkpointGate.scopeRegistry.release(worker.id)
                        // Keep the job registered while drainQueue promotes queued work.
                        drainQueue()
                    }
                }
            }
        }
        activeJobs[worker.id] = job
        job.invokeOnCompletion {
            liveCloudWorkers.remove(worker.id)
            activeJobs.remove(worker.id, job)
        }
        job.start()
    }

    private suspend fun drainQueue() {
        queueLock.withLock {
            if (currentPauseReason != null) return@withLock
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
                val coordinator = coordinatorOrFallback()
                when (val admission = SubagentScheduler.admit(request, live, coordinator)) {
                    is Admission.Start -> {
                        iterator.remove()
                        val latest = workers[queued.worker.id] ?: queued.worker
                        val starting = latest.copy(
                            state = RunPhase.Running,
                            waitingReason = null,
                            pauseReason = null,
                            updatedAt = System.currentTimeMillis(),
                        )
                        updateWorker(starting)
                        services.workerStore.saveWorker(starting)
                        services.checkpointGate.beginRunBoundary("Before subagent: ${starting.job}")
                        services.checkpointGate.scopeRegistry.register(starting.id, starting.job, starting.writeScope)
                        launchWorker(starting, queued.brief, queued.entry, queued.skill, resuming = queued.resuming)
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
                        iterator.remove()
                        val latest = workers[queued.worker.id] ?: queued.worker
                        val failedRun = latest.toWorkerRun().finish(RunPhase.Failed, failure = admission.line)
                        val failedRecord = latest.withWorkerRun(failedRun).copy(
                            activity = null,
                            waitingReason = null,
                            summary = admission.line,
                            error = admission.line,
                            envelope = ResultEnvelope.build(
                                workerId = latest.id,
                                state = RunPhase.Failed,
                                claim = WorkerClaim(admission.line),
                                stopReason = "spawn_refused",
                                error = admission.line,
                            ),
                            updatedAt = System.currentTimeMillis(),
                        )
                        updateWorker(failedRecord)
                        services.workerStore.saveWorker(failedRecord)
                    }
                }
            }
        }
    }

    suspend fun awaitTerminal(workerIds: List<String>, timeoutMs: Long): String {
        val unknownIds = mutableListOf<String>()
        val idsToWatch = if (workerIds.isEmpty()) {
            workers.keys.toList()
        } else {
            val known = mutableListOf<String>()
            for (id in workerIds) {
                val resolved = workers[id] ?: services.workerStore.loadWorker(id)?.let { stored ->
                    val record = if (stored.state == RunPhase.Running) {
                        services.workerStore.recoverWorkersForRun(stored.runId, activeJobs.keys.toSet())
                            .firstOrNull { it.id == id } ?: stored
                    } else stored
                    record.also(::updateWorker)
                }
                if (resolved == null) {
                    unknownIds += id
                } else {
                    known += id
                }
            }
            known
        }

        if (idsToWatch.isNotEmpty()) {
            val allFinishedInitially = idsToWatch.all { id ->
                val w = workers[id]
                w != null && w.isTerminal
            }
            if (!allFinishedInitially && timeoutMs > 0) {
                awaitWatchedWorkers(state, idsToWatch.toSet(), timeoutMs)
            }
        }

        val currentWorkers = if (workerIds.isEmpty()) {
            workers.values.toList()
        } else {
            idsToWatch.mapNotNull { id ->
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
            unknownWorkers = unknownIds,
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
            withContext(NonCancellable) {
                runningJob.cancelAndJoin()
                services.checkpointGate.beginRunBoundary("Stopped subagent: ${worker?.job ?: workerId} — partial")
            }
        }
    }

    fun pauseAll(reason: RunPauseReason) {
        currentPauseReason = reason
        activeJobs.values.forEach { it.cancel() }
        scope.launch(NonCancellable) {
            pauseQueuedWorkers(reason)
        }
    }

    private suspend fun pauseQueuedWorkers(reason: RunPauseReason) {
        val paused = mutableListOf<WorkerRecord>()
        queueLock.withLock {
            if (currentPauseReason != reason) return@withLock
            val now = System.currentTimeMillis()
            val iterator = queue.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                iterator.remove()
                val latest = workers[queued.worker.id] ?: queued.worker
                val pausedRecord = latest.copy(
                    state = RunPhase.Paused,
                    pauseReason = reason,
                    waitingReason = null,
                    interrupted = true,
                    updatedAt = now,
                )
                updateWorker(pausedRecord)
                paused += pausedRecord
            }
        }
        for (record in paused) {
            services.workerStore.saveWorker(record)
        }
    }

    suspend fun relaunchPaused(runId: String) {
        currentPauseReason = null
        val storedWorkers = services.workerStore.recoverWorkersForRun(runId, activeJobs.keys.toSet())
        val configs = services.providerSource.configs()
        val resolvedPool = SubagentPool.resolve(services.poolState(), configs)
        val toSave = mutableListOf<WorkerRecord>()
        queueLock.withLock {
            for (record in storedWorkers) {
                updateWorker(record)
                if (record.state != RunPhase.Paused && record.state != RunPhase.Queued) continue
                val brief = WorkerBrief(
                    job = record.job,
                    brief = record.brief,
                    writeScope = record.writeScope,
                    skill = record.skill,
                    retryOf = record.retryOf,
                )
                val config = configs.firstOrNull { it.id == record.configId }
                if (config == null) {
                    val missingMsg = "Provider config '${record.configId}' is unavailable."
                    val failedRun = record.toWorkerRun().finish(RunPhase.Failed, failure = missingMsg)
                    val failedRecord = record.withWorkerRun(failedRun).copy(
                        activity = null,
                        waitingReason = null,
                        pauseReason = null,
                        summary = missingMsg,
                        error = missingMsg,
                        envelope = ResultEnvelope.build(
                            workerId = record.id,
                            state = RunPhase.Failed,
                            claim = WorkerClaim(missingMsg),
                            stopReason = "provider_error",
                            error = missingMsg,
                        ),
                        updatedAt = System.currentTimeMillis(),
                    )
                    updateWorker(failedRecord)
                    toSave += failedRecord
                    continue
                }
                val entry = resolvedPool.entries.firstOrNull {
                    it.entry.configId == config.id && it.entry.model == record.model
                } ?: resolvedPool.fixed?.takeIf {
                    it.entry.configId == config.id && it.entry.model == record.model
                } ?: SubagentPool.resolvedEntry(
                    alias = "resumed",
                    entry = PoolEntry(config.id, record.model),
                    config = if (config.type == ProviderType.Local || config.model == record.model) config
                        else config.withModelId(record.model),
                    local = config.type == ProviderType.Local,
                )
                val skill = record.skill?.let(::findSkill)
                val resuming = record.state == RunPhase.Paused
                val queuedRecord = if (resuming) {
                    record.copy(
                        state = RunPhase.Queued,
                        pauseReason = null,
                        waitingReason = null,
                        updatedAt = System.currentTimeMillis(),
                    ).also {
                        updateWorker(it)
                        toSave += it
                    }
                } else {
                    record
                }
                if (queue.none { it.worker.id == queuedRecord.id }) {
                    queue += QueuedWorker(queuedRecord, brief, entry, skill, resuming = resuming)
                }
            }
        }
        for (updated in toSave) {
            services.workerStore.saveWorker(updated)
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
        while (true) {
            // Admission and drainQueue publish Running before launchWorker registers the
            // job. Take the snapshot under queueLock so joinAll cannot miss that gap.
            val snapshot = queueLock.withLock { activeJobs.values.toList() }
            if (snapshot.isEmpty()) break
            snapshot.joinAll()
        }
    }
}
