package com.kaiser.rivet.chat

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.provider.ProviderClient
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.storage.CodingSessions
import com.kaiser.rivet.subagent.WorkerRecord
import com.kaiser.rivet.subagent.WorkerSessionStore
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WorkerPromotionTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var sessions: CodingSessions
    private lateinit var workers: WorkerSessionStore
    private lateinit var vm: ChatViewModel
    private lateinit var worker: WorkerRecord

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app.deleteDatabase("coding-sessions.db")
        sessions = CodingSessions(app)
        workers = WorkerSessionStore.inMemory()
        worker = WorkerRecord(
            id = "w-done",
            runId = "run",
            parentSessionId = "parent",
            workspaceId = "tree",
            job = "Review the parser",
            brief = "Check the null branch",
            configId = "cfg",
            model = "model-1",
            state = RunPhase.Done,
        )
        workers.saveWorker(worker)
        workers.saveMessages(worker.id, listOf(
            AgentMessage.user("Inspect the parser"),
            AgentMessage.assistant("Done."),
        ), interrupted = false)
        val config = ProviderConfig("cfg", ProviderType.OpenAi, "Test",
            "https://example.invalid/v1", "model-1")
        vm = ChatViewModel(
            app = app,
            sessionPersistence = sessions,
            providerSource = ProviderRuntimeSource { ProviderRuntimeResult.Ready(config, "key") },
            clientFactory = { _, _ -> NoopClient },
            workerStore = workers,
        )
        await { it.ready }
    }

    @After fun cleanup() = runBlocking {
        vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        Dispatchers.resetMain()
    }

    @Test fun workerTranscriptsNeverBecomeSessionListRowsUntilPromoted() = runBlocking {
        val idsBefore = sessions.list().map { it.id }.toSet()
        assertTrue(sessions.list().none { it.id == worker.id })
        assertTrue(idsBefore.isNotEmpty())
    }

    @Test fun openingAWorkerDoesNotChangeTheSessionAndContinueImportsOnce() = runBlocking {
        val original = vm.uiState.value.currentSessionId
        vm.openWorker(worker.id)
        awaitValue {
            val sheet = vm.workerSheet.value
            if (sheet.worker?.id == worker.id && !sheet.loading) Unit else null
        }
        assertEquals(original, vm.uiState.value.currentSessionId)
        assertEquals(2, vm.workerSheet.value.messages.size)

        vm.continueWorker(worker.id)
        val promoted = awaitValue {
            val id = workers.loadWorker(worker.id)?.promotedSessionId
            if (id != null && vm.uiState.value.currentSessionId == id) id else null
        }
        assertNotEquals(original, promoted)
        assertEquals(1, sessions.list().count { it.title == "Subagent · Review the parser" })
        assertNull(vm.workerSheet.value.error)

        vm.continueWorker(worker.id)
        delay(150)
        assertEquals(1, sessions.list().count { it.title == "Subagent · Review the parser" })
        assertEquals(promoted, vm.uiState.value.currentSessionId)
    }

    @Test fun openWorkerAndContinueWorkerOnRunningWorkerDoNotMutateLiveTranscript() = runBlocking {
        val live = WorkerRecord(
            id = "w-live",
            runId = "run-live",
            parentSessionId = "parent",
            workspaceId = "tree",
            job = "Inspect parser edge cases",
            brief = "Trace token loop",
            configId = "cfg",
            model = "model-1",
            state = RunPhase.Running,
        )
        val initial = listOf(
            AgentMessage.user("Trace token loop"),
            AgentMessage.assistant("Reading file", toolCalls = listOf(AgentToolCall("tc-live", "read_file", "{}"))),
        )
        workers.saveWorker(live)
        workers.saveMessages(live.id, initial, interrupted = true)

        vm.openWorker(live.id)
        awaitValue {
            val sheet = vm.workerSheet.value
            if (sheet.worker?.id == live.id && !sheet.loading) Unit else null
        }
        vm.continueWorker(live.id)
        delay(100)

        assertEquals(2, workers.fullEventCount(live.id))
        val afterTool = initial + AgentMessage.tools(
            listOf(AgentToolResult(callId = "tc-live", name = "read_file", content = "done")),
        )
        workers.saveMessages(live.id, afterTool, interrupted = false)
        assertEquals(3, workers.fullEventCount(live.id))
    }

    @Test fun continueWorkerPreservesPreCompactionEventsWhenPromotingCompactedWorker() = runBlocking {
        val compactedWorker = WorkerRecord(
            id = "w-compacted",
            runId = "run-c",
            parentSessionId = "parent",
            workspaceId = "tree",
            job = "Refactor parser",
            brief = "Keep history",
            configId = "cfg",
            model = "model-1",
            state = RunPhase.Done,
        )
        val m1 = AgentMessage.user("Step 1 " + "a".repeat(2_000))
        val m2 = AgentMessage.assistant("Reply 1 " + "b".repeat(2_000))
        val m3 = AgentMessage.user("Step 2")
        val m4 = AgentMessage.assistant("Reply 2")
        val all = listOf(m1, m2, m3, m4)
        workers.saveWorker(compactedWorker)
        workers.saveMessages(compactedWorker.id, all, interrupted = false)
        workers.compact(
            expected = all,
            retained = listOf(m3, m4),
            summary = "Summarized step 1.",
            attempt = null,
            expectedSessionId = compactedWorker.id,
        )
        assertEquals(4, workers.fullEventCount(compactedWorker.id))

        vm.continueWorker(compactedWorker.id)
        val promotedId = awaitValue {
            sessions.list().firstOrNull { it.title == "Subagent · Refactor parser" }?.id
        }
        await { it.currentSessionId == promotedId }

        assertEquals(4, sessions.fullEventCount(promotedId))
        val promotedEvents = sessions.recent(promotedId, limit = 10)
        assertTrue(promotedEvents.any { it.text.startsWith("Step 1") })
    }

    private suspend fun await(predicate: (ChatUiState) -> Boolean) {
        withTimeout(5_000) {
            while (!predicate(vm.uiState.value)) delay(10)
        }
    }

    private suspend fun <T : Any> awaitValue(block: suspend () -> T?): T {
        return withTimeout(5_000) {
            while (true) {
                val value = block()
                if (value != null) return@withTimeout value
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }
    }

    private object NoopClient : ProviderClient {
        override suspend fun listModels(): List<com.kaiser.rivet.provider.ModelInfo> = emptyList()
        override suspend fun testConnection(): com.kaiser.rivet.provider.TestResult =
            com.kaiser.rivet.provider.TestResult(ok = true, message = "ok")
        override suspend fun streamAgent(
            request: com.kaiser.rivet.provider.AgentRequest,
            onReasoning: (String) -> Unit,
            onDelta: (String) -> Unit,
        ): com.kaiser.rivet.agent.AgentResponse = throw UnsupportedOperationException()
    }
}
