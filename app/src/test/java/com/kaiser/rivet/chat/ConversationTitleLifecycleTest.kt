package com.kaiser.rivet.chat

import android.app.Application
import android.content.Context
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.provider.*
import com.kaiser.rivet.storage.AgentSessionStore
import com.kaiser.rivet.storage.CodingSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConversationTitleLifecycleTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val config = ProviderConfig("p", ProviderType.OpenAi, "Provider",
        "https://example.invalid/v1", "model", ReasoningLevel.High)

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app.deleteDatabase("coding-sessions.db")
        app.getSharedPreferences("workspace", Context.MODE_PRIVATE).edit().clear().commit()
        AgentSessionStore(app).clear()
    }
    @After fun cleanup() { Dispatchers.resetMain() }

    @Test fun firstSuccessfulTurnTitlesNewConversationWithoutChangingEvents() = runBlocking {
        val requests = mutableListOf<AgentRequest>()
        val store = CodingSessions(app)
        val client = object : ProviderClient {
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun testConnection() = TestResult(true, "ok")
            override suspend fun streamAgent(request: AgentRequest, onDelta: (String) -> Unit): AgentResponse {
                requests += request
                return if (requests.size == 1) AgentResponse("Here is the fix.")
                    else AgentResponse("Fix Login Crash", usage = AgentUsage(12, 3))
            }
        }
        val vm = ChatViewModel(app, store,
            ProviderRuntimeSource { ProviderRuntimeResult.Ready(config, "key") }, { _, _ -> client })
        withTimeout(5000) { vm.uiState.first { it.ready && !it.projectLoading } }
        val oldId = vm.uiState.value.currentSessionId
        vm.newSession()
        val id = withTimeout(5000) { vm.uiState.first { it.currentSessionId != oldId } }.currentSessionId!!
        vm.send("Fix the login crash")
        withTimeout(5000) { vm.uiState.first { it.currentSessionTitle == "Fix Login Crash" } }
        assertEquals(2, requests.size)
        assertTrue(requests[1].tools.isEmpty())
        assertEquals(ReasoningLevel.Default, requests[1].reasoning)
        assertEquals(config.model, requests[1].model)
        assertEquals(listOf(AgentMessage.user("Fix the login crash"), AgentMessage.assistant("Here is the fix.")),
            store.load().messages)
        assertEquals(2, store.fullEventCount(id))
        vm.send("Thanks")
        withTimeout(5000) { vm.uiState.first { !it.streaming && it.acceptedMessageCount == 2L } }
        assertEquals(3, requests.size)
    }
}
