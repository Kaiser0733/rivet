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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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
}

