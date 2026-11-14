package com.kaiser.rivet.subagent

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.kaiser.rivet.agent.AgentContext
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.agent.ContextBudget
import com.kaiser.rivet.agent.ProjectContext
import com.kaiser.rivet.agent.RunPauseReason
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.agent.RunRecord
import com.kaiser.rivet.agent.RunStep
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.provider.contextInputTokens
import com.kaiser.rivet.storage.AgentSessionCodec
import com.kaiser.rivet.storage.AgentSessionLimitException
import com.kaiser.rivet.storage.CodingSessions
import com.kaiser.rivet.storage.ContextAttempt
import com.kaiser.rivet.storage.ContextEstimate
import com.kaiser.rivet.storage.ContextStore
import com.kaiser.rivet.storage.InterruptedTurnRecovery
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

data class WorkerRecord(
    val id: String,
    val runId: String,
    val parentSessionId: String?,
    val workspaceId: String?,
    val job: String,
    val brief: String,
    val skill: String? = null,
    val configId: String,
    val model: String,
    val modelLabel: String = model,
    val writeScope: List<WorkspacePath> = emptyList(),
    val state: RunPhase = RunPhase.Queued,
    val pauseReason: RunPauseReason? = null,
    val waitingReason: String? = null,
    val activity: String? = null,
    val retryOf: String? = null,
    val summary: String = "",
    val envelope: String? = null,
    val error: String? = null,
    val steps: List<RunStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val promotedSessionId: String? = null,
    val interrupted: Boolean = false,
) {
    val isTerminal: Boolean
        get() = state == RunPhase.Done || state == RunPhase.Failed || state == RunPhase.Stopped

    fun toWorkerRun(): WorkerRun = WorkerRun(
        record = RunRecord(
            id = id,
            workspaceId = workspaceId,
            title = job,
            phase = state,
            pause = pauseReason,
            startedAt = createdAt,
            updatedAt = updatedAt,
            steps = steps,
            failure = error,
        ),
        waitingReason = waitingReason,
    )

    fun withWorkerRun(workerRun: WorkerRun): WorkerRecord = copy(
        state = workerRun.record.phase,
        pauseReason = workerRun.record.pause,
        waitingReason = workerRun.waitingReason,
        steps = workerRun.record.steps,
        error = workerRun.record.failure,
        updatedAt = workerRun.record.updatedAt,
    )
}

data class WorkerTranscript(
    val worker: WorkerRecord,
    val messages: List<AgentMessage>,
    val summary: String,
    val interrupted: Boolean,
)

/**
 * Separate SQLite store (`subagents.db`) for worker rows, event logs, 512 KB active
 * projections, compactions, context attempts, usage anchors, and per-session spawn grants.
 * Worker transcripts never live in `coding-sessions.db` unless explicitly promoted in P4.
 */
