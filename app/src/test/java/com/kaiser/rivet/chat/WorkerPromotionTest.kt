package com.kaiser.rivet.chat

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.kaiser.rivet.agent.AgentMessage
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

    @Test fun openingAWorkerDoesNotChangeTheSessionAndContinueImportsOnce() = runBlocking {
        val original = vm.uiState.value.currentSessionId
        vm.openWorker(worker.id)
        await { it.workerSheet.worker?.id == worker.id && !it.workerSheet.loading }
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
