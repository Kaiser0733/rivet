package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentResponse
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.agent.ContextBudget
import com.kaiser.rivet.agent.ProjectContext
import com.kaiser.rivet.agent.modelMessages
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ModelContextLimit
import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderClient
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.provider.TestResult
import com.kaiser.rivet.provider.withManualMaxOutputTokens
import com.kaiser.rivet.storage.ContextAttempt
import com.kaiser.rivet.storage.ContextEstimate
import com.kaiser.rivet.storage.ContextStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCheckpointTest {
    @Test fun compactionProducesTheSameSixSectionsForCloudAndLocal() = runBlocking {
        val headings = listOf("GOAL", "DECISIONS + WHY", "CONSTRAINTS & PREFERENCES",
            "STATE", "OPEN THREADS", "EXACT VALUES")
        for (type in listOf(ProviderType.Gemini, ProviderType.Local)) {
            val provider = SummaryProvider("""{"OPEN THREADS":["Verify"]}""")
            val summary = checkpoint(preparation(provider, type).prepare(history("Fix the crash")))
            for (heading in headings) {
                assertTrue(summary.contains("\n$heading\n"))
                assertTrue(provider.requests.single().system.contains("\"$heading\""))
            }
        }
    }

    @Test fun twoCompactionsKeepTheExactGoalBlock() = runBlocking {
        val goal = "  Fix \"src/A.kt\".\n  Keep spacing, café, and /tmp/Build A.\n"
        val provider = SummaryProvider("""{"GOAL":"Paraphrased"}""", """{"GOAL":"Different paraphrase"}""")
        val (first, second) = twoCompactions(provider, goal)
        val exactGoalBlock = "\nGOAL\n$goal\n"

        assertTrue(first.contains(exactGoalBlock))
        assertTrue(second.contains(exactGoalBlock))
        assertFalse(first.contains("Paraphrased"))
        assertFalse(second.contains("Different paraphrase"))
    }

    @Test fun secondCompactionKeepsPriorDecisionsAndMarksSupersededOnes() = runBlocking {
        val provider = SummaryProvider(
            """{"DECISIONS + WHY":["Use SQLite because it is local","Use A because it is simpler"],"CONSTRAINTS & PREFERENCES":["Keep paths exact"]}""",
            """{"DECISIONS + WHY":["SUPERSEDED: Use A because it is simpler","Use B because A failed"]}""",
        )
        val (_, lastSummary) = twoCompactions(provider, "Fix the crash")

        assertTrue(lastSummary.contains("Use SQLite because it is local"))
        assertTrue(lastSummary.contains("SUPERSEDED: Use A because it is simpler"))
        assertTrue(lastSummary.contains("Use B because A failed"))
        assertTrue(lastSummary.contains("Keep paths exact"))
        val secondInput = provider.requests[1].messages.single().text
        assertTrue(secondInput.contains("Previous checkpoint"))
        assertTrue(secondInput.contains("Use SQLite because it is local"))
        assertEquals(2, provider.requests.size)
    }

    private suspend fun twoCompactions(provider: SummaryProvider, goal: String): Pair<String, String> {
        val preparation = preparation(provider)
        val first = preparation.prepare(history(goal))
        val second = preparation.prepare(first + AgentMessage.assistant("y".repeat(40_000)) + AgentMessage.assistant("Done"))
        return checkpoint(first) to checkpoint(second)
    }

    private fun history(goal: String) =
        listOf(AgentMessage.user("Old transcript"), AgentMessage.assistant("x".repeat(40_000)), AgentMessage.user(goal))

    private fun checkpoint(messages: List<AgentMessage>): String =
        messages.single { it.internalContext?.kind == "summary" }.internalContext!!.modelText()

    @Test fun checkpointCapRemovesOldestStateBeforeProtectedSections() = runBlocking {
        val provider = SummaryProvider(
            """{"STATE":["Old detail ${"x".repeat(6100)}","Newest detail"],"DECISIONS + WHY":["Keep SQLite because it works"],"CONSTRAINTS & PREFERENCES":["Keep paths exact"],"EXACT VALUES":["src/A.kt"]}""",
        )
        val preparation = preparation(provider)
        val goal = AgentMessage.user("Fix the build")
        val history = listOf(AgentMessage.user("Inspect"), AgentMessage.assistant("x".repeat(40_000)), goal)

        val summary = preparation.prepare(history).first().internalContext!!.modelText()

        assertFalse(summary.contains("Old detail"))
        assertTrue(summary.contains("Newest detail"))
        assertTrue(summary.contains("Keep SQLite because it works"))
        assertTrue(summary.contains("Keep paths exact"))
        assertTrue(summary.contains("src/A.kt"))
    }

    @Test fun summarizerReceivesPrunedOriginalBodyEvenWhenPlanningUsesToolStubs() = runBlocking {
        val head = "H".repeat(4096)
        val tail = "T".repeat(1024)
        val call = AgentToolCall("read", "read_file", "{}")
        val first = AgentMessage.user("Inspect")
        val history = listOf(
            first, AgentMessage.assistant("", listOf(call)),
            AgentMessage.tools(listOf(AgentToolResult(call.id, call.name, head + "M".repeat(5000) + tail))),
            AgentMessage.assistant("A".repeat(40_000)), AgentMessage.user("Continue the fix"),
        )
        val provider = SummaryProvider("""{"EXACT VALUES":["src/A.kt"]}""")

        val reduced = preparation(provider).prepare(history)

        assertFalse(first in reduced)
        val input = provider.requests.single().messages.single().text
        assertTrue(input.contains(head + "\n[Tool result middle omitted.]\n" + tail))
        assertFalse(input.contains("M".repeat(100)))
    }

    private fun preparation(provider: ProviderClient, type: ProviderType = ProviderType.Gemini): ContextPreparation {
        val endpoint = "https://example.invalid"
        val base = ProviderConfig("test", type, "Test", endpoint, "small",
            modelContextLimit = ModelContextLimit("small", endpoint, 8192))
        val config = if (type == ProviderType.Local) base.withManualMaxOutputTokens(2048) else base
        return ContextPreparation(ProjectionStore(), "session", provider, config, "turn", "") { messages, _ ->
            AgentRequest("small", modelMessages(messages), "S".repeat(6000), ReasoningLevel.Default, emptyList())
        }
    }

    private class ProjectionStore : ContextStore {
        override suspend fun compact(expected: List<AgentMessage>, retained: List<AgentMessage>, summary: String,
                                     attempt: ContextAttempt?, expectedSessionId: String?) =
            ProjectContext.projection(expected, retained, summary)

        override suspend fun recordContextFailure(sessionId: String, attempt: ContextAttempt) = Unit

        override suspend fun recordUsage(sessionId: String, turnId: String, providerId: String, model: String,
                                         usage: AgentUsage?, requestMessages: List<AgentMessage>, system: String,
                                         tools: List<AgentToolDefinition>, endpoint: String, providerType: ProviderType?,
                                         reasoning: ReasoningLevel) = Unit

        override suspend fun contextEstimate(sessionId: String, providerId: String, model: String,
                                             messages: List<AgentMessage>, system: String, tools: List<AgentToolDefinition>,
                                             endpoint: String, reasoning: ReasoningLevel): ContextEstimate =
            ContextEstimate(ContextBudget.estimateRequestTokens(AgentRequest(model, messages, system, reasoning, tools)),
                "estimated")
    }

    private class SummaryProvider(vararg responses: String) : ProviderClient {
        private val responses = responses.toList()
        val requests = mutableListOf<AgentRequest>()

        override suspend fun listModels(): List<ModelInfo> = emptyList()
        override suspend fun testConnection() = TestResult(true, "ok")

        override suspend fun streamAgent(request: AgentRequest, onReasoning: (String) -> Unit,
                                         onDelta: (String) -> Unit): AgentResponse {
            requests += request
            val text = if (request.system.contains("\"GOAL\"")) responses[requests.lastIndex]
                else """{"objective":"Paraphrased task","decisions":["Use B because A failed"]}"""
            onDelta(text)
            return AgentResponse(text = text)
        }
    }
}
