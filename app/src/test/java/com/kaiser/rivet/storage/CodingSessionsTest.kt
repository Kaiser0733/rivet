package com.kaiser.rivet.storage

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.AgentUsage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CodingSessionsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun reset() = runBlocking {
        app.deleteDatabase("coding-sessions.db")
        app.getSharedPreferences("workspace", Context.MODE_PRIVATE).edit().clear().commit()
        AgentSessionStore(app).clear()
    }

    @Test fun oldTranscriptMigratesOnceAndRemainsInDataStore() = runBlocking {
        val original = listOf(
            AgentMessage.user("modify this file"),
            AgentMessage.assistant("", listOf(AgentToolCall("c1", "read_file", "{}"))),
            AgentMessage.tools(listOf(AgentToolResult("c1", "read_file", "{\"content\":\"ok\"}"))),
        )
        val legacy = AgentSessionStore(app)
        legacy.save(original, interrupted = true)
        val sessions = CodingSessions(app)

        val migrated = sessions.load()
        assertEquals(original, migrated.messages)
        assertTrue(migrated.interrupted)
        assertEquals(1, sessions.list().size)
        assertEquals(original, legacy.load().messages)

        val reopened = CodingSessions(app)
        assertEquals(original, reopened.load().messages)
        assertEquals(1, reopened.list().size)
    }

    @Test fun sessionsResumeIndependentlyAndDeletionIsIsolated() = runBlocking {
        val sessions = CodingSessions(app)
        val first = sessions.load()
        val firstId = first.id!!
        sessions.save(listOf(AgentMessage.user("first")), interrupted = false)
        val second = sessions.create("content://test/workspace")
        val secondId = second.id!!
        assertNotEquals(firstId, secondId)
        sessions.save(listOf(AgentMessage.user("second")), interrupted = true)
        assertEquals("content://test/workspace", sessions.load().workspaceId)
        assertEquals(listOf("second"), sessions.recent(secondId).map { it.text })

        assertEquals("first", sessions.select(firstId).messages.single().text)
        sessions.rename(firstId, "Project work")
        assertEquals("Project work", sessions.list().first { it.id == firstId }.title)
        sessions.delete(secondId)
        assertEquals("first", sessions.load().messages.single().text)
        assertFalse(sessions.load().interrupted)
        assertEquals(1, sessions.list().size)
    }

    @Test fun failedMigrationLeavesOriginalAndCanRetry() = runBlocking {
        val oversized = "[\"" + "x".repeat(AgentSessionCodec.MAX_SERIALIZED_BYTES) + "\"]"
        app.chatData.edit { it[stringPreferencesKey("agent_messages")] = oversized }
        val sessions = CodingSessions(app)
        try { sessions.load(); throw AssertionError("Expected limit") }
        catch (_: AgentSessionLimitException) { Unit }
        assertEquals(oversized, app.chatData.data.first()[stringPreferencesKey("agent_messages")])

        AgentSessionStore(app).save(listOf(AgentMessage.user("safe")), interrupted = false)
        assertEquals("safe", sessions.load().messages.single().text)
        assertEquals(1, sessions.list().size)
    }

    @Test fun usageStaysWithSessionAndModelAfterRestart() = runBlocking {
        val sessions = CodingSessions(app)
        val first = sessions.load().id!!
        sessions.recordUsage(first, "turn-1", "anthropic", "claude", AgentUsage(100, 25, 40))
        sessions.recordUsage(first, "turn-1", "custom", "local", null)
        val second = sessions.create(null).id!!
        sessions.recordUsage(second, "turn-2", "gemini", "gemini", AgentUsage(60, 15))

        val reopened = CodingSessions(app)
        assertEquals(100L, reopened.usage(first).reportedInputTokens)
        assertEquals(25L, reopened.usage(first).reportedOutputTokens)
        assertEquals(1, reopened.usage(first).unknownRequests)
        assertEquals(60L, reopened.usage(second).reportedInputTokens)
    }

    @Test fun repeatedCompactionKeepsFullHistoryBeyondOldDataStoreLimit() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        var active = emptyList<AgentMessage>()
        repeat(4) { index ->
            active += AgentMessage.user("request $index " + "x".repeat(180_000))
            active += AgentMessage.assistant("done $index")
            sessions.save(active, interrupted = false)
            val retained = listOf(AgentMessage.user("continue $index"))
            // Retained events must come from the original active transcript.
            active += retained.single()
            sessions.save(active, interrupted = false)
            sessions.compact(active, retained, "summary through $index")
            active = retained
        }
        val reopened = CodingSessions(app)
        val restored = reopened.load()
        assertEquals(listOf(AgentMessage.user("continue 3")), restored.messages)
        assertEquals("summary through 3", restored.summary)
        assertEquals(12, reopened.fullEventCount(id))
        assertTrue(reopened.recent(id, 100).sumOf { it.text.length } > AgentSessionCodec.MAX_SERIALIZED_BYTES)
    }

    @Test fun failedCompactionLeavesActiveAndFullEventsUnchanged() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val original = listOf(AgentMessage.user("request"), AgentMessage.assistant("answer"))
        sessions.save(original, interrupted = false)
        try {
            sessions.compact(original, listOf(AgentMessage.user("invented")), "summary")
            throw AssertionError("Expected rejected active rewrite")
        } catch (_: IllegalArgumentException) { Unit }
        assertEquals(original, sessions.load().messages)
        assertEquals(2, sessions.fullEventCount(id))
    }

    @Test fun usageAnchorPricesOnlyNewDeltaAndInvalidatesOnCompactionOrModelSwitch() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        var active = listOf(AgentMessage.user("initial request"))
        sessions.save(active, interrupted = true)
        sessions.recordUsage(id, "turn", "provider-a", "model-a", AgentUsage(100, 25), active, "system")
        assertEquals(ContextEstimate(125, "reported"),
            sessions.contextEstimate(id, "provider-a", "model-a", active, "system"))
        active = active + AgentMessage.assistant("answer") + AgentMessage.user("more " + "x".repeat(400))
        sessions.save(active, interrupted = false)
        val delta = sessions.contextEstimate(id, "provider-a", "model-a", active, "system")
        assertEquals("estimated", delta.source)
        assertTrue(delta.tokens!! > 125)
        assertEquals("estimated", sessions.contextEstimate(id, "provider-a", "model-b", active, "system").source)

        val retained = listOf(active.last())
        sessions.compact(active, retained, "short summary")
        assertEquals("estimated", sessions.contextEstimate(id, "provider-a", "model-a", retained, "system").source)
    }
}
