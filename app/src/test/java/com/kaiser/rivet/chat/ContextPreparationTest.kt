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
import com.kaiser.rivet.provider.withManualContextWindow
import com.kaiser.rivet.storage.AgentSessionStore
import com.kaiser.rivet.storage.CodingSessions
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(retained.count { it.internalContext == null } < history.size)
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
        val active = sessions.compact(latest, latest, prior)
        val provider = SummaryProvider()
        val preparation = preparation(sessions, id, 4096, provider, prior)

        val reduced = preparation.prepare(active)
        assertEquals(latest, reduced.filter { it.internalContext == null })
        assertEquals(1, reduced.count { it.internalContext?.kind == "summary" })

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
        val active = sessions.compact(history, history, prior)
        val provider = SummaryProvider()
        val preparation = preparation(sessions, id, 16384, provider, prior)

        val retained = preparation.prepare(active)

        val newestRemoved = history.last { it !in retained }.text.substringBefore(' ')
        val input = provider.requests.single().messages.single().text
        assertTrue(input.contains(newestRemoved))
        assertTrue(input.contains("Earlier removed events omitted"))
        assertTrue(input.contains("Check the latest boundary"))
        assertEquals(history, sessions.recent(id, 100).takeLast(100))
    }

    @Test fun transcriptWithRepeatedLargeFileCreateAndReadBacksStubsInsteadOfDying() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        AgentSessionStore(app).clear()
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val largeHtml = "<html><body>" + "<div class=\"card\">dashboard</div>".repeat(1200) + "</body></html>"
        val transcript = buildList {
            add(AgentMessage.user("Build a single-file dashboard in index.html"))
            val createCall = com.kaiser.rivet.agent.AgentToolCall(
                "call-write-1",
                "write_file",
                """{"path":"index.html","content":"$largeHtml"}""",
            )
            add(AgentMessage.assistant("", listOf(createCall)))
            add(AgentMessage.tools(listOf(com.kaiser.rivet.agent.AgentToolResult(
                "call-write-1",
                "write_file",
                """{"path":"index.html","size":${largeHtml.length},"sha256":"sha-1"}""",
                summary = "Wrote index.html",
            ))))
            repeat(5) { step ->
                val callId = "call-read-$step"
                add(AgentMessage.assistant("", listOf(com.kaiser.rivet.agent.AgentToolCall(
                    callId,
                    "read_file",
                    """{"path":"index.html","offset":${step * 8000},"limit":24000}""",
                ))))
                add(AgentMessage.tools(listOf(com.kaiser.rivet.agent.AgentToolResult(
                    callId,
                    "read_file",
                    """{"path":"index.html","size":${largeHtml.length},"sha256":"sha-1","content":"$largeHtml"}""",
                    summary = "Read index.html chunk $step",
                ))))
            }
        }
        sessions.save(transcript, interrupted = true)
        val provider = SummaryProvider()
        val preparation = preparation(sessions, id, 16_000, provider)

        val reduced = preparation.prepare(transcript)

        assertEquals(0, provider.requests.size)
        assertTrue(com.kaiser.rivet.agent.AgentContext.serializedBytes(reduced) < 12_000)
        assertTrue(reduced.flatMap { it.toolCalls }.any { it.arguments.contains("\"arguments_pruned\":true") })
        assertTrue(reduced.flatMap { it.toolResults }.any { it.content.contains("\"output_pruned\":true") })
        assertTrue(reduced.flatMap { it.toolResults }.any { it.content.contains("\"path\":\"index.html\"") })
    }

    // Compaction must happen while there is still room to send the compacted request, so
    // the same turn continues; waiting for the ceiling is what dead-stopped long chats.
    @Test fun aConversationOverTheTriggerCompactsWellBeforeTheCeiling() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        AgentSessionStore(app).clear()
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val history = listOf(AgentMessage.user("Old investigation " + "x".repeat(84_000)),
            AgentMessage.assistant("Found a clue"), AgentMessage.user("Continue the current fix"))
        sessions.save(history, interrupted = true)
        val provider = SummaryProvider()
        val preparation = preparation(sessions, id, 32_768, provider)

        val retained = preparation.prepare(history)

        assertTrue("the request must shrink", retained.count { it.internalContext == null } < history.size)
        assertTrue("the newest request survives verbatim", retained.contains(history.last()))
        assertTrue("a summary replaces the old history", preparation.summary.isNotBlank())
        assertEquals("one summary call is enough", 1, provider.requests.size)
    }

    // A stored projection cannot grow past its own cap, so the window's token trigger is
    // unreachable on a big window. Compaction must fire on the projection pressure first,
    // or the save fails and the conversation dead-stops with the tool result lost.
    @Test fun aBigWindowCompactsBeforeTheStoredProjectionHitsItsCap() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        AgentSessionStore(app).clear()
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        // ~168k tokens of transcript: far under a 256k window's trigger (215k) but close
        // to the 512 KB projection cap that would make the next save throw.
        val big = "x".repeat(500_000)
        val history = listOf(AgentMessage.user("Old dump " + big),
            AgentMessage.assistant("Noted"), AgentMessage.user("Continue the fix"))
        sessions.save(history, interrupted = true)
        val provider = SummaryProvider()
        // The window is entered, not guessed: F4 plans a guessed 256K window at half of it
        // (a ~100k trigger), which would fire the token trigger and hide the projection-cap
        // path this test exists to pin.
        val config = ProviderConfig("test", ProviderType.OpenAiCompatible, "Test", endpoint, "big")
            .withManualContextWindow(262_144)
        // A fresh turn has no stored summary; the parameter stays required because a real
        // session's summary must never be silently dropped.
        val preparation = ContextPreparation(sessions, id, provider, config, "turn-big", "") { messages, _ ->
            AgentRequest("big", com.kaiser.rivet.agent.modelMessages(messages),
                "S".repeat(6000), ReasoningLevel.Default, emptyList())
        }

        val retained = preparation.prepare(history)

        assertTrue("the old dump must be compacted away", retained.count { it.internalContext == null } < history.size)
        assertTrue(retained.contains(history.last()))
        assertEquals(1, provider.requests.size)
    }

    // A request that still fits the window must be sent even when there is nothing left to
    // compact: refusing it is a dead stop for a request the provider would have accepted.
    @Test fun aRequestThatStillFitsIsSentWhenNothingCanBeCompacted() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        AgentSessionStore(app).clear()
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        // One long user message and nothing else: no tool pair to prune, and no room for
        // a summary, but the request is under the 16,000-token ceiling.
        val history = listOf(AgentMessage.user("Pasted notes " + "x".repeat(26_000)))
        sessions.save(history, interrupted = true)
        val provider = SummaryProvider()
        val preparation = preparation(sessions, id, 16_000, provider)

        val retained = preparation.prepare(history)

        assertEquals("nothing was compacted, so the request is unchanged", history, retained)
        assertEquals("no summary is worth paying for here", 0, provider.requests.size)
    }

    // A summariser that keeps failing must not be paid for on every step of the turn.
    // A summariser that keeps failing must not be paid for on every step of the turn, and
    // its own failure must surface. Once the attempts are spent the request is sent as it
    // stands, because refusing a request that still fits is the dead stop dc5235a removed.
    @Test fun aFailingSummariserIsPaidForTwiceAndThenTheTurnProceeds() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        AgentSessionStore(app).clear()
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        // ~30 k estimated tokens against a 32 768 ceiling: over the compaction trigger, but
        // still inside the ceiling, so the request itself is sendable.
        val history = listOf(AgentMessage.user("Old investigation " + "x".repeat(84_000)),
            AgentMessage.assistant("Found a clue"), AgentMessage.user("Continue the current fix"))
        sessions.save(history, interrupted = true)
        val provider = FailingSummaryProvider()
        val preparation = preparation(sessions, id, 32_768, provider)

        // The bounded attempts are spent, and each one surfaces the summariser's failure.
        repeat(2) {
            try {
                preparation.prepare(history)
                throw AssertionError("a failed summary must not pass silently")
            } catch (_: IllegalStateException) { /* expected: the provider's failure surfaces */ }
        }
        assertEquals("summary attempts are bounded per turn", 2, provider.requests.size)

        // The next step must not buy a third summary and must not refuse the request.
        val sent = preparation.prepare(history)
        assertEquals("the turn proceeds with the transcript as it stands", history, sent)
        assertEquals("no further summary was bought", 2, provider.requests.size)
        assertEquals("nothing was committed", "", preparation.summary)
    }

    // The last resort that legitimately refuses: a request whose own minimum projection
    // already exceeds the ceiling cannot be sent, and no summary can make room for it.
    @Test fun aRequestTooLargeToFitIsRefusedBeforeTheProvider() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        AgentSessionStore(app).clear()
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val huge = listOf(AgentMessage.user("Pasted dump " + "x".repeat(60_000)))
        sessions.save(huge, interrupted = true)
        val provider = SummaryProvider()
        val preparation = preparation(sessions, id, 16_000, provider)

        try {
            preparation.prepare(huge)
            throw AssertionError("a request whose own minimum exceeds the ceiling must be refused")
        } catch (_: ContextCapacityTooSmall) { /* expected */ }

        assertEquals("refusing costs no summary call", 0, provider.requests.size)
        assertEquals("the transcript is kept", huge, sessions.recent(id))
    }

    // Kaiser's device case: a long chat whose compacted projection fit what the model would take,
    // refused anyway because the projection did not fit the comfortable margin Rivet aims for. A
    // request is sendable when it fits the ceiling and is smaller than what we would otherwise send,
    // and that is all — no third condition may creep back in, or the wall comes back with it.
    @Test fun aProjectionThatFitsTheCeilingIsSentEvenAboveTheComfortableTarget() {
        val ceiling = 220_000L
        assertTrue("fits the ceiling, just above the aim: this is the device dead end",
            projectionCanBeSent(ceiling - 1, baseline = ceiling + 40_000, ceiling = ceiling))
        assertFalse("at the ceiling is not sendable",
            projectionCanBeSent(ceiling, baseline = ceiling + 40_000, ceiling = ceiling))
        assertFalse("no smaller than the request it replaces is not progress",
            projectionCanBeSent(ceiling - 1, baseline = ceiling - 1, ceiling = ceiling))
    }

    private fun preparation(sessions: CodingSessions, id: String, limit: Int,
                            provider: ProviderClient, priorSummary: String = ""): ContextPreparation {
        val config = ProviderConfig("test", ProviderType.Gemini, "Test", endpoint, "small",
            modelContextLimit = ModelContextLimit("small", endpoint, limit))
        return ContextPreparation(sessions, id, provider, config, "turn-$limit", priorSummary) { messages, _ ->
            AgentRequest("small", com.kaiser.rivet.agent.modelMessages(messages),
                "S".repeat(6000), ReasoningLevel.Default, emptyList())
        }
    }

    private class FailingSummaryProvider : ProviderClient {
        val requests = mutableListOf<AgentRequest>()
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun testConnection() = TestResult(true, "ok")
        override suspend fun streamAgent(
            request: AgentRequest,
            onReasoning: (String) -> Unit,
            onDelta: (String) -> Unit,
        ): AgentResponse {
            requests += request
            throw IllegalStateException("provider down")
        }
    }

    private class SummaryProvider : ProviderClient {
        val requests = mutableListOf<AgentRequest>()
        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun testConnection() = TestResult(true, "ok")
        override suspend fun streamAgent(
            request: AgentRequest,
            onReasoning: (String) -> Unit,
            onDelta: (String) -> Unit,
        ): AgentResponse {
            requests += request
            return AgentResponse(text = """{"objective":"Continue the current fix","nextStep":"Inspect"}""")
        }
    }
}
