package com.kaiser.rivet.chat

import android.app.Application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import android.content.Context
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import com.kaiser.rivet.workspace.TestDocumentsProvider
import com.kaiser.rivet.workspace.WorkspaceSelection
import org.robolectric.Robolectric
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.provider.*
import com.kaiser.rivet.storage.AgentSessionPersistence
import com.kaiser.rivet.storage.AgentSessionStore
import com.kaiser.rivet.storage.CodingSessions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
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
    private val models = mutableListOf<ChatViewModel>()
    private fun newChatViewModel(app: Application, sessionPersistence: AgentSessionPersistence,
                                 providerSource: ProviderRuntimeSource,
                                 clientFactory: (ProviderConfig, String) -> ProviderClient) =
        ChatViewModel(app, sessionPersistence, providerSource, clientFactory).also { models += it }

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val config = ProviderConfig("p", ProviderType.OpenAi, "Provider",
        "https://example.invalid/v1", "model", ReasoningLevel.High)

    @Before fun setup() = runBlocking {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app.deleteDatabase("coding-sessions.db")
        app.getSharedPreferences("workspace", Context.MODE_PRIVATE).edit().clear().commit()
        AgentSessionStore(app).clear()
    }
    @After fun cleanup() = runBlocking {
        models.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
        models.clear()
        Dispatchers.resetMain()
    }

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
        val vm = newChatViewModel(app, store,
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
    private suspend fun conversation(stream: suspend (AgentRequest) -> AgentResponse): Pair<ChatViewModel, CodingSessions> {
        val store = CodingSessions(app)
        val client = object : ProviderClient {
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun testConnection() = TestResult(true, "ok")
            override suspend fun streamAgent(request: AgentRequest, onDelta: (String) -> Unit) = stream(request)
        }
        val vm = newChatViewModel(app, store,
            ProviderRuntimeSource { ProviderRuntimeResult.Ready(config, "key") }, { _, _ -> client })
        withTimeout(5000) { vm.uiState.first { it.ready && !it.projectLoading } }
        val previous = vm.uiState.value.currentSessionId
        vm.newSession()
        withTimeout(5000) { vm.uiState.first { it.currentSessionId != previous } }
        return vm to store
    }

    @Test fun manualRenameWinsWhileTitleRequestIsInFlight() = runBlocking {
        val titleStarted = CompletableDeferred<Unit>()
        val titleAnswer = CompletableDeferred<String>()
        var calls = 0
        val (vm, store) = conversation {
            if (++calls == 1) AgentResponse("Done") else {
                titleStarted.complete(Unit)
                AgentResponse(titleAnswer.await())
            }
        }
        vm.send("Fix it")
        withTimeout(5000) { titleStarted.await() }
        delay(50)
        val id = vm.uiState.value.currentSessionId!!
        vm.renameSession(id, "My chosen title")
        withTimeout(5000) { vm.uiState.first { it.currentSessionTitle == "My chosen title" } }
        titleAnswer.complete("Generated Title")
        withTimeout(5000) { vm.uiState.first { it.usage?.titleRequests == 1 } }
        assertEquals("My chosen title", store.load().title)
        assertEquals(2, calls)
    }

    @Test fun titleProviderFailureAndInvalidOutputNeverFailCodingTurn() = runBlocking {
        for (invalid in listOf<String?>(null, "", "{\"title\":\"bad\"}")) {
            val done = CompletableDeferred<Unit>()
            var calls = 0
            val (vm, store) = conversation {
                if (++calls == 1) AgentResponse("Done") else {
                    done.complete(Unit)
                    if (invalid == null) throw ProviderError.Forbidden()
                    AgentResponse(invalid)
                }
            }
            vm.send("Fix it")
            withTimeout(5000) { done.await() }
            delay(50)
            assertNull(vm.uiState.value.error)
            assertEquals("New session", store.load().title)
            assertEquals(2, store.fullEventCount(vm.uiState.value.currentSessionId!!))
            assertEquals(2, calls)
        }
    }

    @Test fun cancelledTurnNeverStartsTitleRequestOrRegeneratesLater() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val (vm, store) = conversation {
            if (++calls == 1) { started.complete(Unit); awaitCancellation() }
            AgentResponse("Done")
        }
        vm.send("Fix it")
        withTimeout(5000) { started.await() }
        vm.cancel()
        withTimeout(5000) { vm.uiState.first { !it.streaming } }
        assertEquals(1, calls)
        vm.send("Try again")
        withTimeout(5000) { vm.uiState.first { !it.streaming && it.acceptedMessageCount == 2L } }
        assertEquals(2, calls)
        assertEquals("New session", store.load().title)
    }

    @Test fun failedIncompleteOrBlockedToolTurnNeverRequestsTitle() = runBlocking {
        for (response in listOf<AgentResponse?>(null, AgentResponse(), AgentResponse(toolCalls = listOf(
            com.kaiser.rivet.agent.AgentToolCall("blocked", "run_command", "{\"command\":\"echo test\"}"))))) {
            var calls = 0
            val (vm, store) = conversation {
                calls++
                if (response == null) throw ProviderError.InvalidResponse("Incomplete stream")
                response
            }
            vm.send("Do it")
            withTimeout(5000) { vm.uiState.first { !it.streaming && it.acceptedMessageCount == 1L } }
            assertEquals(1, calls)
            assertEquals("New session", store.load().title)
            assertTrue(vm.uiState.value.error != null)
        }
    }

    @Test fun existingDefaultAndManualTitlesDoNotSpendAuxiliaryTokens() = runBlocking {
        val store = CodingSessions(app)
        val legacy = store.load().id!!
        store.rename(legacy, "New session")
        var calls = 0
        val client = object : ProviderClient {
            override suspend fun listModels() = emptyList<ModelInfo>()
            override suspend fun testConnection() = TestResult(true, "ok")
            override suspend fun streamAgent(request: AgentRequest, onDelta: (String) -> Unit): AgentResponse {
                calls++; return AgentResponse("Done")
            }
        }
        val vm = newChatViewModel(app, store,
            ProviderRuntimeSource { ProviderRuntimeResult.Ready(config, "key") }, { _, _ -> client })
        withTimeout(5000) { vm.uiState.first { it.ready && !it.projectLoading } }
        assertEquals(0, calls)
        vm.send("First legacy task")
        withTimeout(5000) { vm.uiState.first { !it.streaming && it.acceptedMessageCount == 1L } }
        assertEquals(1, calls)
        assertEquals("New session", store.load().title)
        vm.newSession()
        withTimeout(5000) { vm.uiState.first { it.currentSessionId != legacy } }
        vm.renameSession(vm.uiState.value.currentSessionId!!, "Manual Title")
        withTimeout(5000) { vm.uiState.first { it.currentSessionTitle == "Manual Title" } }
        vm.send("First manually named task")
        withTimeout(5000) { vm.uiState.first { !it.streaming && it.acceptedMessageCount == 1L } }
        assertEquals(2, calls)
        assertEquals("Manual Title", store.load().title)
    }

    private fun selectFixtureProject(): TestDocumentsProvider {
        val authority = "com.kaiser.rivet.title-project"
        val documents = Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(
            ProviderInfo().apply { this.authority = authority; exported = true; grantUriPermissions = true }).get()
        WorkspaceSelection(app).select(DocumentsContract.buildTreeDocumentUri(authority, "root"),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        return documents
    }

    @Test fun workspaceGuidanceNeverEntersTheActualTitleRequest() = runBlocking {
        val documents = selectFixtureProject()
        val marker = "PRIVATE_PROJECT_INSTRUCTIONS"
        documents.nodes["instructions"] = TestDocumentsProvider.Node("AGENTS.md", "root", false,
            java.io.File.createTempFile("title-agents", ".test", app.cacheDir).apply { writeText(marker) })
        val requests = mutableListOf<AgentRequest>()
        val (vm, _) = conversation { request ->
            requests += request
            if (requests.size == 1) AgentResponse("Done") else AgentResponse("Fix Login Crash")
        }
        vm.send("Fix the login crash")
        withTimeout(5000) { vm.uiState.first { it.currentSessionTitle == "Fix Login Crash" } }
        assertTrue(requests[0].messages.any { marker in it.text })
        val title = requests[1]
        assertTrue(title.tools.isEmpty())
        assertFalse(title.system.contains(marker))
        assertFalse(title.messages.any { marker in it.text || "content://" in it.text || "TaskState" in it.text ||
            it.internalContext != null || it.transportState != null || it.toolResults.isNotEmpty() })
    }

    @Test fun cancelledPendingToolTurnNeverGeneratesTitleOrExecutesMutation() = runBlocking {
        val documents = selectFixtureProject()
        var calls = 0
        val (vm, store) = conversation {
            calls++
            AgentResponse(toolCalls = listOf(com.kaiser.rivet.agent.AgentToolCall(
                "create", "create_file", "{\"path\":\"disposable.txt\"}")))
        }
        vm.send("Create a disposable file")
        withTimeout(5000) { vm.uiState.first { it.pendingApproval != null } }
        vm.cancel()
        withTimeout(5000) { vm.uiState.first { !it.streaming && it.pendingApproval == null } }
        assertEquals(1, calls)
        assertEquals("New session", store.load().title)
        assertEquals(0, documents.createCalls)
        assertEquals(0, store.usage(vm.uiState.value.currentSessionId!!).titleRequests)
    }

}