internal class WorkerSessionStore internal constructor(
    private val backend: WorkerStoreBackend,
) : ContextStore {
    constructor(context: Context) : this(SqliteWorkerStoreBackend(context.applicationContext))

    private val lock = Mutex()
    private val summaryBytesByWorker = ConcurrentHashMap<String, Int>()

    suspend fun saveWorker(worker: WorkerRecord): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            summaryBytesByWorker[worker.id] = worker.summary.toByteArray(Charsets.UTF_8).size
            backend.upsertWorker(worker)
        }
    }

    suspend fun loadWorker(workerId: String): WorkerRecord? = withContext(Dispatchers.IO) {
        lock.withLock {
            backend.getWorker(workerId)?.also {
                summaryBytesByWorker[workerId] = it.summary.toByteArray(Charsets.UTF_8).size
            }
        }
    }

    suspend fun workersForRun(runId: String): List<WorkerRecord> = withContext(Dispatchers.IO) {
        lock.withLock {
            backend.listWorkersForRun(runId).also { list ->
                list.forEach { summaryBytesByWorker[it.id] = it.summary.toByteArray(Charsets.UTF_8).size }
            }
        }
    }

    suspend fun workersForSession(sessionId: String): List<WorkerRecord> = withContext(Dispatchers.IO) {
        lock.withLock {
            backend.listWorkersForSession(sessionId).also { list ->
                list.forEach { summaryBytesByWorker[it.id] = it.summary.toByteArray(Charsets.UTF_8).size }
            }
        }
    }

    suspend fun loadTranscript(workerId: String): WorkerTranscript = withContext(Dispatchers.IO) {
        lock.withLock {
            val worker = backend.getWorker(workerId)
                ?: throw IllegalArgumentException("Unknown worker '$workerId'")
            val summaryBytes = worker.summary.toByteArray(Charsets.UTF_8).size
            summaryBytesByWorker[workerId] = summaryBytes
            val active = backend.getActiveMessages(workerId)
            val interrupted = InterruptedTurnRecovery.hasPendingToolCall(active.lastOrNull()) ||
                (worker.interrupted && InterruptedTurnRecovery.hasUnfinishedTurn(active))
            val messages = recoverInterruptedLocked(worker, interrupted, active, summaryBytes)
            val updatedWorker = backend.getWorker(workerId) ?: worker
            WorkerTranscript(
                worker = updatedWorker,
                messages = messages,
                summary = updatedWorker.summary,
                interrupted = interrupted,
            )
        }
    }

    suspend fun saveMessages(
        workerId: String,
        messages: List<AgentMessage>,
        interrupted: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val worker = backend.getWorker(workerId)
                ?: throw IllegalArgumentException("Unknown worker '$workerId'")
            val active = backend.getActiveMessages(workerId)
            val count = active.size
            val last = active.lastOrNull()
            val secondLast = active.getOrNull(count - 2)
            val retractEmpty = messages.size == count - 1 &&
                last?.role == AgentRole.Assistant &&
                last.text.isBlank() &&
                last.toolCalls.isEmpty() &&
                (count == 1 || messages.lastOrNull() == secondLast)
            if (!retractEmpty && (messages.size < count || (count > 0 && messages[count - 1] != last))) {
                throw IllegalStateException("Worker event prefix changed")
            }
            if (InterruptedTurnRecovery.hasPendingToolCall(last) && messages.size > count) {
                val next = messages[count]
                require(
                    next.role == AgentRole.Tool &&
                        next.toolResults.map { it.callId to it.name } == last!!.toolCalls.map { it.id to it.name },
                ) {
                    "Pending tool call requires its correlated results"
                }
            }
            val summaryBytes = worker.summary.toByteArray(Charsets.UTF_8).size
            summaryBytesByWorker[workerId] = summaryBytes
            if (!AgentSessionCodec.fits(messages, summaryBytes)) {
                throw AgentSessionLimitException(AgentSessionCodec.MAX_SERIALIZED_BYTES + 1)
            }
            val effectiveInterrupted = interrupted || InterruptedTurnRecovery.hasPendingToolCall(messages.lastOrNull())
            backend.appendMessages(
                workerId = workerId,
                retractLast = retractEmpty,
                newMessages = messages.drop(if (retractEmpty) count - 1 else count),
                interrupted = effectiveInterrupted,
                now = System.currentTimeMillis(),
            )
        }
    }

    fun canSaveWithReserve(
        workerId: String,
        messages: List<AgentMessage>,
        reservedEncodedBytes: Int,
    ): Boolean {
        val summaryBytes = summaryBytesByWorker[workerId] ?: 0
        return AgentSessionCodec.fits(messages, reservedEncodedBytes + summaryBytes)
    }

    suspend fun markInterrupted(workerId: String, interrupted: Boolean): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!interrupted) {
                val last = backend.getActiveMessages(workerId).lastOrNull()
                if (InterruptedTurnRecovery.hasPendingToolCall(last)) return@withLock
            }
            backend.setInterrupted(workerId, interrupted, System.currentTimeMillis())
        }
    }

    suspend fun recentMessages(workerId: String, limit: Int = 100): List<AgentMessage> =
        withContext(Dispatchers.IO) {
            require(limit in 1..500)
            lock.withLock {
                backend.getAllEvents(workerId)
                    .filter { it.role != AgentRole.Context && it.internalContext == null }
                    .takeLast(limit)
            }
        }

    suspend fun fullEventCount(workerId: String): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            backend.getEventCount(workerId)
        }
    }

    suspend fun hasSpawnGrant(sessionId: String?): Boolean = withContext(Dispatchers.IO) {
        if (sessionId.isNullOrBlank()) return@withContext false
        lock.withLock {
            backend.hasSpawnGrant(sessionId)
        }
    }

    suspend fun grantSpawn(sessionId: String?, now: Long = System.currentTimeMillis()): Unit =
        withContext(Dispatchers.IO) {
            if (sessionId.isNullOrBlank()) return@withContext
            lock.withLock {
                backend.grantSpawn(sessionId, now)
            }
        }

    suspend fun deleteForSession(sessionId: String): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val workers = backend.listWorkersForSession(sessionId)
            workers.forEach { summaryBytesByWorker.remove(it.id) }
            backend.deleteForSession(sessionId)
        }
    }

    /**
     * Prunes heavy transcript payloads (`worker_events`, `worker_active`, `worker_compactions`,
     * `worker_context_attempts`, `worker_usage`) for finished runs older than [maxAgeMs]
     * (14 days) or beyond the newest [maxRuns] (20) runs with workers, while keeping the
     * lightweight `workers` summary row until `RunJournal.prune(20)` removes the run.
     */
    suspend fun prune(
        now: Long = System.currentTimeMillis(),
        maxAgeMs: Long = RETENTION_MS,
        maxRuns: Int = MAX_RETAINED_RUNS,
    ): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            backend.pruneTranscripts(now, maxAgeMs, maxRuns)
        }
    }

    override suspend fun compact(
        expected: List<AgentMessage>,
        retained: List<AgentMessage>,
        summary: String,
        attempt: ContextAttempt?,
        expectedSessionId: String?,
    ): List<AgentMessage> = withContext(Dispatchers.IO) {
        val workerId = requireNotNull(expectedSessionId) { "workerId is required for worker compaction" }
        require(summary.toByteArray(Charsets.UTF_8).size <= CodingSessions.MAX_SUMMARY_BYTES)
        lock.withLock {
            val worker = backend.getWorker(workerId)
                ?: throw IllegalArgumentException("Unknown worker '$workerId'")
            require(retained != expected || summary != worker.summary)
            require(InterruptedTurnRecovery.validProjection(expected, retained))
            val projected = ProjectContext.projection(expected, retained, summary, worker.workspaceId)
            require(AgentContext.validGroups(projected))
            val summaryBytes = summary.toByteArray(Charsets.UTF_8).size
            if (!AgentSessionCodec.fits(projected, summaryBytes)) {
                throw AgentSessionLimitException(AgentSessionCodec.MAX_SERIALIZED_BYTES + 1)
            }
            val currentActive = backend.getActiveMessages(workerId)
            if (currentActive != expected) {
                throw IllegalStateException("Active context changed")
            }
            backend.recordCompaction(
                workerId = workerId,
                projected = projected,
                summary = summary,
                beforeCount = expected.size,
                afterCount = projected.size,
                attempt = attempt,
                beforeBytes = AgentContext.serializedBytes(expected),
                afterBytes = AgentContext.serializedBytes(projected),
                summaryBytes = summaryBytes,
                now = System.currentTimeMillis(),
            )
            summaryBytesByWorker[workerId] = summaryBytes
            projected
        }
    }

    override suspend fun recordContextFailure(sessionId: String, attempt: ContextAttempt) =
        withContext(Dispatchers.IO) {
            require(attempt.failure != null)
            lock.withLock {
                backend.recordContextFailure(sessionId, attempt, System.currentTimeMillis())
            }
        }

    override suspend fun recordUsage(
        sessionId: String,
        turnId: String,
        providerId: String,
        model: String,
        usage: AgentUsage?,
        requestMessages: List<AgentMessage>,
        system: String,
        tools: List<AgentToolDefinition>,
        endpoint: String,
        providerType: ProviderType?,
        reasoning: ReasoningLevel,
    ) = withContext(Dispatchers.IO) {
        lock.withLock {
            backend.recordUsage(
                workerId = sessionId,
                turnId = turnId,
                providerId = providerId,
                model = model,
                usage = usage,
                requestMessages = requestMessages,
                system = system,
                tools = tools,
                endpoint = endpoint,
                providerType = providerType,
                reasoning = reasoning,
                now = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun contextEstimate(
        sessionId: String,
        providerId: String,
        model: String,
        messages: List<AgentMessage>,
        system: String,
        tools: List<AgentToolDefinition>,
        endpoint: String,
        reasoning: ReasoningLevel,
    ): ContextEstimate = withContext(Dispatchers.IO) {
        lock.withLock {
            backend.contextEstimate(
                workerId = sessionId,
                providerId = providerId,
                model = model,
                messages = messages,
                system = system,
                tools = tools,
                endpoint = endpoint,
                reasoning = reasoning,
            )
        }
    }

    private fun recoverInterruptedLocked(
        worker: WorkerRecord,
        interrupted: Boolean,
        active: List<AgentMessage>,
        summaryBytes: Int,
    ): List<AgentMessage> {
        if (!interrupted) {
            if (worker.interrupted) {
                backend.setInterrupted(worker.id, false, System.currentTimeMillis())
            }
            return active
        }
        val plan = InterruptedTurnRecovery.plan(active, summaryBytes) ?: run {
            if (!worker.interrupted) {
                backend.setInterrupted(worker.id, true, System.currentTimeMillis())
            }
            return active
        }
        backend.applyInterruptedRecovery(
            workerId = worker.id,
            plan = plan,
            summary = worker.summary,
            beforeCount = active.size + 1,
            now = System.currentTimeMillis(),
        )
        return plan.activeAfterRecovery
    }

    companion object {
        const val RETENTION_MS = 14L * 24L * 60L * 60L * 1000L
        const val MAX_RETAINED_RUNS = 20

        fun inMemory(): WorkerSessionStore = WorkerSessionStore(InMemoryWorkerStoreBackend())
    }
}

internal interface WorkerStoreBackend {
    fun upsertWorker(worker: WorkerRecord)
    fun getWorker(workerId: String): WorkerRecord?
    fun listWorkersForRun(runId: String): List<WorkerRecord>
    fun listWorkersForSession(sessionId: String): List<WorkerRecord>
    fun getActiveMessages(workerId: String): List<AgentMessage>
    fun getAllEvents(workerId: String): List<AgentMessage>
    fun getEventCount(workerId: String): Int
    fun appendMessages(
        workerId: String,
        retractLast: Boolean,
        newMessages: List<AgentMessage>,
        interrupted: Boolean,
        now: Long,
    )
    fun setInterrupted(workerId: String, interrupted: Boolean, now: Long)
    fun applyInterruptedRecovery(
        workerId: String,
        plan: InterruptedTurnRecovery.RecoveryPlan,
        summary: String,
        beforeCount: Int,
        now: Long,
    )
    fun recordCompaction(
        workerId: String,
        projected: List<AgentMessage>,
        summary: String,
        beforeCount: Int,
        afterCount: Int,
        attempt: ContextAttempt?,
        beforeBytes: Int,
        afterBytes: Int,
        summaryBytes: Int,
        now: Long,
    )
    fun recordContextFailure(workerId: String, attempt: ContextAttempt, now: Long)
    fun recordUsage(
        workerId: String,
        turnId: String,
        providerId: String,
        model: String,
        usage: AgentUsage?,
        requestMessages: List<AgentMessage>,
        system: String,
        tools: List<AgentToolDefinition>,
        endpoint: String,
        providerType: ProviderType?,
        reasoning: ReasoningLevel,
        now: Long,
    )
    fun contextEstimate(
        workerId: String,
        providerId: String,
        model: String,
        messages: List<AgentMessage>,
        system: String,
        tools: List<AgentToolDefinition>,
        endpoint: String,
        reasoning: ReasoningLevel,
    ): ContextEstimate
    fun hasSpawnGrant(sessionId: String): Boolean
    fun grantSpawn(sessionId: String, now: Long)
    fun deleteForSession(sessionId: String)
    fun pruneTranscripts(now: Long, maxAgeMs: Long, maxRuns: Int): Int
}

internal object WorkerStoreCodec {
    val json = Json { ignoreUnknownKeys = true }
    private val stepsSerializer = ListSerializer(RunStep.serializer())
    private val messagesSerializer = ListSerializer(AgentMessage.serializer())

    fun encodeScope(scope: List<WorkspacePath>): String =
        JsonArray(scope.map { JsonPrimitive(it.value) }).toString()

    fun decodeScope(raw: String): List<WorkspacePath> = try {
        (json.parseToJsonElement(raw) as? JsonArray)?.mapNotNull { elem ->
            val path = (elem as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
            try {
                WorkspacePath.parse(path)
            } catch (_: WorkspaceFailure) {
                null
            }
        }.orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    fun encodeSteps(steps: List<RunStep>): String = json.encodeToString(stepsSerializer, steps)

    fun decodeSteps(raw: String): List<RunStep> = try {
        json.decodeFromString(stepsSerializer, raw)
    } catch (_: Exception) {
        emptyList()
    }

    fun encodeMessage(message: AgentMessage): String =
        json.encodeToString(AgentMessage.serializer(), message)

    fun decodeMessage(raw: String): AgentMessage =
        json.decodeFromString(AgentMessage.serializer(), raw)

    fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun hashMessages(messages: List<AgentMessage>): String =
        hash(json.encodeToString(messagesSerializer, messages))

    fun requestEnvironmentHash(
        endpoint: String,
        system: String,
        tools: List<AgentToolDefinition>,
        reasoning: ReasoningLevel,
    ): String = hash(buildString {
        fun part(value: String) {
            append(value.length).append(':').append(value)
        }
        part(endpoint)
        part(reasoning.name)
        part(system)
        append(tools.size).append(':')
        tools.forEach { tool ->
            part(tool.name)
            part(tool.description)
            part(tool.parameters.toString())
        }
    })
}

internal data class WorkerUsageAnchor(
    val input: Long,
    val count: Int,
    val prefixHash: String?,
    val environmentHash: String,
    val generation: Int,
)

internal class InMemoryWorkerStoreBackend : WorkerStoreBackend {
    private val workers = linkedMapOf<String, WorkerRecord>()
    private val generations = mutableMapOf<String, Int>()
    private val events = mutableMapOf<String, MutableList<AgentMessage>>()
    private val active = mutableMapOf<String, MutableList<AgentMessage>>()
    private val compactions = mutableMapOf<String, MutableList<String>>()
    private val attempts = mutableMapOf<String, MutableList<ContextAttempt>>()
    private val usageAnchors = mutableMapOf<String, MutableList<Triple<String, String, WorkerUsageAnchor>>>()
    private val spawnGrants = mutableMapOf<String, Long>()

    override fun upsertWorker(worker: WorkerRecord) {
        workers[worker.id] = worker
        generations.putIfAbsent(worker.id, 0)
    }

    override fun getWorker(workerId: String): WorkerRecord? = workers[workerId]

    override fun listWorkersForRun(runId: String): List<WorkerRecord> =
        workers.values.filter { it.runId == runId }.sortedWith(compareBy<WorkerRecord> { it.createdAt }.thenBy { it.id })

    override fun listWorkersForSession(sessionId: String): List<WorkerRecord> =
        workers.values.filter { it.parentSessionId == sessionId }.sortedWith(compareBy<WorkerRecord> { it.createdAt }.thenBy { it.id })

    override fun getActiveMessages(workerId: String): List<AgentMessage> =
        active[workerId]?.toList().orEmpty()

    override fun getAllEvents(workerId: String): List<AgentMessage> =
        events[workerId]?.toList().orEmpty()

    override fun getEventCount(workerId: String): Int =
        events[workerId]?.size ?: 0

    override fun appendMessages(
        workerId: String,
        retractLast: Boolean,
        newMessages: List<AgentMessage>,
        interrupted: Boolean,
        now: Long,
    ) {
        val evList = events.getOrPut(workerId) { mutableListOf() }
        val actList = active.getOrPut(workerId) { mutableListOf() }
        if (retractLast) {
            if (evList.isNotEmpty()) evList.removeAt(evList.lastIndex)
            if (actList.isNotEmpty()) actList.removeAt(actList.lastIndex)
        }
        evList.addAll(newMessages)
        actList.addAll(newMessages)
        workers[workerId]?.let { current ->
            workers[workerId] = current.copy(interrupted = interrupted, updatedAt = now)
        }
    }

    override fun setInterrupted(workerId: String, interrupted: Boolean, now: Long) {
        workers[workerId]?.let { current ->
            workers[workerId] = current.copy(interrupted = interrupted, updatedAt = now)
        }
    }

    override fun applyInterruptedRecovery(
        workerId: String,
        plan: InterruptedTurnRecovery.RecoveryPlan,
        summary: String,
        beforeCount: Int,
        now: Long,
    ) {
        val evList = events.getOrPut(workerId) { mutableListOf() }
        val actList = active.getOrPut(workerId) { mutableListOf() }
        evList.add(plan.pendingToolResult)
        if (plan.fitsInActive) {
            actList.add(plan.pendingToolResult)
        } else {
            if (actList.isNotEmpty()) actList.removeAt(actList.lastIndex)
            compactions.getOrPut(workerId) { mutableListOf() }.add(summary)
        }
        generations[workerId] = (generations[workerId] ?: 0) + 1
        workers[workerId]?.let { current ->
            workers[workerId] = current.copy(interrupted = true, updatedAt = now)
        }
    }

    override fun recordCompaction(
        workerId: String,
        projected: List<AgentMessage>,
        summary: String,
        beforeCount: Int,
        afterCount: Int,
        attempt: ContextAttempt?,
        beforeBytes: Int,
        afterBytes: Int,
        summaryBytes: Int,
        now: Long,
    ) {
        active[workerId] = projected.toMutableList()
        compactions.getOrPut(workerId) { mutableListOf() }.add(summary)
        if (attempt != null) {
            attempts.getOrPut(workerId) { mutableListOf() }.add(attempt)
        }
        generations[workerId] = (generations[workerId] ?: 0) + 1
        workers[workerId]?.let { current ->
            workers[workerId] = current.copy(summary = summary, updatedAt = now)
        }
    }

    override fun recordContextFailure(workerId: String, attempt: ContextAttempt, now: Long) {
        val list = attempts.getOrPut(workerId) { mutableListOf() }
        list.add(attempt)
        while (list.size > 64) list.removeAt(0)
    }

    override fun recordUsage(
        workerId: String,
        turnId: String,
        providerId: String,
        model: String,
        usage: AgentUsage?,
        requestMessages: List<AgentMessage>,
        system: String,
        tools: List<AgentToolDefinition>,
        endpoint: String,
        providerType: ProviderType?,
        reasoning: ReasoningLevel,
        now: Long,
    ) {
        val contextInput = usage?.let {
            if (providerType == null) it.inputTokens else it.contextInputTokens(providerType)
        }
        if (contextInput != null && contextInput > 0 && !turnId.startsWith("compaction-")) {
            val gen = generations[workerId] ?: 0
            val anchor = WorkerUsageAnchor(
                input = contextInput,
                count = requestMessages.size,
                prefixHash = WorkerStoreCodec.hashMessages(requestMessages),
                environmentHash = WorkerStoreCodec.requestEnvironmentHash(endpoint, system, tools, reasoning),
                generation = gen,
            )
            usageAnchors.getOrPut(workerId) { mutableListOf() }.add(Triple(providerId, model, anchor))
        }
    }

    override fun contextEstimate(
        workerId: String,
        providerId: String,
        model: String,
        messages: List<AgentMessage>,
        system: String,
        tools: List<AgentToolDefinition>,
        endpoint: String,
        reasoning: ReasoningLevel,
    ): ContextEstimate {
        val request = AgentRequest(model, messages, system, reasoning, tools)
        val estimated = ContextBudget.estimateRequestTokens(request)
        val gen = generations[workerId] ?: 0
        val anchor = usageAnchors[workerId]
            ?.asReversed()
            ?.firstOrNull { it.first == providerId && it.second == model }
            ?.third
        if (anchor != null &&
            anchor.generation == gen &&
            anchor.environmentHash == WorkerStoreCodec.requestEnvironmentHash(endpoint, system, tools, reasoning) &&
            anchor.count in 1..messages.size &&
            anchor.prefixHash == WorkerStoreCodec.hashMessages(messages.take(anchor.count))
        ) {
            if (anchor.count == messages.size) return ContextEstimate(anchor.input, "reported")
            val base = ContextBudget.estimateRequestTokens(request.copy(messages = messages.take(anchor.count)))
            val delta = (estimated - base).coerceAtLeast(0)
            if (anchor.input <= Long.MAX_VALUE - delta) {
                return ContextEstimate(anchor.input + delta, "estimated")
            }
        }
        return ContextEstimate(estimated, "estimated")
    }

    override fun hasSpawnGrant(sessionId: String): Boolean = spawnGrants.containsKey(sessionId)

    override fun grantSpawn(sessionId: String, now: Long) {
        spawnGrants[sessionId] = now
    }

    override fun deleteForSession(sessionId: String) {
        val toRemove = workers.values.filter { it.parentSessionId == sessionId }.map { it.id }
        for (id in toRemove) {
            workers.remove(id)
            generations.remove(id)
            events.remove(id)
            active.remove(id)
            compactions.remove(id)
            attempts.remove(id)
            usageAnchors.remove(id)
        }
        spawnGrants.remove(sessionId)
    }

    override fun pruneTranscripts(now: Long, maxAgeMs: Long, maxRuns: Int): Int {
        val cutoff = now - maxAgeMs
        val byRun = workers.values.groupBy { it.runId }
        val finishedRunsNewestFirst = byRun.entries
            .filter { (_, runWorkers) -> runWorkers.all { it.isTerminal } }
            .sortedByDescending { (_, runWorkers) -> runWorkers.maxOfOrNull { it.updatedAt } ?: 0L }
        var prunedWorkers = 0
        finishedRunsNewestFirst.forEachIndexed { index, (_, runWorkers) ->
            val lastUpdated = runWorkers.maxOfOrNull { it.updatedAt } ?: 0L
            if (index >= maxRuns || lastUpdated < cutoff) {
                for (w in runWorkers) {
                    val hadEvents = !events[w.id].isNullOrEmpty() || !active[w.id].isNullOrEmpty()
                    events.remove(w.id)
                    active.remove(w.id)
                    compactions.remove(w.id)
                    attempts.remove(w.id)
                    usageAnchors.remove(w.id)
                    if (hadEvents) prunedWorkers++
                }
            }
        }
        return prunedWorkers
    }
}

private class SqliteWorkerStoreBackend(context: Context) : WorkerStoreBackend {
    private val helper = SubagentDatabase(context)

    override fun upsertWorker(worker: WorkerRecord) {
        val db = helper.writableDatabase
        val values = worker.toContentValues()
        db.beginTransaction()
        try {
            val updated = db.update("workers", values, "id=?", arrayOf(worker.id))
            if (updated == 0) {
                db.insertOrThrow("workers", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun getWorker(workerId: String): WorkerRecord? {
        val db = helper.writableDatabase
        return db.rawQuery(
            "SELECT $WORKER_COLUMNS FROM workers WHERE id=?",
            arrayOf(workerId),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toWorkerRecord() else null
        }
    }

    override fun listWorkersForRun(runId: String): List<WorkerRecord> {
        val db = helper.writableDatabase
        return db.rawQuery(
            "SELECT $WORKER_COLUMNS FROM workers WHERE run_id=? ORDER BY created_at ASC, id ASC",
            arrayOf(runId),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toWorkerRecord()) }
        }
    }

    override fun listWorkersForSession(sessionId: String): List<WorkerRecord> {
        val db = helper.writableDatabase
        return db.rawQuery(
            "SELECT $WORKER_COLUMNS FROM workers WHERE parent_session_id=? ORDER BY created_at ASC, id ASC",
            arrayOf(sessionId),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toWorkerRecord()) }
        }
    }

    override fun getActiveMessages(workerId: String): List<AgentMessage> {
        val db = helper.writableDatabase
        return db.rawQuery(
            "SELECT payload FROM worker_active WHERE worker_id=? ORDER BY id ASC",
            arrayOf(workerId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(WorkerStoreCodec.decodeMessage(cursor.getString(0)))
                }
            }
        }
    }

    override fun getAllEvents(workerId: String): List<AgentMessage> {
        val db = helper.writableDatabase
        return db.rawQuery(
            "SELECT payload FROM worker_events WHERE worker_id=? ORDER BY id ASC",
            arrayOf(workerId),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(WorkerStoreCodec.decodeMessage(cursor.getString(0)))
                }
            }
        }
    }

    override fun getEventCount(workerId: String): Int {
        val db = helper.writableDatabase
        return db.rawQuery(
            "SELECT COUNT(*) FROM worker_events WHERE worker_id=?",
            arrayOf(workerId),
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    override fun appendMessages(
        workerId: String,
        retractLast: Boolean,
        newMessages: List<AgentMessage>,
        interrupted: Boolean,
        now: Long,
    ) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            if (retractLast) {
                db.execSQL(
                    "DELETE FROM worker_active WHERE id=(SELECT MAX(id) FROM worker_active WHERE worker_id=?)",
                    arrayOf(workerId),
                )
                db.execSQL(
                    "DELETE FROM worker_events WHERE id=(SELECT MAX(id) FROM worker_events WHERE worker_id=?)",
                    arrayOf(workerId),
                )
            }
            for (message in newMessages) {
                val payload = WorkerStoreCodec.encodeMessage(message)
                db.insertOrThrow("worker_events", null, ContentValues().apply {
                    put("worker_id", workerId)
                    put("payload", payload)
                })
                db.insertOrThrow("worker_active", null, ContentValues().apply {
                    put("worker_id", workerId)
                    put("payload", payload)
                })
            }
            db.execSQL(
                "UPDATE workers SET interrupted=?, updated_at=? WHERE id=?",
                arrayOf(if (interrupted) 1 else 0, now, workerId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun setInterrupted(workerId: String, interrupted: Boolean, now: Long) {
        val db = helper.writableDatabase
        db.execSQL(
            "UPDATE workers SET interrupted=?, updated_at=? WHERE id=?",
            arrayOf(if (interrupted) 1 else 0, now, workerId),
        )
    }

    override fun applyInterruptedRecovery(
        workerId: String,
        plan: InterruptedTurnRecovery.RecoveryPlan,
        summary: String,
        beforeCount: Int,
        now: Long,
    ) {
        val db = helper.writableDatabase
        val payload = WorkerStoreCodec.encodeMessage(plan.pendingToolResult)
        db.beginTransaction()
        try {
            val through = db.insertOrThrow("worker_events", null, ContentValues().apply {
                put("worker_id", workerId)
                put("payload", payload)
            })
            if (plan.fitsInActive) {
                db.insertOrThrow("worker_active", null, ContentValues().apply {
                    put("worker_id", workerId)
                    put("payload", payload)
                })
            } else {
                db.execSQL(
                    "DELETE FROM worker_active WHERE id=(SELECT MAX(id) FROM worker_active WHERE worker_id=?)",
                    arrayOf(workerId),
                )
                db.insertOrThrow("worker_compactions", null, ContentValues().apply {
                    put("worker_id", workerId)
                    put("through_event_id", through)
                    put("summary", summary)
                    put("before_count", beforeCount)
                    put("after_count", plan.activeAfterRecovery.size)
                    put("created_at", now)
                })
            }
            db.execSQL(
                "UPDATE workers SET interrupted=1, active_generation=active_generation+1, updated_at=? WHERE id=?",
                arrayOf(now, workerId),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun recordCompaction(
        workerId: String,
        projected: List<AgentMessage>,
        summary: String,
        beforeCount: Int,
        afterCount: Int,
        attempt: ContextAttempt?,
        beforeBytes: Int,
        afterBytes: Int,
        summaryBytes: Int,
        now: Long,
    ) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val through = db.rawQuery(
                "SELECT MAX(id) FROM worker_events WHERE worker_id=?",
                arrayOf(workerId),
            ).use {
                it.moveToFirst()
                if (it.isNull(0)) 0L else it.getLong(0)
            }
            val compactionId = db.insertOrThrow("worker_compactions", null, ContentValues().apply {
                put("worker_id", workerId)
                put("through_event_id", through)
                put("summary", summary)
                put("before_count", beforeCount)
                put("after_count", afterCount)
                put("created_at", now)
            })
            db.delete("worker_active", "worker_id=?", arrayOf(workerId))
            for (message in projected) {
                db.insertOrThrow("worker_active", null, ContentValues().apply {
                    put("worker_id", workerId)
                    put("payload", WorkerStoreCodec.encodeMessage(message))
                })
            }
            db.execSQL(
                "UPDATE workers SET summary=?, active_generation=active_generation+1, updated_at=? WHERE id=?",
                arrayOf(summary, now, workerId),
            )
            if (attempt != null) {
                insertContextAttempt(db, workerId, through, compactionId, attempt, beforeBytes, afterBytes, summaryBytes, now)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun recordContextFailure(workerId: String, attempt: ContextAttempt, now: Long) {
        val db = helper.writableDatabase
        val through = db.rawQuery(
            "SELECT MAX(id) FROM worker_events WHERE worker_id=?",
            arrayOf(workerId),
        ).use {
            it.moveToFirst()
            if (it.isNull(0)) 0L else it.getLong(0)
        }
        db.beginTransaction()
        try {
            insertContextAttempt(db, workerId, through, null, attempt, null, null, null, now)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun insertContextAttempt(
        db: SQLiteDatabase,
        workerId: String,
        through: Long,
        compactionId: Long?,
        attempt: ContextAttempt,
        beforeBytes: Int?,
        afterBytes: Int?,
        summaryBytes: Int?,
        now: Long,
    ) {
        db.insertOrThrow("worker_context_attempts", null, ContentValues().apply {
            put("worker_id", workerId)
            put("through_event_id", through)
            put("compaction_id", compactionId)
            put("reason", attempt.reason.take(40))
            put("estimate_source", attempt.estimateSource.take(20))
            put("before_tokens", attempt.beforeTokens)
            put("after_tokens", attempt.afterTokens)
            put("before_bytes", beforeBytes)
            put("after_bytes", afterBytes)
            put("summary_bytes", summaryBytes)
            put("provider_id", attempt.providerId.take(100))
            put("model", attempt.model.take(200))
            put("outcome", if (attempt.failure == null) "compacted" else "failed")
            put("failure_code", attempt.failure?.take(80))
            put("created_at", now)
        })
        db.execSQL(
            "DELETE FROM worker_context_attempts WHERE worker_id=? AND id NOT IN " +
                "(SELECT id FROM worker_context_attempts WHERE worker_id=? ORDER BY id DESC LIMIT 64)",
            arrayOf(workerId, workerId),
        )
    }

    override fun recordUsage(
        workerId: String,
        turnId: String,
        providerId: String,
        model: String,
        usage: AgentUsage?,
        requestMessages: List<AgentMessage>,
        system: String,
        tools: List<AgentToolDefinition>,
        endpoint: String,
        providerType: ProviderType?,
        reasoning: ReasoningLevel,
        now: Long,
    ) {
        val db = helper.writableDatabase
        val generation = db.rawQuery(
            "SELECT active_generation FROM workers WHERE id=?",
            arrayOf(workerId),
        ).use {
            if (!it.moveToFirst()) return
            it.getInt(0)
        }
        val contextInput = usage?.let {
            if (providerType == null) it.inputTokens else it.contextInputTokens(providerType)
        }
        db.insertOrThrow("worker_usage", null, ContentValues().apply {
            put("worker_id", workerId)
            put("turn_id", turnId)
            put("provider_id", providerId)
            put("model", model)
            put("source", if (contextInput != null) "reported" else "unknown")
            put("input_tokens", usage?.inputTokens)
            put("output_tokens", usage?.outputTokens)
            put("cache_read_tokens", usage?.cacheReadTokens)
            put("cache_creation_tokens", usage?.cacheCreationTokens)
            put("context_input_tokens", contextInput)
            put("reasoning_tokens", usage?.reasoningTokens)
            put("total_tokens", usage?.totalTokens)
            put("base_count", requestMessages.size)
            put("base_prefix_hash", WorkerStoreCodec.hashMessages(requestMessages))
            put("system_hash", WorkerStoreCodec.requestEnvironmentHash(endpoint, system, tools, reasoning))
            put("active_generation", generation)
            put("created_at", now)
        })
    }

    override fun contextEstimate(
        workerId: String,
        providerId: String,
        model: String,
        messages: List<AgentMessage>,
        system: String,
        tools: List<AgentToolDefinition>,
        endpoint: String,
        reasoning: ReasoningLevel,
    ): ContextEstimate {
        val db = helper.writableDatabase
        val request = AgentRequest(model, messages, system, reasoning, tools)
        val estimated = ContextBudget.estimateRequestTokens(request)
        val generation = db.rawQuery(
            "SELECT active_generation FROM workers WHERE id=?",
            arrayOf(workerId),
        ).use {
            if (!it.moveToFirst()) return ContextEstimate(estimated, "estimated")
            it.getInt(0)
        }
        val anchor = db.rawQuery(
            "SELECT context_input_tokens,base_count,base_prefix_hash,system_hash,active_generation " +
                "FROM worker_usage WHERE worker_id=? AND provider_id=? AND model=? AND source='reported' " +
                "AND context_input_tokens > 0 AND turn_id NOT LIKE 'compaction-%' " +
                "ORDER BY id DESC LIMIT 1",
            arrayOf(workerId, providerId, model),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null
            else WorkerUsageAnchor(
                input = cursor.getLong(0),
                count = cursor.getInt(1),
                prefixHash = if (cursor.isNull(2)) null else cursor.getString(2),
                environmentHash = cursor.getString(3),
                generation = cursor.getInt(4),
            )
        }
        if (anchor != null &&
            anchor.generation == generation &&
            anchor.environmentHash == WorkerStoreCodec.requestEnvironmentHash(endpoint, system, tools, reasoning) &&
            anchor.count in 1..messages.size &&
            anchor.prefixHash == WorkerStoreCodec.hashMessages(messages.take(anchor.count))
        ) {
            if (anchor.count == messages.size) return ContextEstimate(anchor.input, "reported")
            val base = ContextBudget.estimateRequestTokens(request.copy(messages = messages.take(anchor.count)))
            val delta = (estimated - base).coerceAtLeast(0)
            if (anchor.input <= Long.MAX_VALUE - delta) {
                return ContextEstimate(anchor.input + delta, "estimated")
            }
        }
        return ContextEstimate(estimated, "estimated")
    }

    override fun hasSpawnGrant(sessionId: String): Boolean {
        val db = helper.writableDatabase
        return db.rawQuery(
            "SELECT 1 FROM spawn_grants WHERE session_id=? LIMIT 1",
            arrayOf(sessionId),
        ).use { it.moveToFirst() }
    }

    override fun grantSpawn(sessionId: String, now: Long) {
        val db = helper.writableDatabase
        db.insertWithOnConflict("spawn_grants", null, ContentValues().apply {
            put("session_id", sessionId)
            put("granted_at", now)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun deleteForSession(sessionId: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("workers", "parent_session_id=?", arrayOf(sessionId))
            db.delete("spawn_grants", "session_id=?", arrayOf(sessionId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun pruneTranscripts(now: Long, maxAgeMs: Long, maxRuns: Int): Int {
        val db = helper.writableDatabase
        val allWorkers = db.rawQuery("SELECT $WORKER_COLUMNS FROM workers", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toWorkerRecord()) }
        }
        val cutoff = now - maxAgeMs
        val byRun = allWorkers.groupBy { it.runId }
        val finishedRunsNewestFirst = byRun.entries
            .filter { (_, runWorkers) -> runWorkers.all { it.isTerminal } }
            .sortedByDescending { (_, runWorkers) -> runWorkers.maxOfOrNull { it.updatedAt } ?: 0L }

        val targetWorkers = mutableListOf<String>()
        finishedRunsNewestFirst.forEachIndexed { index, (_, runWorkers) ->
            val lastUpdated = runWorkers.maxOfOrNull { it.updatedAt } ?: 0L
            if (index >= maxRuns || lastUpdated < cutoff) {
                runWorkers.forEach { targetWorkers += it.id }
            }
        }
        if (targetWorkers.isEmpty()) return 0

        var pruned = 0
        db.beginTransaction()
        try {
            for (workerId in targetWorkers) {
                val deletedEvents = db.delete("worker_events", "worker_id=?", arrayOf(workerId))
                val deletedActive = db.delete("worker_active", "worker_id=?", arrayOf(workerId))
                db.delete("worker_compactions", "worker_id=?", arrayOf(workerId))
                db.delete("worker_context_attempts", "worker_id=?", arrayOf(workerId))
                db.delete("worker_usage", "worker_id=?", arrayOf(workerId))
                if (deletedEvents > 0 || deletedActive > 0) pruned++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return pruned
    }

    private fun WorkerRecord.toContentValues() = ContentValues().apply {
        put("id", id)
        put("run_id", runId)
        put("parent_session_id", parentSessionId)
        put("workspace_id", workspaceId)
        put("job", job)
        put("brief", brief)
        put("skill", skill)
        put("config_id", configId)
        put("model", model)
        put("model_label", modelLabel)
        put("write_scope", WorkerStoreCodec.encodeScope(writeScope))
        put("state", state.name)
        put("pause_reason", pauseReason?.name)
        put("waiting_reason", waitingReason)
        put("activity", activity)
        put("retry_of", retryOf)
        put("summary", summary)
        put("envelope", envelope)
        put("error", error)
        put("steps", WorkerStoreCodec.encodeSteps(steps))
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("promoted_session_id", promotedSessionId)
        put("interrupted", if (interrupted) 1 else 0)
    }

    private fun Cursor.toWorkerRecord(): WorkerRecord {
        val stateName = getString(11)
        val state = RunPhase.entries.firstOrNull { it.name == stateName } ?: RunPhase.Failed
        val pauseName = if (isNull(12)) null else getString(12)
        val pause = pauseName?.let { name -> RunPauseReason.entries.firstOrNull { it.name == name } }
        return WorkerRecord(
            id = getString(0),
            runId = getString(1),
            parentSessionId = if (isNull(2)) null else getString(2),
            workspaceId = if (isNull(3)) null else getString(3),
            job = getString(4),
            brief = getString(5),
            skill = if (isNull(6)) null else getString(6),
            configId = getString(7),
            model = getString(8),
            modelLabel = getString(9),
            writeScope = WorkerStoreCodec.decodeScope(getString(10)),
            state = state,
            pauseReason = pause,
            waitingReason = if (isNull(13)) null else getString(13),
            activity = if (isNull(14)) null else getString(14),
            retryOf = if (isNull(15)) null else getString(15),
            summary = getString(16),
            envelope = if (isNull(17)) null else getString(17),
            error = if (isNull(18)) null else getString(18),
            steps = WorkerStoreCodec.decodeSteps(getString(19)),
            createdAt = getLong(20),
            updatedAt = getLong(21),
            promotedSessionId = if (isNull(22)) null else getString(22),
            interrupted = getInt(23) != 0,
        )
    }

    companion object {
        private const val WORKER_COLUMNS =
            "id,run_id,parent_session_id,workspace_id,job,brief,skill,config_id,model,model_label," +
                "write_scope,state,pause_reason,waiting_reason,activity,retry_of,summary,envelope," +
                "error,steps,created_at,updated_at,promoted_session_id,interrupted"
    }
}

private class SubagentDatabase(context: Context) : SQLiteOpenHelper(context, "subagents.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE workers (" +
                "id TEXT PRIMARY KEY, " +
                "run_id TEXT NOT NULL, " +
                "parent_session_id TEXT, " +
                "workspace_id TEXT, " +
                "job TEXT NOT NULL, " +
                "brief TEXT NOT NULL, " +
                "skill TEXT, " +
                "config_id TEXT NOT NULL, " +
                "model TEXT NOT NULL, " +
                "model_label TEXT NOT NULL DEFAULT '', " +
                "write_scope TEXT NOT NULL, " +
                "state TEXT NOT NULL, " +
                "pause_reason TEXT, " +
                "waiting_reason TEXT, " +
                "activity TEXT, " +
                "retry_of TEXT, " +
                "summary TEXT NOT NULL DEFAULT '', " +
                "envelope TEXT, " +
                "error TEXT, " +
                "steps TEXT NOT NULL DEFAULT '[]', " +
                "created_at INTEGER NOT NULL, " +
                "updated_at INTEGER NOT NULL, " +
                "promoted_session_id TEXT, " +
                "interrupted INTEGER NOT NULL DEFAULT 0, " +
                "active_generation INTEGER NOT NULL DEFAULT 0" +
                ")",
        )
        db.execSQL("CREATE INDEX workers_run_id ON workers(run_id, created_at)")
        db.execSQL("CREATE INDEX workers_session_id ON workers(parent_session_id, created_at)")
        db.execSQL(
            "CREATE TABLE worker_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "worker_id TEXT NOT NULL REFERENCES workers(id) ON DELETE CASCADE, " +
                "payload TEXT NOT NULL" +
                ")",
        )
        db.execSQL("CREATE INDEX worker_events_worker_id ON worker_events(worker_id, id)")
        db.execSQL(
            "CREATE TABLE worker_active (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "worker_id TEXT NOT NULL REFERENCES workers(id) ON DELETE CASCADE, " +
                "payload TEXT NOT NULL" +
                ")",
        )
        db.execSQL("CREATE INDEX worker_active_worker_id ON worker_active(worker_id, id)")
        db.execSQL(
            "CREATE TABLE worker_compactions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "worker_id TEXT NOT NULL REFERENCES workers(id) ON DELETE CASCADE, " +
                "through_event_id INTEGER NOT NULL, " +
                "summary TEXT NOT NULL, " +
                "before_count INTEGER NOT NULL, " +
                "after_count INTEGER NOT NULL, " +
                "created_at INTEGER NOT NULL" +
                ")",
        )
        db.execSQL("CREATE INDEX worker_compactions_worker_id ON worker_compactions(worker_id, id)")
        db.execSQL(
            "CREATE TABLE worker_context_attempts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "worker_id TEXT NOT NULL REFERENCES workers(id) ON DELETE CASCADE, " +
                "through_event_id INTEGER NOT NULL, " +
                "compaction_id INTEGER, " +
                "reason TEXT NOT NULL, " +
                "estimate_source TEXT NOT NULL, " +
                "before_tokens INTEGER NOT NULL, " +
                "after_tokens INTEGER, " +
                "before_bytes INTEGER, " +
                "after_bytes INTEGER, " +
                "summary_bytes INTEGER, " +
                "provider_id TEXT NOT NULL, " +
                "model TEXT NOT NULL, " +
                "outcome TEXT NOT NULL, " +
                "failure_code TEXT, " +
                "created_at INTEGER NOT NULL" +
                ")",
        )
        db.execSQL("CREATE INDEX worker_context_attempts_worker_id ON worker_context_attempts(worker_id, id)")
        db.execSQL(
            "CREATE TABLE worker_usage (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "worker_id TEXT NOT NULL REFERENCES workers(id) ON DELETE CASCADE, " +
                "turn_id TEXT NOT NULL, " +
                "provider_id TEXT NOT NULL, " +
                "model TEXT NOT NULL, " +
                "source TEXT NOT NULL, " +
                "input_tokens INTEGER, " +
                "output_tokens INTEGER, " +
                "cache_read_tokens INTEGER, " +
                "cache_creation_tokens INTEGER, " +
                "context_input_tokens INTEGER, " +
                "reasoning_tokens INTEGER, " +
                "total_tokens INTEGER, " +
                "base_count INTEGER NOT NULL DEFAULT 0, " +
                "base_prefix_hash TEXT, " +
                "system_hash TEXT, " +
                "active_generation INTEGER NOT NULL DEFAULT 0, " +
                "created_at INTEGER NOT NULL" +
                ")",
        )
        db.execSQL("CREATE INDEX worker_usage_worker_id ON worker_usage(worker_id, id)")
        db.execSQL(
            "CREATE TABLE spawn_grants (" +
                "session_id TEXT PRIMARY KEY, " +
                "granted_at INTEGER NOT NULL" +
                ")",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Unsupported subagents database upgrade $oldVersion to $newVersion")
    }
}
