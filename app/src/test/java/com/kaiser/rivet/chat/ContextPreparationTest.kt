package com.kaiser.rivet.chat

import android.app.Application
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.ContextCapacityTooSmall
import com.kaiser.rivet.agent.TaskState
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ModelContextLimit
import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderClient
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.provider.TestResult
import com.kaiser.rivet.storage.AgentSessionStore
import com.kaiser.rivet.storage.CodingSessions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ContextPreparationTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val endpoint = "https://example.invalid"

    @Test fun verifiedSmallWindowsEitherFitActualRequestOrFailBeforeProvider() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        AgentSessionStore(app).clear()
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val latest = listOf(AgentMessage.user("Hi"))
        val limits = listOf(1, 2, 100, 1024, 2048, 2049, 4096, 8192, 16384)

        for (limit in limits) {
            sessions.clear()
            sessions.save(latest, interrupted = true)
            val provider = SummaryProvider()
            val preparation = preparation(sessions, id, limit, provider)
            if (limit <= 2049) {
                try {
                    preparation.prepare(latest)
                    throw AssertionError("Expected verified $limit-token model to be too small")
                } catch (_: ContextCapacityTooSmall) { Unit }
            } else {
                assertEquals(latest, preparation.prepare(latest))
            }
            assertEquals(0, provider.requests.size)
            assertEquals(latest, sessions.recent(id))
            assertEquals(1, sessions.fullEventCount(id))
        }
    }

    @Test fun smallUsableWindowReducesOldGroupsAndRetainsLatestRequest() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        AgentSessionStore(app).clear()
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val history = listOf(AgentMessage.user("Old investigation " + "x".repeat(12_000)),
            AgentMessage.assistant("Found a clue"), AgentMessage.user("Continue the current fix"))
        sessions.save(history, interrupted = true)
        val provider = SummaryProvider()
        val preparation = preparation(sessions, id, 4096, provider)

        val retained = preparation.prepare(history)

        assertTrue(retained.contains(history.last()))
        assertTrue(retained.size < history.size)
        assertEquals(history, sessions.recent(id))
        assertEquals(1, provider.requests.size)
        assertTrue(preparation.summary.contains("Continue"))
    }

    @Test fun latestUserTooLargeIsDiagnosedWithoutCompactionOrProviderCall() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        AgentSessionStore(app).clear()
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val latest = listOf(AgentMessage.user("x".repeat(40_000)))
        sessions.save(latest, interrupted = true)
        val provider = SummaryProvider()
        val preparation = preparation(sessions, id, 4096, provider)

        try {
            preparation.prepare(latest)
            throw AssertionError("Expected latest request to exceed verified capacity")
        } catch (_: ContextCapacityTooSmall) { Unit }

        assertEquals(0, provider.requests.size)
        assertEquals(latest, sessions.load().messages)
        assertEquals(latest, sessions.recent(id))
    }

    @Test fun smallerModelCanShortenPriorTaskStateWithoutDroppingCurrentUser() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        AgentSessionStore(app).clear()
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val latest = listOf(AgentMessage.user("Continue the fix"))
        sessions.save(latest, interrupted = true)
        val prior = TaskState("Original objective", completed = List(9) { "x".repeat(800) },
            nextStep = "Continue the fix").encode()
        val provider = SummaryProvider()
        val preparation = preparation(sessions, id, 4096, provider, prior)

        assertEquals(latest, preparation.prepare(latest))

        assertEquals(1, provider.requests.size)
        assertTrue(provider.requests.single().messages.single().text.contains("Original objective"))
        assertEquals(latest, sessions.recent(id))
        assertTrue(sessions.load().summary.contains("Continue the current fix"))
    }

    @Test fun summaryRequestKeepsNewestRemovedGroupUnderTightInputBudget() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        AgentSessionStore(app).clear()
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val history = (0 until 70).map { index ->
            AgentMessage.user("marker-$index " + "x".repeat(3000))
        }
        sessions.save(history, interrupted = true)
        val prior = TaskState("Carry original objective", completed = List(9) { "y".repeat(800) },
            nextStep = "Check the latest boundary").encode()
        val provider = SummaryProvider()
        val preparation = preparation(sessions, id, 16384, provider, prior)

        val retained = preparation.prepare(history)

        val newestRemoved = history.last { it !in retained }.text.substringBefore(' ')
        val input = provider.requests.single().messages.single().text
        assertTrue(input.contains(newestRemoved))
        assertTrue(input.contains("Earlier removed events omitted"))
        assertTrue(input.contains("Check the latest boundary"))
        assertEquals(history, sessions.recent(id, 100).takeLast(100))
    }

    private fun preparation(sessions: CodingSessions, id: String, limit: Int,
                            provider: SummaryProvider, priorSummary: String = ""): ContextPreparation {
        val config = ProviderConfig("test", ProviderType.Gemini, "Test", endpoint, "small",
            modelContextLimit = ModelContextLimit("small", endpoint, limit))
        return ContextPreparation(sessions, id, provider, config, "turn-$limit", priorSummary) { messages, _ ->
            AgentRequest("small", com.kaiser.rivet.agent.modelMessages(messages),
                "S".repeat(6000), ReasoningLevel.Default, emptyList())
        }
    }

    private class SummaryProvider : ProviderClient {
        val requests = mutableListOf<AgentRequest>()
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun testConnection() = TestResult(true, "ok")
        override suspend fun streamAgent(request: AgentRequest, onDelta: (String) -> Unit): AgentResponse {
            requests += request
            return AgentResponse(text = """{"objective":"Continue the current fix","nextStep":"Inspect"}""")
        }
    }
}
