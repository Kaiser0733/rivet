package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentApprovalGate
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.RunPauseReason
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.agent.Skill
import com.kaiser.rivet.agent.SkillCatalog
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
import com.kaiser.rivet.workspace.WorkspacePath
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentSupervisorTest {
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
        private val responseText: String = "Summary: Done.\nFindings:\n- fact\nOpen questions: none",
        private val onRequest: ((AgentRequest) -> Unit)? = null,
    ) : ProviderClient {
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun testConnection(): TestResult = TestResult(true, "ok")
        override suspend fun streamAgent(
            request: AgentRequest,
            onReasoning: (String) -> Unit,
            onDelta: (String) -> Unit,
        ): AgentResponse {
            onRequest?.invoke(request)
            gate?.await()
            onDelta(responseText)
            return AgentResponse(
                text = responseText,
                toolCalls = emptyList(),
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
        skillsCatalog: SkillCatalog? = null,
        coordinatorConfig: ProviderConfig? = configs.firstOrNull(),
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
        coordinatorConfig = { coordinatorConfig },
        skills = { skillsCatalog },
    )

    @Test
    fun killedWorkerIsFailedAndDoesNotHoldTheResumedCoordinator() = runBlocking {
        val cloud = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        store.saveWorker(WorkerRecord(
            id = "killed-worker", runId = "test-run", parentSessionId = "test-session",
            workspaceId = "test-workspace", job = "build", brief = "build it",
            configId = cloud.id, model = cloud.model, state = RunPhase.Running,
        ))
        val supervisor = SubagentSupervisor(testServices(store, listOf(cloud)), CoroutineScope(SupervisorJob()))

        supervisor.relaunchPaused("test-run")

        val failed = supervisor.state.value.single()
        assertEquals(RunPhase.Failed, failed.state)
        assertTrue(failed.error.orEmpty().contains("before this worker returned a result"))
        assertEquals(failed.state, store.loadWorker(failed.id)?.state)
        val results = withTimeout(1_000) { supervisor.beforeCompletionHold("test-workspace") }
        assertTrue(results.single().internalContext?.content.orEmpty().contains("worker_lost"))
    }

    @Test
    fun silentWorkerEndsWithAReasonAndReleasesTheCoordinator() = runBlocking {
        val cloud = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val services = testServices(store, listOf(cloud),
            clientFactory = { _, _ -> ControllableClient(gate = CompletableDeferred()) })
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()), workerIdleMs = 1_000)
        val spawn = supervisor.validateSpawn("""{"job":"build","brief":"build it"}""") as SpawnValidation.Valid
        supervisor.executeSpawn(spawn.brief, spawn.entry, spawn.skill)

        val results = withTimeout(4_000) { supervisor.beforeCompletionHold("test-workspace") }

        assertEquals(RunPhase.Failed, supervisor.state.value.single().state)
        assertTrue(results.single().internalContext?.content.orEmpty().contains("no activity for 1 seconds"))
    }

    @Test
    fun validateSpawnCatchesAllInvalidForms() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val services = testServices(store, listOf(config))
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        // Missing brief
        val res1 = supervisor.validateSpawn("{\"job\": \"job1\"}")
        assertTrue(res1 is SpawnValidation.Rejected)
        assertEquals("invalid_brief", (res1 as SpawnValidation.Rejected).code)

        // Unknown model
        val res2 = supervisor.validateSpawn("{\"job\": \"job1\", \"brief\": \"brief1\", \"model\": \"m99\"}")
        assertTrue(res2 is SpawnValidation.Rejected)
        assertEquals("invalid_model", (res2 as SpawnValidation.Rejected).code)

        // Invalid skill
        val res3 = supervisor.validateSpawn("{\"job\": \"job1\", \"brief\": \"brief1\", \"model\": \"m1\", \"skill\": \"missing\"}")
        assertTrue(res3 is SpawnValidation.Rejected)
        assertEquals("invalid_skill", (res3 as SpawnValidation.Rejected).code)

        // Non-existent retry_of
        val res4 = supervisor.validateSpawn("{\"job\": \"job1\", \"brief\": \"brief1\", \"model\": \"m1\", \"retry_of\": \"none\"}")
        assertTrue(res4 is SpawnValidation.Rejected)
        assertEquals("invalid_retry", (res4 as SpawnValidation.Rejected).code)
    }

    @Test
    fun omittedModelUsesTheFirstTickedPoolEntry() = runBlocking {
        val config = cloudConfig("c1")
        val services = testServices(WorkerSessionStore.inMemory(), listOf(config))
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val result = supervisor.validateSpawn("""{"job":"job1","brief":"brief1"}""")

        val valid = requireNotNull(result as? SpawnValidation.Valid)
        assertEquals("m1", valid.entry.alias)
        assertEquals("c1", valid.entry.config.id)
    }

    @Test
    fun spawnsAreCappedAtTwentyPerRun() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        repeat(SubagentScheduler.MAX_SPAWNS_PER_RUN) { index ->
            store.saveWorker(WorkerRecord(
                id = "previous-$index",
                runId = "test-run",
                parentSessionId = "test-session",
                workspaceId = "test-workspace",
                job = "previous $index",
                brief = "done",
                configId = config.id,
                model = config.model,
                state = RunPhase.Done,
                createdAt = index.toLong(),
            ))
        }
        val services = testServices(store, listOf(config))
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))
        val valid = supervisor.validateSpawn(
            """{"job":"one too many","brief":"do it","model":"m1"}""",
        ) as SpawnValidation.Valid

        val result = supervisor.executeSpawn(valid.brief, valid.entry, valid.skill)

        assertTrue(result.error)
        assertEquals("spawn_limit", json.parseToJsonElement(result.json).jsonObject["error"]?.jsonPrimitive?.content)
        assertTrue(json.parseToJsonElement(result.json).jsonObject["required_action"]
            ?.jsonPrimitive?.content.orEmpty().contains("20"))
    }

    @Test
    fun executeSpawnAdmitsFirstTwoAndQueuesThirdUntilSlotFrees() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val gate3 = CompletableDeferred<Unit>()
        var clientCount = 0

        val services = testServices(
            workerStore = store,
            configs = listOf(config),
            clientFactory = { _, _ ->
                clientCount++
                when (clientCount) {
                    1 -> ControllableClient(gate1)
                    2 -> ControllableClient(gate2)
                    else -> ControllableClient(gate3)
                }
            },
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn("{\"job\": \"worker 1\", \"brief\": \"do 1\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val v2 = supervisor.validateSpawn("{\"job\": \"worker 2\", \"brief\": \"do 2\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val v3 = supervisor.validateSpawn("{\"job\": \"worker 3\", \"brief\": \"do 3\", \"model\": \"m1\"}") as SpawnValidation.Valid

        val r1 = supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        val r2 = supervisor.executeSpawn(v2.brief, v2.entry, v2.skill)
        val r3 = supervisor.executeSpawn(v3.brief, v3.entry, v3.skill)

        assertFalse(r1.error)
        assertFalse(r2.error)
        assertFalse(r3.error)

        assertEquals("running", json.parseToJsonElement(r1.json).jsonObject["state"]?.jsonPrimitive?.content)
        assertEquals("running", json.parseToJsonElement(r2.json).jsonObject["state"]?.jsonPrimitive?.content)
        assertEquals("waiting", json.parseToJsonElement(r3.json).jsonObject["state"]?.jsonPrimitive?.content)
        val worker3Id = json.parseToJsonElement(r3.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!

        // Complete worker 1 -> worker 3 automatically promotes to running
        gate1.complete(Unit)

        withTimeout(3_000) {
            supervisor.state.first { list ->
                val w3 = list.firstOrNull { it.id == worker3Id }
                w3?.state == RunPhase.Running
            }
        }

        // Clean up remaining gates
        gate2.complete(Unit)
        gate3.complete(Unit)
        supervisor.joinAll()
    }

    @Test
    fun overlappingWriteScopesQueueEvenWithFreeSlot() = runBlocking {
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
            "{\"job\": \"auth refactor\", \"brief\": \"auth\", \"model\": \"m1\", \"write_scope\": [\"src/auth\"]}"
        ) as SpawnValidation.Valid
        val v2 = supervisor.validateSpawn(
            "{\"job\": \"login tweak\", \"brief\": \"login\", \"model\": \"m1\", \"write_scope\": [\"src/auth/login.kt\"]}"
        ) as SpawnValidation.Valid

        val r1 = supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        val r2 = supervisor.executeSpawn(v2.brief, v2.entry, v2.skill)

        assertEquals("running", json.parseToJsonElement(r1.json).jsonObject["state"]?.jsonPrimitive?.content)
        val p2 = json.parseToJsonElement(r2.json).jsonObject
        assertEquals("waiting", p2["state"]?.jsonPrimitive?.content)
        assertEquals("same files as auth refactor", p2["reason"]?.jsonPrimitive?.content)

        val worker2Id = p2["worker_id"]?.jsonPrimitive?.content!!

        // Release worker 1
        gate1.complete(Unit)

        // Worker 2 promotes
        withTimeout(3_000) {
            supervisor.state.first { list ->
                val w2 = list.firstOrNull { it.id == worker2Id }
                w2?.state == RunPhase.Running
            }
        }

        gate2.complete(Unit)
        supervisor.joinAll()
    }

    @Test
    fun localModelEnforcesSingleSlotAndRefusesSecondLocalWorker() = runBlocking {
        val local = localConfig("l1")
        val store = WorkerSessionStore.inMemory()
        val gate = CompletableDeferred<Unit>()
        val services = testServices(
            workerStore = store,
            configs = listOf(local),
            clientFactory = { _, _ -> ControllableClient(gate) },
            coordinatorConfig = cloudConfig("coord"),
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn("{\"job\": \"local 1\", \"brief\": \"do 1\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val v2 = supervisor.validateSpawn("{\"job\": \"local 2\", \"brief\": \"do 2\", \"model\": \"m1\"}") as SpawnValidation.Valid

        val r1 = supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        val r2 = supervisor.executeSpawn(v2.brief, v2.entry, v2.skill)

        assertFalse(r1.error)
        assertTrue(r2.error)
        val p2 = json.parseToJsonElement(r2.json).jsonObject
        assertEquals("spawn_refused", p2["error"]?.jsonPrimitive?.content)
        assertTrue(p2["required_action"]?.jsonPrimitive?.content?.contains("one local model at a time") == true)

        gate.complete(Unit)
        supervisor.joinAll()
    }

    @Test
    fun userStopRunningAndQueuedWorker() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val gate3 = CompletableDeferred<Unit>()

        val services = testServices(
            workerStore = store,
            configs = listOf(config),
            clientFactory = { _, _ -> ControllableClient(gate1) },
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn("{\"job\": \"job 1\", \"brief\": \"do 1\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val v2 = supervisor.validateSpawn("{\"job\": \"job 2\", \"brief\": \"do 2\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val v3 = supervisor.validateSpawn("{\"job\": \"job 3\", \"brief\": \"do 3\", \"model\": \"m1\"}") as SpawnValidation.Valid

        val r1 = supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        val r2 = supervisor.executeSpawn(v2.brief, v2.entry, v2.skill)
        val r3 = supervisor.executeSpawn(v3.brief, v3.entry, v3.skill)

        val id1 = json.parseToJsonElement(r1.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!
        val id3 = json.parseToJsonElement(r3.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!

        // Stop queued worker 3
        supervisor.stop(id3)
        val w3 = supervisor.state.value.first { it.id == id3 }
        assertEquals(RunPhase.Stopped, w3.state)

        // Stop running worker 1
        supervisor.stop(id1)
        withTimeout(3_000) {
            supervisor.state.first { list ->
                val w1 = list.firstOrNull { it.id == id1 }
                w1?.state == RunPhase.Stopped
            }
        }

        gate1.complete(Unit)
        supervisor.joinAll()
    }

    @Test
    fun beforeCompletionHoldAwaitsAndDeliversResultsOnce() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val services = testServices(store, listOf(config))
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn("{\"job\": \"quick job\", \"brief\": \"do it\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val r1 = supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        val id1 = json.parseToJsonElement(r1.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!

        val events1 = supervisor.beforeCompletionHold("test-workspace")
        assertEquals(1, events1.size)
        assertEquals(SubagentContext.RESULT_KIND, events1.first().internalContext?.kind)
        assertTrue(events1.first().internalContext?.content?.contains(id1) == true)

        // Second hold call has no new undelivered results
        val events2 = supervisor.beforeCompletionHold("test-workspace")
        assertTrue(events2.isEmpty())
    }

    @Test
    fun retryOfValidationAllowsOnceForFailedWorker() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val services = testServices(store, listOf(config))
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        // Create a failed worker record directly in store
        val failedWorker = WorkerRecord(
            id = "failed-worker-1",
            runId = "test-run",
            parentSessionId = "test-session",
            workspaceId = "test-workspace",
            job = "flaky task",
            brief = "do task",
            configId = "c1",
            model = "gpt-4o-mini",
            state = RunPhase.Failed,
            error = "network timeout",
        )
        store.saveWorker(failedWorker)

        // First retry is valid
        val retry1 = supervisor.validateSpawn(
            "{\"job\": \"retry flaky\", \"brief\": \"try again\", \"model\": \"m1\", \"retry_of\": \"failed-worker-1\"}"
        )
        assertTrue(retry1 is SpawnValidation.Valid)
        val r1 = supervisor.executeSpawn((retry1 as SpawnValidation.Valid).brief, retry1.entry, retry1.skill)
        assertFalse(r1.error)

        // Second retry of same worker is rejected
        val retry2 = supervisor.validateSpawn(
            "{\"job\": \"retry flaky again\", \"brief\": \"third time\", \"model\": \"m1\", \"retry_of\": \"failed-worker-1\"}"
        )
        assertTrue(retry2 is SpawnValidation.Rejected)
        assertEquals("invalid_retry", (retry2 as SpawnValidation.Rejected).code)
        assertTrue((retry2 as SpawnValidation.Rejected).detail.contains("already been retried once"))

        supervisor.joinAll()
    }

    @Test
    fun skillLookupMatchesExactCaseInsensitiveAndRejectsNonModelInvocable() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val capturedRequests = mutableListOf<AgentRequest>()
        val auditSkill = Skill(
            name = "CodeAudit",
            description = "Audit code",
            body = "Check every auth boundary carefully.",
            disableModelInvocation = false,
        )
        val hiddenSkill = Skill(
            name = "InternalOnly",
            description = "User only",
            body = "Manual only.",
            disableModelInvocation = true,
        )
        val catalog = SkillCatalog(
            skills = listOf(auditSkill, hiddenSkill),
            errors = emptyList(),
            digest = "digest-1",
        )
        val services = testServices(
            workerStore = store,
            configs = listOf(config),
            clientFactory = { _, _ -> ControllableClient(onRequest = { capturedRequests += it }) },
            skillsCatalog = catalog,
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val rejectedHidden = supervisor.validateSpawn(
            """{"job":"secret","brief":"do it","model":"m1","skill":"InternalOnly"}"""
        )
        assertTrue(rejectedHidden is SpawnValidation.Rejected)
        assertEquals("invalid_skill", (rejectedHidden as SpawnValidation.Rejected).code)

        val validInsensitive = supervisor.validateSpawn(
            """{"job":"audit","brief":"check auth","model":"m1","skill":"codeaudit"}"""
        ) as SpawnValidation.Valid
        assertEquals("CodeAudit", validInsensitive.skill?.name)

        val spawned = supervisor.executeSpawn(validInsensitive.brief, validInsensitive.entry, validInsensitive.skill)
        assertFalse(spawned.error)
        supervisor.joinAll()

        assertTrue(capturedRequests.isNotEmpty())
        val promptTexts = capturedRequests.first().messages.joinToString("\n") { it.text }
        assertTrue(promptTexts.contains("Check every auth boundary carefully."))
    }

    @Test
    fun fileScopeIsRegisteredWithJobWhileRunningAndReleasedOnCompletion() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val gate = CompletableDeferred<Unit>()
        val services = testServices(
            workerStore = store,
            configs = listOf(config),
            clientFactory = { _, _ -> ControllableClient(gate) },
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val valid = supervisor.validateSpawn(
            """{"job":"core refactor","brief":"update core","model":"m1","write_scope":["src/core"]}"""
        ) as SpawnValidation.Valid
        val spawned = supervisor.executeSpawn(valid.brief, valid.entry, valid.skill)
        val workerId = json.parseToJsonElement(spawned.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!

        val activeScope = services.checkpointGate.scopeRegistry.scopeOf(workerId)
        assertNotNull(activeScope)
        assertEquals("core refactor", activeScope!!.job)
        assertEquals(setOf(WorkspacePath.parse("src/core")), activeScope.paths)
        assertEquals(
            "${FileScopeRegistry.OWNED_BY_WORKER_PREFIX}core refactor",
            services.checkpointGate.scopeRegistry.checkWrite(
                FileScopeRegistry.COORDINATOR,
                listOf(WorkspacePath.parse("src/core/Main.kt")),
            ),
        )

        gate.complete(Unit)
        supervisor.joinAll()

        assertNull(services.checkpointGate.scopeRegistry.scopeOf(workerId))
        assertNull(
            services.checkpointGate.scopeRegistry.checkWrite(
                FileScopeRegistry.COORDINATOR,
                listOf(WorkspacePath.parse("src/core/Main.kt")),
            ),
        )
    }

    @Test
    fun pauseAllHaltsDrainQueueAndMovesQueuedWorkersToPaused() = runBlocking {
        val config = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val gate3 = CompletableDeferred<Unit>()
        val resumedGate1 = CompletableDeferred<Unit>()
        val resumedGate2 = CompletableDeferred<Unit>()
        val resumedGate3 = CompletableDeferred<Unit>()
        var clientCount = 0

        val services = testServices(
            workerStore = store,
            configs = listOf(config),
            clientFactory = { _, _ ->
                clientCount++
                when (clientCount) {
                    1 -> ControllableClient(gate1)
                    2 -> ControllableClient(gate2)
                    3 -> ControllableClient(resumedGate1)
                    4 -> ControllableClient(resumedGate2)
                    else -> ControllableClient(resumedGate3)
                }
            },
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn("{\"job\": \"worker 1\", \"brief\": \"do 1\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val v2 = supervisor.validateSpawn("{\"job\": \"worker 2\", \"brief\": \"do 2\", \"model\": \"m1\"}") as SpawnValidation.Valid
        val v3 = supervisor.validateSpawn("{\"job\": \"worker 3\", \"brief\": \"do 3\", \"model\": \"m1\"}") as SpawnValidation.Valid

        supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        supervisor.executeSpawn(v2.brief, v2.entry, v2.skill)
        supervisor.executeSpawn(v3.brief, v3.entry, v3.skill)

        withTimeout(3_000) {
            supervisor.state.first { list ->
                list.count { it.state == RunPhase.Running } == 2 &&
                    list.count { it.state == RunPhase.Queued } == 1
            }
        }

        supervisor.pauseAll(RunPauseReason.NetworkLost)

        withTimeout(3_000) {
            supervisor.state.first { list ->
                list.size == 3 && list.all { it.state == RunPhase.Paused }
            }
        }

        // Cancelled workers ran drainQueue() in finally: queued worker must NOT have started while paused.
        assertEquals(2, clientCount)

        gate1.complete(Unit)
        gate2.complete(Unit)
        gate3.complete(Unit)
        resumedGate1.complete(Unit)
        resumedGate2.complete(Unit)
        resumedGate3.complete(Unit)

        supervisor.relaunchPaused("test-run")
        supervisor.joinAll()

        withTimeout(3_000) {
            supervisor.state.first { list -> list.size == 3 && list.all { it.state == RunPhase.Done } }
        }
        Unit
    }

    @Test
    fun drainQueueMarksRefusedQueuedWorkerAsFailedAndRemovesFromQueue() = runBlocking {
        val cloud = cloudConfig("c1")
        val local = localConfig("l1")
        val store = WorkerSessionStore.inMemory()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val gate4 = CompletableDeferred<Unit>()
        var cloudClients = 0

        val services = testServices(
            workerStore = store,
            configs = listOf(cloud, local),
            coordinatorConfig = cloud,
            clientFactory = { cfg, _ ->
                if (cfg.id == "l1") {
                    ControllableClient(gate4)
                } else {
                    if (cloudClients++ == 0) ControllableClient(gate1) else ControllableClient(gate2)
                }
            },
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn(
            """{"job":"cloud 1","brief":"work a","model":"m1","write_scope":["src/a"]}"""
        ) as SpawnValidation.Valid
        val v2 = supervisor.validateSpawn(
            """{"job":"cloud 2","brief":"work b","model":"m1"}"""
        ) as SpawnValidation.Valid
        val v3 = supervisor.validateSpawn(
            """{"job":"queued local","brief":"work local on a","model":"m2","write_scope":["src/a"]}"""
        ) as SpawnValidation.Valid

        supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        supervisor.executeSpawn(v2.brief, v2.entry, v2.skill)
        val r3 = supervisor.executeSpawn(v3.brief, v3.entry, v3.skill)
        val id3 = json.parseToJsonElement(r3.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!

        withTimeout(3_000) {
            supervisor.state.first { list ->
                list.firstOrNull { it.id == id3 }?.state == RunPhase.Queued
            }
        }

        // Free cloud 2's slot while cloud 1 still holds src/a, then occupy the single local slot with v4.
        gate2.complete(Unit)
        withTimeout(3_000) {
            supervisor.state.first { list -> list.count { it.state == RunPhase.Running } == 1 }
        }

        val v4 = supervisor.validateSpawn(
            """{"job":"local blocker","brief":"work local on b","model":"m2","write_scope":["src/b"]}"""
        ) as SpawnValidation.Valid
        supervisor.executeSpawn(v4.brief, v4.entry, v4.skill)

        withTimeout(3_000) {
            supervisor.state.first { list -> list.count { it.state == RunPhase.Running } == 2 }
        }

        // Complete cloud 1: drainQueue() checks v3 (local) while v4 (local) is running -> Admission.Refuse.
        gate1.complete(Unit)

        withTimeout(3_000) {
            supervisor.state.first { list ->
                list.firstOrNull { it.id == id3 }?.state == RunPhase.Failed
            }
        }

        gate4.complete(Unit)
        supervisor.joinAll()

        assertFalse(supervisor.hasLiveWorkers())
        val refused = store.loadWorker(id3)
        assertEquals(RunPhase.Failed, refused?.state)
        assertNotNull(refused?.envelope)
    }

    @Test
    fun concurrentExecuteSpawnUnderQueueLockNeverExceedsMaxRunningSlots() = runBlocking {
        val cloud = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val gate3 = CompletableDeferred<Unit>()
        val worker1Started = CompletableDeferred<Unit>()
        val activeClients = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val insideConfigs = AtomicInteger(0)
        val twoInsideConfigs = CompletableDeferred<Unit>()
        val twoActive = CompletableDeferred<Unit>()
        val barrierArmed = AtomicBoolean(false)

        val providerSource = object : ProviderRuntimeSource {
            override suspend fun load() = ProviderRuntimeResult.Ready(cloud, "fake-key")
            override suspend fun loadFor(configId: String) = ProviderRuntimeResult.Ready(cloud, "fake-key")
            override suspend fun configs(): List<ProviderConfig> {
                if (barrierArmed.get()) {
                    if (insideConfigs.incrementAndGet() >= 2) {
                        twoInsideConfigs.complete(Unit)
                    }
                    withTimeoutOrNull(150) { twoInsideConfigs.await() }
                }
                return listOf(cloud)
            }
        }

        val clientCalls = AtomicInteger(0)
        val services = RunServices(
            workspaceId = "test-workspace",
            runId = "test-run",
            parentSessionId = "test-session",
            workerStore = store,
            providerSource = providerSource,
            clientFactory = { _, _ ->
                val idx = clientCalls.incrementAndGet()
                val gate = when (idx) {
                    1 -> gate1
                    2 -> gate2
                    else -> gate3
                }
                object : ProviderClient {
                    override suspend fun listModels() = emptyList<ModelInfo>()
                    override suspend fun testConnection() = TestResult(true, "ok")
                    override suspend fun streamAgent(
                        request: AgentRequest,
                        onReasoning: (String) -> Unit,
                        onDelta: (String) -> Unit,
                    ): AgentResponse {
                        val now = activeClients.incrementAndGet()
                        maxConcurrent.updateAndGet { prev -> maxOf(prev, now) }
                        if (now >= 2) twoActive.complete(Unit)
                        if (idx == 1) worker1Started.complete(Unit)
                        try {
                            gate.await()
                            onDelta("Done.")
                            return AgentResponse(text = "Summary: Done.\nFindings:\n- ok\nOpen questions: none")
                        } finally {
                            activeClients.decrementAndGet()
                        }
                    }
                }
            },
            approvals = AgentApprovalGate(),
            checkpointGate = RunCheckpointGate(FakeCheckpointBackend()),
            poolState = { SubagentPoolState(allowAgentChoice = true, entries = listOf(PoolEntry(cloud.id, cloud.model))) },
            coordinatorConfig = { cloud },
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val v1 = supervisor.validateSpawn("""{"job":"w1","brief":"do 1","model":"m1"}""") as SpawnValidation.Valid
        val v2 = supervisor.validateSpawn("""{"job":"w2","brief":"do 2","model":"m1"}""") as SpawnValidation.Valid
        val v3 = supervisor.validateSpawn("""{"job":"w3","brief":"do 3","model":"m1"}""") as SpawnValidation.Valid

        supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        withTimeout(3_000) { worker1Started.await() }

        barrierArmed.set(true)
        val d2 = async(Dispatchers.Default) { supervisor.executeSpawn(v2.brief, v2.entry, v2.skill) }
        val d3 = async(Dispatchers.Default) { supervisor.executeSpawn(v3.brief, v3.entry, v3.skill) }
        d2.await()
        d3.await()
        withTimeout(3_000) { twoActive.await() }
        barrierArmed.set(false)

        gate1.complete(Unit)
        gate2.complete(Unit)
        gate3.complete(Unit)
        supervisor.joinAll()

        withTimeout(3_000) {
            supervisor.state.first { list -> list.size == 3 && list.all { it.state == RunPhase.Done } }
        }
        assertEquals(SubagentScheduler.MAX_RUNNING, maxConcurrent.get())
    }

    @Test
    fun awaitTerminalReturnsImmediatelyWithoutWaitingWhenIdsContainUnknownWorker() = runBlocking {
        val cloud = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val services = testServices(
            workerStore = store,
            configs = listOf(cloud),
        )
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))

        val onlyUnknown = withTimeout(1_000) {
            supervisor.awaitTerminal(listOf("missing-worker"), timeoutMs = 10_000L)
        }
        val onlyUnknownObj = json.parseToJsonElement(onlyUnknown).jsonObject
        assertEquals(0, onlyUnknownObj["workers"]?.jsonArray?.size)
        assertEquals(
            listOf("missing-worker"),
            onlyUnknownObj["unknown_workers"]?.jsonArray?.map { it.jsonPrimitive.content },
        )

        val v1 = supervisor.validateSpawn("""{"job":"fast","brief":"done quickly","model":"m1"}""") as SpawnValidation.Valid
        val spawned = supervisor.executeSpawn(v1.brief, v1.entry, v1.skill)
        val id1 = json.parseToJsonElement(spawned.json).jsonObject["worker_id"]?.jsonPrimitive?.content!!

        val mixed = withTimeout(3_000) {
            supervisor.awaitTerminal(listOf(id1, "missing-2"), timeoutMs = 10_000L)
        }
        val mixedObj = json.parseToJsonElement(mixed).jsonObject
        assertEquals(1, mixedObj["workers"]?.jsonArray?.size)
        assertEquals(
            listOf("missing-2"),
            mixedObj["unknown_workers"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun relaunchPausedRespectsMaxRunningBeginsBoundariesAndJoinAllAwaitsQueued() = runBlocking {
        val cloud = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val boundaryCalls = AtomicInteger(0)
        val backend = object : CheckpointBackend {
            override suspend fun beginCheckpoint(workspaceId: String, runId: String?, sessionId: String?) = "cp-1"
            override suspend fun finishCheckpoint(workspaceId: String, id: String) = true
            override suspend fun recordCheckpointPost(workspaceId: String, id: String) {}
            override suspend fun checkpointMatchesPost(workspaceId: String, id: String) = true
            override suspend fun beginRunCheckpoint(
                workspaceId: String,
                runId: String,
                sequence: Int,
                summary: String,
                allowDirty: Boolean,
            ): String {
                boundaryCalls.incrementAndGet()
                return "b-${boundaryCalls.get()}"
            }
            override suspend fun finishRunCheckpoint(workspaceId: String, id: String) {}
        }

        val gate1 = CompletableDeferred<Unit>()
        val gate2 = CompletableDeferred<Unit>()
        val gate3 = CompletableDeferred<Unit>()
        val activeClients = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val started = AtomicInteger(0)
        val twoRunning = CompletableDeferred<Unit>()

        val services = RunServices(
            workspaceId = "test-workspace",
            runId = "test-run",
            parentSessionId = "test-session",
            workerStore = store,
            providerSource = FakeProviderSource(listOf(cloud)),
            clientFactory = { _, _ ->
                val idx = started.incrementAndGet()
                val gate = when (idx) {
                    1 -> gate1
                    2 -> gate2
                    else -> gate3
                }
                object : ProviderClient {
                    override suspend fun listModels() = emptyList<ModelInfo>()
                    override suspend fun testConnection() = TestResult(true, "ok")
                    override suspend fun streamAgent(
                        request: AgentRequest,
                        onReasoning: (String) -> Unit,
                        onDelta: (String) -> Unit,
                    ): AgentResponse {
                        val concurrent = activeClients.incrementAndGet()
                        maxConcurrent.updateAndGet { prev -> maxOf(prev, concurrent) }
                        if (concurrent >= 2) twoRunning.complete(Unit)
                        try {
                            gate.await()
                            onDelta("Done.")
                            return AgentResponse(text = "Summary: Done.\nFindings:\n- ok\nOpen questions: none")
                        } finally {
                            activeClients.decrementAndGet()
                        }
                    }
                }
            },
            approvals = AgentApprovalGate(),
            checkpointGate = RunCheckpointGate(backend),
            poolState = { SubagentPoolState(allowAgentChoice = true, entries = listOf(PoolEntry(cloud.id, cloud.model))) },
            coordinatorConfig = { cloud },
        )

        for (i in 1..3) {
            store.saveWorker(
                WorkerRecord(
                    id = "w-paused-$i",
                    runId = "test-run",
                    parentSessionId = "test-session",
                    workspaceId = "test-workspace",
                    job = "job-$i",
                    brief = "do $i",
                    configId = cloud.id,
                    model = cloud.model,
                    modelLabel = "OpenAI",
                    state = RunPhase.Paused,
                    pauseReason = RunPauseReason.NetworkLost,
                    createdAt = i.toLong(),
                ),
            )
        }

        services.checkpointGate.beginTurn("test-workspace", "test-run", "test-session")
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))
        supervisor.relaunchPaused("test-run")

        withTimeout(3_000) {
            supervisor.state.first { list ->
                list.count { it.state == RunPhase.Running } == 2 &&
                    list.count { it.state == RunPhase.Queued } == 1
            }
            twoRunning.await()
        }
        assertEquals(SubagentScheduler.MAX_RUNNING, maxConcurrent.get())
        assertEquals(2, boundaryCalls.get())

        val joinJob = launch { supervisor.joinAll() }
        gate1.complete(Unit)
        gate2.complete(Unit)
        withTimeout(3_000) {
            supervisor.state.first { list -> list.firstOrNull { it.id == "w-paused-3" }?.state == RunPhase.Running }
        }
        assertFalse(joinJob.isCompleted)

        gate3.complete(Unit)
        joinJob.join()

        assertEquals(SubagentScheduler.MAX_RUNNING, maxConcurrent.get())
        assertEquals(3, boundaryCalls.get())
        assertTrue(supervisor.state.value.all { it.state == RunPhase.Done })
    }

    @Test
    fun retryOfRejectsSecondRetryAcrossSeparateTurnSupervisors() = runBlocking {
        val cloud = cloudConfig("c1")
        val store = WorkerSessionStore.inMemory()
        val failedId = "failed-worker-cross-turn"
        store.saveWorker(
            WorkerRecord(
                id = failedId,
                runId = "test-run",
                parentSessionId = "test-session",
                workspaceId = "test-workspace",
                job = "flaky task",
                brief = "do task",
                configId = cloud.id,
                model = cloud.model,
                state = RunPhase.Failed,
                error = "network timeout",
            ),
        )

        val turn1Services = testServices(
            workerStore = store,
            configs = listOf(cloud),
        )
        val turn1Supervisor = SubagentSupervisor(turn1Services, CoroutineScope(SupervisorJob()))

        val retry1 = turn1Supervisor.validateSpawn(
            """{"job":"retry 1","brief":"retry first","model":"m1","retry_of":"$failedId"}"""
        ) as SpawnValidation.Valid
        turn1Supervisor.executeSpawn(retry1.brief, retry1.entry, retry1.skill)
        turn1Supervisor.joinAll()

        // New turn (new supervisor instance on same session & WorkerSessionStore)
        val turn2Services = RunServices(
            workspaceId = "test-workspace",
            runId = "test-run-2",
            parentSessionId = "test-session",
            workerStore = store,
            providerSource = FakeProviderSource(listOf(cloud)),
            clientFactory = { _, _ -> ControllableClient() },
            approvals = AgentApprovalGate(),
            checkpointGate = RunCheckpointGate(FakeCheckpointBackend()),
            poolState = { SubagentPoolState(allowAgentChoice = true, entries = listOf(PoolEntry(cloud.id, cloud.model))) },
            coordinatorConfig = { cloud },
        )
        val turn2Supervisor = SubagentSupervisor(turn2Services, CoroutineScope(SupervisorJob()))

        val secondRetry = turn2Supervisor.validateSpawn(
            """{"job":"retry 2","brief":"second retry","model":"m1","retry_of":"$failedId"}"""
        )
        assertTrue(secondRetry is SpawnValidation.Rejected)
        assertEquals("invalid_retry", (secondRetry as SpawnValidation.Rejected).code)
        assertTrue(secondRetry.detail.contains("already been retried once"))
    }
}

