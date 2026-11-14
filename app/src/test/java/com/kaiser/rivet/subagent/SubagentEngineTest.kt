package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentApprovalGate
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.agent.RunPauseReason
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.chat.ProviderRuntimeResult
import com.kaiser.rivet.chat.ProviderRuntimeSource
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderClient
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.TestResult
import com.kaiser.rivet.runtime.CheckpointBackend
import com.kaiser.rivet.runtime.RunCheckpointGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentEngineTest {
    private val json = Json { ignoreUnknownKeys = true }

    private class FakeCheckpointBackend : CheckpointBackend {
        override suspend fun beginCheckpoint(workspaceId: String, runId: String?, sessionId: String?): String = "cp-1"
        override suspend fun finishCheckpoint(workspaceId: String, id: String): Boolean = true
        override suspend fun recordCheckpointPost(workspaceId: String, id: String) {}
        override suspend fun checkpointMatchesPost(workspaceId: String, id: String): Boolean = true
        override suspend fun beginRunCheckpoint(
            workspaceId: String,
            runId: String,
            sequence: Int,
            summary: String,
            allowDirty: Boolean,
        ): String? = "b-1"
        override suspend fun finishRunCheckpoint(workspaceId: String, id: String) {}
    }

    private class FakeProviderSource(
        val configsList: List<ProviderConfig>,
        val apiKeys: Map<String, String> = emptyMap(),
    ) : ProviderRuntimeSource {
        override suspend fun load(): ProviderRuntimeResult {
            val cfg = configsList.firstOrNull() ?: return ProviderRuntimeResult.Failure("No configs")
            val key = apiKeys[cfg.id] ?: "fake-key"
            return ProviderRuntimeResult.Ready(cfg, key)
        }
        override suspend fun loadFor(configId: String): ProviderRuntimeResult {
            val cfg = configsList.firstOrNull { it.id == configId } ?: return ProviderRuntimeResult.Failure("Config not found")
            val key = apiKeys[configId] ?: "fake-key"
            return ProviderRuntimeResult.Ready(cfg, key)
        }
        override suspend fun configs(): List<ProviderConfig> = configsList
    }

    private class ControllableClient(
        private val gate: CompletableDeferred<Unit>? = null,
        private val responseText: String = "Summary: Done.\nFindings:\n- complete\nOpen questions: none",
        private val promptTokens: Long = 100L,
        private val completionTokens: Long = 50L,
    ) : ProviderClient {
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun testConnection(): TestResult = TestResult(true, "ok")
        override suspend fun streamAgent(
            request: AgentRequest,
            onReasoning: (String) -> Unit,
            onDelta: (String) -> Unit,
        ): AgentResponse {
            gate?.await()
            onDelta(responseText)
            return AgentResponse(
                text = responseText,
                toolCalls = emptyList(),
                usage = AgentUsage(inputTokens = promptTokens, outputTokens = completionTokens),
            )
        }
    }

    private fun cloudConfig(id: String, model: String = "gpt-4o-mini") = ProviderConfig(
        id = id,
        type = ProviderType.OpenAi,
        name = "OpenAI",
        baseUrl = "https://example.com",
        model = model,
    )

    private fun localConfig(id: String, model: String = "qwen-1.7b") = ProviderConfig(
        id = id,
        type = ProviderType.Local,
        name = "On this phone",
        baseUrl = "local://",
        model = model,
    )

    private fun testServices(
        workerStore: WorkerSessionStore,
        configs: List<ProviderConfig>,
        clientFactory: (ProviderConfig, String) -> ProviderClient = { _, _ -> ControllableClient() },
        onRecordSpend: (ProviderConfig, AgentUsage?, Boolean) -> Unit = { _, _, _ -> },
    ): RunServices = RunServices(
        workspaceId = "test-workspace",
        runId = "test-run",
        parentSessionId = "test-session",
        workerStore = workerStore,
        providerSource = FakeProviderSource(configs),
        clientFactory = clientFactory,
        approvals = AgentApprovalGate(),
        checkpointGate = RunCheckpointGate(FakeCheckpointBackend()),
        poolState = {
            SubagentPoolState(
                allowAgentChoice = true,
                entries = configs.map { PoolEntry(it.id, it.model) },
            )
        },
        coordinatorConfig = { configs.firstOrNull() },
        recordSpend = onRecordSpend,
    )

    @Test
    fun twoWorkersPlusCoordinatorRunTogetherAndRecordSpend() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        var totalPromptTokens = 0L
        var totalCompletionTokens = 0L

        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        var clientIndex = 0

        val services = testServices(
            workerStore = store,
            configs = listOf(config),
            clientFactory = { _, _ ->
                clientIndex++
                if (clientIndex == 1) {
                    ControllableClient(gate1, promptTokens = 120L, completionTokens = 40L)
                } else {
                    ControllableClient(gate2, promptTokens = 200L, completionTokens = 80L)
                }
            },
            onRecordSpend = { _, usage, _ ->
                if (usage != null) {
                    totalPromptTokens += usage.inputTokens
                    totalCompletionTokens += usage.outputTokens
                }
            },
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn("{\"job\": \"job 1\", \"brief\": \"task 1\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val v2 = supervisor.validateSpawn("{\"job\": \"job 2\", \"brief\": \"task 2\", \"model\": \"m1\"}") as SpawnValidation.Valid

        val r1 = supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        val r2 = supervisor.executeSpawn(v2.brief, v2.entry, v2.skill)

        val id1 = json.parseToJsonElement(r1.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!
        val id2 = json.parseToJsonElement(r2.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!

        // Both running concurrently
        assertTrue(supervisor.hasLiveWorkers())
        assertTrue(supervisor.hasLiveCloudWorker())

        gate1.complete(Unit)
        gate2.complete(Unit)

        supervisor.joinAll()

        assertFalse(supervisor.hasLiveWorkers())
        assertFalse(supervisor.hasLiveCloudWorker())

        val w1 = store.loadWorker(id1)
        val w2 = store.loadWorker(id2)
        assertNotNull(w1?.envelope)
        assertNotNull(w2?.envelope)
        assertEquals(RunPhase.Done, w1?.state)
        assertEquals(RunPhase.Done, w2?.state)

        assertEquals(320L, totalPromptTokens)
        assertEquals(120L, totalCompletionTokens)
    }

    @Test
    fun scopeQueueBehaviour_promotionAndSerialization() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        var clientIndex = 0

        val services = testServices(
            workerStore = store,
            configs = listOf(config),
            clientFactory = { _, _ ->
                clientIndex++
                if (clientIndex == 1) ControllableClient(gate1) else ControllableClient(gate2)
            },
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn(
            "{\"job\": \"module core\", \"brief\": \"core work\", \"model\": \"m1\", \"write_scope\": [\"src/core\"]}"
        ) as SpawnValidation.Valid
        val v2 = supervisor.validateSpawn(
            "{\"job\": \"file edit\", \"brief\": \"edit file\", \"model\": \"m1\", \"write_scope\": [\"src/core/item.kt\"]}"
        ) as SpawnValidation.Valid

        val r1 = supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        val r2 = supervisor.executeSpawn(v2.brief, v2.entry, v2.skill)

        val id1 = json.parseToJsonElement(r1.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!
        val id2 = json.parseToJsonElement(r2.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!

        val p2 = json.parseToJsonElement(r2.json).jsonObject
        assertEquals("waiting", p2["state"]?.jsonPrimitive?.content)
        assertEquals("same files as module core", p2["reason"]?.jsonPrimitive?.content)

        // When worker 1 finishes, worker 2 must be promoted to running
        gate1.complete(Unit)

        withTimeout(3_000) {
            supervisor.state.first { list ->
                val w2 = list.firstOrNull { it.id == id2 }
                w2?.state == RunPhase.Running
            }
        }

        gate2.complete(Unit)
        supervisor.joinAll()

        val w2Done = store.loadWorker(id2)
        assertEquals(RunPhase.Done, w2Done?.state)
    }

    @Test
    fun completionHold_holdsTurnUntilWorkerCompletes() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val gate = CompletableDeferred<Unit>()

        val services = testServices(
            workerStore = store,
            configs = listOf(config),
            clientFactory = { _, _ -> ControllableClient(gate) },
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn("{\"job\": \"slow task\", \"brief\": \"take time\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val r1 = supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        val id1 = json.parseToJsonElement(r1.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!

        assertTrue(supervisor.hasLiveWorkers())

        // Launch hold in background; release gate after a slight delay
        gate.complete(Unit)

        val events = supervisor.beforeCompletionHold("test-workspace")
        assertEquals(1, events.size)
        assertEquals(SubagentContext.RESULT_KIND, events[0].internalContext?.kind)
        assertTrue(events[0].internalContext?.content?.contains(id1) == true)

        assertFalse(supervisor.hasLiveWorkers())
        supervisor.joinAll()
    }

    @Test
    fun pauseAndResume_relaunchPausedFromTranscript() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        var clientCalls = 0

        val services = testServices(
            workerStore = store,
            configs = listOf(config),
            clientFactory = { _, _ ->
                clientCalls++
                if (clientCalls == 1) ControllableClient(gate1) else ControllableClient(gate2)
            },
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn("{\"job\": \"long worker\", \"brief\": \"pause me\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val r1 = supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        val id1 = json.parseToJsonElement(r1.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!

        // Pause all workers
        supervisor.pauseAll(RunPauseReason.NetworkLost)
        withTimeout(3_000) {
            supervisor.state.first { list ->
                list.firstOrNull { it.id == id1 }?.state == RunPhase.Paused
            }
        }

        gate1.complete(Unit)

        // Relaunch paused workers
        supervisor.relaunchPaused("test-run")
        gate2.complete(Unit)

        supervisor.joinAll()

        val wFinished = store.loadWorker(id1)
        assertEquals(RunPhase.Done, wFinished?.state)
    }

    @Test
    fun ownerScopedStop_stopsOnlyTargetWorker() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        var clientIndex = 0

        val services = testServices(
            workerStore = store,
            configs = listOf(config),
            clientFactory = { _, _ ->
                clientIndex++
                if (clientIndex == 1) ControllableClient(gate1) else ControllableClient(gate2)
            },
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn("{\"job\": \"worker 1\", \"brief\": \"brief 1\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val v2 = supervisor.validateSpawn("{\"job\": \"worker 2\", \"brief\": \"brief 2\", \"model\": \"m1\"}") as SpawnValidation.Valid

        val r1 = supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        val r2 = supervisor.executeSpawn(v2.brief, v2.entry, v2.skill)

        val id1 = json.parseToJsonElement(r1.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!
        val id2 = json.parseToJsonElement(r2.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!

        // Stop only worker 1
        supervisor.stop(id1)

        withTimeout(3_000) {
            supervisor.state.first { list ->
                val w1 = list.firstOrNull { it.id == id1 }
                w1?.state == RunPhase.Stopped
            }
        }

        // Worker 2 is still running
        val w2Current = supervisor.state.value.first { it.id == id2 }
        assertEquals(RunPhase.Running, w2Current.state)

        gate1.complete(Unit)
        gate2.complete(Unit)

        supervisor.joinAll()

        val w1Final = store.loadWorker(id1)
        val w2Final = store.loadWorker(id2)
        assertEquals(RunPhase.Stopped, w1Final?.state)
        assertEquals(RunPhase.Done, w2Final?.state)
    }

    @Test
    fun restoreAndUndoGuard_hasLiveWorkersReflectsLifecycle() = runBlocking {
        val local = localConfig("l1")
        val cloud = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val gate = CompletableDeferred<Unit>()

        val services = testServices(
            workerStore = store,
            configs = listOf(local, cloud),
            clientFactory = { _, _ -> ControllableClient(gate) },
            coordinatorConfig = local,
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        assertFalse(supervisor.hasLiveWorkers())
        assertFalse(supervisor.hasLiveCloudWorker())

        // Spawn a cloud worker while coordinator is local
        val v = supervisor.validateSpawn("{\"job\": \"cloud worker\", \"brief\": \"do work\", \"model\": \"m2\"}") as SpawnValidation.Valid
        supervisor.executeSpawn(v.brief, v.entry, v.skill)

        // Live workers active -> restore/undo would be refused
        assertTrue(supervisor.hasLiveWorkers())
        // Live cloud worker active -> triggers pause on connectivity lost even if coordinator is local
        assertTrue(supervisor.hasLiveCloudWorker())

        gate.complete(Unit)
        supervisor.joinAll()

        assertFalse(supervisor.hasLiveWorkers())
        assertFalse(supervisor.hasLiveCloudWorker())
    }
}
