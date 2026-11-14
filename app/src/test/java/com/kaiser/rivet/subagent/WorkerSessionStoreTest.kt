package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.storage.AgentSessionLimitException
import com.kaiser.rivet.workspace.WorkspacePath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerSessionStoreTest {
    private fun sampleWorker(
        id: String = "w-1",
        runId: String = "run-1",
        sessionId: String = "sess-1",
        state: RunPhase = RunPhase.Running,
        updatedAt: Long = 1_000L,
    ) = WorkerRecord(
        id = id,
        runId = runId,
        parentSessionId = sessionId,
        workspaceId = "ws-1",
        job = "Inspect auth",
        brief = "Check token validation.",
        skill = "security",
        configId = "cfg-1",
        model = "claude-sonnet-4-6",
        modelLabel = "Claude · Sonnet 4.6",
        writeScope = listOf(WorkspacePath.parse("src/auth")),
        state = state,
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )

    @Test
    fun roundTripsWorkerRecordTranscriptAndRecoversDanglingToolCalls() = runBlocking {
        val store = WorkerSessionStore.inMemory()
        val worker = sampleWorker()
        store.saveWorker(worker)

        val loaded = store.loadWorker("w-1")
        assertNotNull(loaded)
        assertEquals("Inspect auth", loaded!!.job)
        assertEquals(listOf(WorkspacePath.parse("src/auth")), loaded.writeScope)

        val userMsg = AgentMessage.user("You are a Rivet worker for one job.")
        val danglingAssistant = AgentMessage.assistant(
            text = "",
            toolCalls = listOf(AgentToolCall("call-1", "read_file", """{"path":"src/auth/login.kt"}""")),
        )
        store.saveMessages("w-1", listOf(userMsg, danglingAssistant), interrupted = true)

        // Loading the transcript recovers the dangling tool call with a synthetic interrupted result.
        val transcript = store.loadTranscript("w-1")
        assertTrue(transcript.interrupted)
        assertEquals(3, transcript.messages.size)
        val recoveredTool = transcript.messages.last()
        assertEquals(AgentRole.Tool, recoveredTool.role)
        assertEquals(1, recoveredTool.toolResults.size)
        assertEquals("call-1", recoveredTool.toolResults[0].callId)
        assertTrue(recoveredTool.toolResults[0].error)
        assertTrue(recoveredTool.toolResults[0].content.contains("interrupted"))
    }

    @Test
    fun enforces512KiBProjectionCapAndCompactsViaContextStore() = runBlocking {
        val store = WorkerSessionStore.inMemory()
        store.saveWorker(sampleWorker())

        val msg1 = AgentMessage.user("First message " + "a".repeat(2_000))
        val msg2 = AgentMessage.assistant("First reply " + "b".repeat(2_000))
        val msg3 = AgentMessage.user("Follow-up question")
        val msg4 = AgentMessage.assistant("Follow-up answer")
        val initial = listOf(msg1, msg2, msg3, msg4)
        store.saveMessages("w-1", initial, interrupted = false)

        // Compact to retain only msg3 + msg4 with a summary.
        val compacted = store.compact(
            expected = initial,
            retained = listOf(msg3, msg4),
            summary = "Summarised first exchange.",
            attempt = null,
            expectedSessionId = "w-1",
        )
        assertTrue(compacted.any { it.text == "Follow-up question" })
        assertEquals("Summarised first exchange.", store.loadWorker("w-1")!!.summary)
        // Full event history still holds all 4 original events.
        assertEquals(4, store.fullEventCount("w-1"))

        // Exceeding 512 KiB in active messages throws AgentSessionLimitException.
        val huge = AgentMessage.user("x".repeat(520 * 1024))
        val threw = runCatching {
            store.saveMessages("w-1", compacted + huge, interrupted = false)
        }.exceptionOrNull()
        assertTrue(threw is AgentSessionLimitException)
    }

    @Test
    fun tracksSpawnGrantsDeletesForSessionAndPrunesOldFinishedRunTranscripts() = runBlocking {
        val store = WorkerSessionStore.inMemory()
        assertFalse(store.hasSpawnGrant("sess-1"))
        store.grantSpawn("sess-1", now = 100L)
        assertTrue(store.hasSpawnGrant("sess-1"))

        val now = 30L * 24L * 60L * 60L * 1000L
        val oldTime = now - WorkerSessionStore.RETENTION_MS - 1_000L
        val oldDone = sampleWorker(id = "w-old", runId = "run-old", sessionId = "sess-keep", state = RunPhase.Done, updatedAt = oldTime)
        val recentDone = sampleWorker(id = "w-recent", runId = "run-recent", sessionId = "sess-keep", state = RunPhase.Done, updatedAt = now - 1_000L)
        val deletedSessWorker = sampleWorker(id = "w-del", runId = "run-del", sessionId = "sess-1", state = RunPhase.Done, updatedAt = now)

        store.saveWorker(oldDone)
        store.saveMessages("w-old", listOf(AgentMessage.user("old brief"), AgentMessage.assistant("old done")), interrupted = false)
        store.saveWorker(recentDone)
        store.saveMessages("w-recent", listOf(AgentMessage.user("recent brief"), AgentMessage.assistant("recent done")), interrupted = false)
        store.saveWorker(deletedSessWorker)
        store.saveMessages("w-del", listOf(AgentMessage.user("del brief")), interrupted = false)

        // Deleting a parent session removes its workers, transcripts, and spawn grant.
        store.deleteForSession("sess-1")
        assertFalse(store.hasSpawnGrant("sess-1"))
        assertNull(store.loadWorker("w-del"))

        // Pruning removes heavy transcript rows for finished runs older than 14 days while keeping the workers summary row.
        val prunedCount = store.prune(now = now)
        assertEquals(1, prunedCount)
        assertNotNull("Worker summary row must remain after transcript prune", store.loadWorker("w-old"))
        assertEquals(0, store.fullEventCount("w-old"))
        assertEquals(2, store.fullEventCount("w-recent"))
    }

    @Test
    fun contextEstimateUsesReportedUsageAnchorForWorker() = runBlocking {
        val store = WorkerSessionStore.inMemory()
        store.saveWorker(sampleWorker())
        val messages = listOf(AgentMessage.user("hello"), AgentMessage.assistant("world"))
        store.saveMessages("w-1", messages, interrupted = false)

        store.recordUsage(
            sessionId = "w-1",
            turnId = "turn-1",
            providerId = "cfg-1",
            model = "claude-sonnet-4-6",
            usage = AgentUsage(inputTokens = 321L, outputTokens = 45L),
            requestMessages = messages,
            system = "sys",
            tools = emptyList(),
            endpoint = "https://api.anthropic.com",
            providerType = ProviderType.Anthropic,
            reasoning = ReasoningLevel.Default,
        )
        val estimate = store.contextEstimate(
            sessionId = "w-1",
            providerId = "cfg-1",
            model = "claude-sonnet-4-6",
            messages = messages,
            system = "sys",
            tools = emptyList(),
            endpoint = "https://api.anthropic.com",
            reasoning = ReasoningLevel.Default,
        )
        assertEquals("reported", estimate.source)
        assertEquals(321L, estimate.tokens)
    }
}
