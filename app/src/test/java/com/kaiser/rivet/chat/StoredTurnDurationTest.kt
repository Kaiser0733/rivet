package com.kaiser.rivet.chat

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.lifecycle.viewModelScope
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderClient
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.TestResult
import com.kaiser.rivet.storage.CodingSessions
import com.kaiser.rivet.ui.chat.TurnItem
import com.kaiser.rivet.ui.chat.segmentTurnItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class StoredTurnDurationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private lateinit var vm: ChatViewModel

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app.deleteDatabase("coding-sessions.db")
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        // Write the stored metric the way the app persists it. This fixture compiles
        // against the parent API; on the parent it is ignored and the row falls back to
        // "Completed", so this test is red there for the actual behaviour gap.
        SQLiteDatabase.openDatabase(
            app.getDatabasePath("coding-sessions.db").path, null, SQLiteDatabase.OPEN_READWRITE
        ).use { db ->
            db.execSQL(
                "INSERT OR REPLACE INTO metadata(key,value) VALUES(?,?)",
                arrayOf("turn-metrics-$id", """{"entries":{"0":{"durationMs":65000}}}"""),
            )
        }
        val config = ProviderConfig("cfg", ProviderType.OpenAi, "Test",
            "https://example.invalid/v1", "model-1")
        vm = ChatViewModel(
            app = app,
            sessionPersistence = sessions,
            providerSource = ProviderRuntimeSource { ProviderRuntimeResult.Ready(config, "key") },
            clientFactory = { _, _ -> NoopClient },
            workerStore = com.kaiser.rivet.subagent.WorkerSessionStore.inMemory(),
        )
        withTimeout(5_000) {
            while (!vm.uiState.value.ready) delay(10)
        }
    }

    @After fun cleanup() = runBlocking {
        vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        Dispatchers.resetMain()
    }

    @Test fun storedCompletedTurnStillRendersCompletedInDuration() {
        assertEquals(mapOf(0 to 65_000L), vm.uiState.value.turnDurationsMs)
        val items = listOf(
            TurnItem(AgentMessage.user("Fix it"), isUser = true, isAnswer = false, isProcess = false),
            TurnItem(AgentMessage.assistant("Fixed."), isUser = false, isAnswer = true, isProcess = false),
        )
        val turn = segmentTurnItems(items, isStreaming = false,
            turnDurationsMs = vm.uiState.value.turnDurationsMs,
            isInterruptedTurn = false, hasError = false).single()
        assertEquals("Completed in 1m5s", turn.spec.foldLabel())
    }

    private object NoopClient : ProviderClient {
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun testConnection(): TestResult = TestResult(ok = true, message = "ok")
        override suspend fun streamAgent(
            request: AgentRequest,
            onReasoning: (String) -> Unit,
            onDelta: (String) -> Unit,
        ): AgentResponse = error("not used")
    }
}
