package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentApprovalGate
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolEffect
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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

class SubagentToolsTest {
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

    private class FakeClient : ProviderClient {
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun testConnection(): TestResult = TestResult(true, "ok")
        override suspend fun streamAgent(
            request: AgentRequest,
            onReasoning: (String) -> Unit,
            onDelta: (String) -> Unit,
        ): AgentResponse {
            onDelta("I finished the job.")
            return AgentResponse(
                text = "Summary: I finished the job.\nFindings:\n- All done\nOpen questions: none",
                toolCalls = emptyList(),
            )
        }
    }

    private fun testServices(
        workerStore: WorkerSessionStore,
        config: ProviderConfig,
    ): RunServices = RunServices(
        workspaceId = "test-workspace",
        runId = "test-run",
        parentSessionId = "test-session",
        workerStore = workerStore,
        providerSource = FakeProviderSource(listOf(config)),
        clientFactory = { _, _ -> FakeClient() },
        approvals = AgentApprovalGate(),
        checkpointGate = RunCheckpointGate(FakeCheckpointBackend()),
        poolState = {
            SubagentPoolState(
                allowAgentChoice = true,
                entries = listOf(PoolEntry(config.id, config.model)),
            )
        },
        coordinatorConfig = { config },
    )

    @Test
    fun definitionsListBothToolsAndDepthOneRulesApply() {
        val names = SubagentTools.definitions.map { it.name }
        assertEquals(listOf(SubagentTools.SPAWN_TOOL, SubagentTools.AWAIT_TOOL), names)
        val spawn = SubagentTools.definitions.first { it.name == SubagentTools.SPAWN_TOOL }
        assertTrue(spawn.description.contains("worker_id"))
        val await = SubagentTools.definitions.first { it.name == SubagentTools.AWAIT_TOOL }
        assertTrue(await.description.contains("timeout_s"))
    }

    @Test
    fun spawnRejectsInvalidArguments() = runBlocking {
        val config = ProviderConfig(
            id = "c1",
            type = ProviderType.OpenAi,
            name = "OpenAI",
            baseUrl = "https://example.com",
            model = "gpt-4o-mini",
        )
        val store = WorkerSessionStore.inMemory()
        val services = testServices(store, config)
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))
        val tools = SubagentTools(supervisor, store) { "test-session" }

        val call = AgentToolCall(
            id = "call-1",
            name = SubagentTools.SPAWN_TOOL,
            arguments = "not valid json",
        )
        val prepared = tools.prepare(call)
        assertNotNull(prepared)
        assertEquals(AgentToolEffect.ReadOnly, prepared!!.effect)
        val result = prepared.execute()
        assertTrue(result.error)
        val parsed = json.parseToJsonElement(result.content).jsonObject
        assertEquals("invalid_brief", parsed["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun spawnFirstTimeRequiresApprovalAndGrantsSessionOnExecution() = runBlocking {
        val config = ProviderConfig(
            id = "c1",
            type = ProviderType.OpenAi,
            name = "OpenAI",
            baseUrl = "https://example.com",
            model = "gpt-4o-mini",
        )
        val store = WorkerSessionStore.inMemory()
        val services = testServices(store, config)
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))
        val sessionId = "session-123"
        val tools = SubagentTools(supervisor, store) { sessionId }

        assertFalse(store.hasSpawnGrant(sessionId))

        val spawnArgs = buildJsonObject {
            put("job", "research auth")
            put("brief", "Inspect src/auth and report back.")
            put("model", "m1")
        }.toString()
        val call = AgentToolCall(
            id = "call-1",
            name = SubagentTools.SPAWN_TOOL,
            arguments = spawnArgs,
        )

        // First spawn has approval request
        val prepared1 = tools.prepare(call)
        assertNotNull(prepared1)
        assertEquals(AgentToolEffect.Delegation, prepared1!!.effect)
        assertNotNull(prepared1.approval)
        assertEquals(SubagentTools.SPAWN_APPROVAL_TITLE, prepared1.approval!!.title)

        // Execute approves and grants
        val result1 = prepared1.execute()
        assertFalse(result1.error)
        assertTrue(store.hasSpawnGrant(sessionId))
        val parsed1 = json.parseToJsonElement(result1.content).jsonObject
        assertEquals("running", parsed1["state"]?.jsonPrimitive?.content)

        // Second spawn in same session has approval == null (auto-authorized)
        val call2 = AgentToolCall(
            id = "call-2",
            name = SubagentTools.SPAWN_TOOL,
            arguments = spawnArgs,
        )
        val prepared2 = tools.prepare(call2)
        assertNotNull(prepared2)
        assertNull(prepared2!!.approval)
        assertEquals(AgentToolEffect.Delegation, prepared2.effect)
    }

    @Test
    fun awaitValidatesArgumentsAndExecutesTerminalWait() = runBlocking {
        val config = ProviderConfig(
            id = "c1",
            type = ProviderType.OpenAi,
            name = "OpenAI",
            baseUrl = "https://example.com",
            model = "gpt-4o-mini",
        )
        val store = WorkerSessionStore.inMemory()
        val services = testServices(store, config)
        val supervisor = SubagentSupervisor(services, CoroutineScope(SupervisorJob()))
        val tools = SubagentTools(supervisor, store) { "session-1" }

        // Invalid timeout > 600
        val badTimeoutCall = AgentToolCall(
            id = "call-bad",
            name = SubagentTools.AWAIT_TOOL,
            arguments = "{\"timeout_s\": 700}",
        )
        val preparedBad = tools.prepare(badTimeoutCall)
        assertNotNull(preparedBad)
        val badResult = preparedBad!!.execute()
        assertTrue(badResult.error)

        // Valid await
        val validCall = AgentToolCall(
            id = "call-valid",
            name = SubagentTools.AWAIT_TOOL,
            arguments = "{\"timeout_s\": 0}",
        )
        val preparedValid = tools.prepare(validCall)
        assertNotNull(preparedValid)
        assertEquals(ResultEnvelope.MAX_AWAIT_BYTES, preparedValid!!.resultContentLimitBytes)
        val validResult = preparedValid.execute()
        assertFalse(validResult.error)
        val parsed = json.parseToJsonElement(validResult.content).jsonObject
        assertNotNull(parsed["workers"])
    }
}

