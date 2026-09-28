package com.kaiser.rivet.storage

import android.app.Application
import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentContext
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.AgentToolError
import com.kaiser.rivet.agent.AgentUsage
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.provider.ProviderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    @Test fun preAgentChatHistorySurvivesBothMigrationsAndSqliteRestart() = runBlocking {
        val payload = """[{"role":"User","text":"old question"},{"role":"Assistant","text":"old answer"}]"""
        app.chatData.edit {
            it.remove(booleanPreferencesKey("agent_migrated"))
            it[stringPreferencesKey("messages")] = payload
        }

        val imported = CodingSessions(app).load()
        assertEquals(listOf("old question", "old answer"), imported.messages.map { it.text })
        assertEquals(listOf(AgentMessage.user("old question"), AgentMessage.assistant("old answer")),
            imported.messages)
        assertEquals(payload, app.chatData.data.first()[stringPreferencesKey("messages")])
        assertEquals(imported.messages, CodingSessions(app).load().messages)
        assertEquals(1, CodingSessions(app).list().size)
    }

    @Test fun interruptedToolCallGetsOneCorrelatedUnknownResultOnRestart() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val call = AgentToolCall("pending-1", "write_file", "{}")
        val pending = listOf(AgentMessage.user("edit"), AgentMessage.assistant("", listOf(call)))
        sessions.save(pending, interrupted = true)

        val restored = CodingSessions(app).load()
        assertTrue(restored.interrupted)
        assertEquals(3, restored.messages.size)
        val result = restored.messages.last().toolResults.single()
        assertEquals(call.id, result.callId)
        assertEquals(call.name, result.name)
        assertTrue(result.error)
        assertTrue(result.content.contains("outcome is unknown"))
        assertEquals(3, CodingSessions(app).fullEventCount(id))
        assertEquals(3, CodingSessions(app).load().messages.size)
        sessions.markInterrupted(false)
        assertEquals(3, CodingSessions(app).load().messages.size)
    }

    @Test fun danglingToolCallCannotBeMarkedClean() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val call = AgentToolCall("pending", "create_file", "{}")
        val pending = listOf(AgentMessage.user("create"), AgentMessage.assistant("", listOf(call)))
        sessions.save(pending, interrupted = false)
        assertTrue(sessions.list().single { it.id == id }.interrupted)

        sessions.markInterrupted(false)

        assertTrue(sessions.list().single { it.id == id }.interrupted)
        assertEquals(pending, sessions.recent(id))
    }

    @Test fun oldCleanFlagOrphanIsRecoveredWithoutReplayingItsTool() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val call = AgentToolCall("old-pending", "write_file", "{}")
        sessions.save(listOf(AgentMessage.user("edit"), AgentMessage.assistant("", listOf(call))), true)
        app.openOrCreateDatabase("coding-sessions.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("UPDATE sessions SET interrupted=0 WHERE id=?", arrayOf(id))
        }

        val restored = CodingSessions(app).load()

        assertTrue(restored.interrupted)
        assertEquals(call.id, restored.messages.last().toolResults.single().callId)
        assertTrue(restored.messages.last().toolResults.single().content.contains("outcome is unknown"))
        assertEquals(3, CodingSessions(app).fullEventCount(id))
        assertEquals(3, CodingSessions(app).load().messages.size)
    }

    @Test fun pendingToolCallCannotBeBuriedByANewUserEvent() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val call = AgentToolCall("pending", "create_file", "{}")
        val pending = listOf(AgentMessage.user("create"), AgentMessage.assistant("", listOf(call)))
        sessions.save(pending, interrupted = true)

        try {
            sessions.save(pending + AgentMessage.user("continue"), interrupted = false)
            error("A new event must not bury an unresolved tool call")
        } catch (_: IllegalArgumentException) { }

        assertEquals(pending, sessions.recent(id))
        assertTrue(sessions.list().single { it.id == id }.interrupted)
        assertEquals(2, sessions.fullEventCount(id))
        assertEquals(call.id, sessions.load().messages.last().toolResults.single().callId)
    }

    @Test fun interruptedRecoveryAtActiveLimitPreservesPriorValidSummary() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val initial = listOf(AgentMessage.user("Older task"), AgentMessage.assistant("Done"),
            AgentMessage.user("Continue"))
        sessions.save(initial, interrupted = false)
        sessions.compact(initial, listOf(initial.last()), "Keep the earlier objective")
        val call = AgentToolCall("pending", "write_file", "{}")
        val synthetic = AgentMessage.tools(listOf(AgentToolResult(call.id, call.name,
            AgentToolError.content("interrupted"), error = true, summary = "Interrupted write_file")))
        fun pending(size: Int) = listOf(initial.last(), AgentMessage.user("x".repeat(size)),
            AgentMessage.assistant("", listOf(call)))
        var low = 0
        var high = AgentSessionCodec.MAX_SERIALIZED_BYTES
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (AgentSessionCodec.fits(pending(middle), "Keep the earlier objective".toByteArray().size))
                low = middle else high = middle - 1
        }
        val nearLimit = pending(low)
        assertFalse(AgentSessionCodec.fits(nearLimit + synthetic,
            "Keep the earlier objective".toByteArray().size))
        sessions.save(nearLimit, interrupted = true)

        val firstLoad = CodingSessions(app).load()
        val secondLoad = CodingSessions(app).load()

        assertTrue(firstLoad.interrupted)
        assertEquals("Keep the earlier objective", firstLoad.summary)
        assertEquals("Keep the earlier objective", secondLoad.summary)
        assertEquals(nearLimit.dropLast(1), secondLoad.messages)
        assertEquals(1, sessions.recent(id).last().toolResults.count { it.callId == call.id })
        assertEquals(6, sessions.fullEventCount(id))
    }

    @Test fun migratedInterruptedToolCallIsRecoveredWithoutRerunningIt() = runBlocking {
        val call = AgentToolCall("legacy-pending", "run_command", "{}")
        AgentSessionStore(app).save(listOf(AgentMessage.user("run"),
            AgentMessage.assistant("", listOf(call))), interrupted = true)
        val migrated = CodingSessions(app).load()
        assertEquals(call.id, migrated.messages.last().toolResults.single().callId)
        assertTrue(migrated.messages.last().toolResults.single().error)
        assertEquals(3, CodingSessions(app).fullEventCount(migrated.id!!))
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

    @Test fun unreadableSessionCannotReplaceCurrentSelection() = runBlocking {
        val sessions = CodingSessions(app)
        val good = sessions.load().id!!
        val broken = sessions.create(null).id!!
        sessions.save(listOf(AgentMessage.user("will be corrupt")), interrupted = false)
        sessions.select(good)
        app.openOrCreateDatabase("coding-sessions.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("UPDATE active_events SET payload=? WHERE session_id=?", arrayOf("{broken", broken))
        }

        try { sessions.select(broken); throw AssertionError("Expected decode failure") }
        catch (_: SerializationException) { Unit }
        assertEquals(good, sessions.load().id)
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

    @Test fun earlierSqliteSchemaUpgradesWithoutLosingEvents() = runBlocking {
        val id = "legacy-session"
        val original = AgentMessage.user("before schema upgrade")
        val db = app.openOrCreateDatabase("coding-sessions.db", Context.MODE_PRIVATE, null)
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, workspace_id TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, interrupted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("INSERT INTO sessions(id,title,created_at,updated_at,interrupted) VALUES(?,?,?,?,?)",
            arrayOf(id, "Legacy", 1L, 1L, 1))
        db.execSQL("INSERT INTO events(session_id,payload) VALUES(?,?)",
            arrayOf(id, Json.encodeToString(AgentMessage.serializer(), original)))
        db.execSQL("INSERT INTO metadata(key,value) VALUES('active_session',?)", arrayOf(id))
        db.execSQL("INSERT INTO metadata(key,value) VALUES('legacy_migrated','1')")
        db.version = 1
        db.close()

        val upgraded = CodingSessions(app)
        assertEquals(listOf(original), upgraded.load().messages)
        assertEquals(1, upgraded.fullEventCount(id))
        upgraded.recordUsage(id, "after-upgrade", "provider", "model", AgentUsage(3, 2))
        assertEquals(1, CodingSessions(app).usage(id).reportedRequests)
        assertEquals(listOf(original), CodingSessions(app).load().messages)
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

    @Test fun clearingCurrentSessionAlsoClearsUsageAndCompactionState() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val history = listOf(AgentMessage.user("first"), AgentMessage.assistant("done"),
            AgentMessage.user("second"))
        sessions.save(history, interrupted = false)
        sessions.compact(history, history.takeLast(1), "older task")
        sessions.recordUsage(id, "turn", "provider", "model", AgentUsage(10, 5))
        sessions.clear()

        val reopened = CodingSessions(app)
        assertTrue(reopened.load().messages.isEmpty())
        assertEquals("", reopened.load().summary)
        assertEquals(0, reopened.fullEventCount(id))
        assertEquals(0, reopened.usage(id).reportedRequests)
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

    @Test fun summaryOnlyReductionKeepsCanonicalAndActiveMessages() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val current = listOf(AgentMessage.user("Continue"))
        sessions.save(current, interrupted = false)

        sessions.compact(current, current, "Short task state")

        val reopened = CodingSessions(app)
        assertEquals(current, reopened.load().messages)
        assertEquals(current, reopened.recent(id))
        assertEquals("Short task state", reopened.load().summary)
        assertEquals(1, reopened.fullEventCount(id))
    }

    @Test fun blankAssistantRetractionAfterCompactionKeepsCanonicalPrefix() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val original = listOf(AgentMessage.user("Original task"), AgentMessage.assistant("Done"),
            AgentMessage.user("Continue"))
        sessions.save(original, interrupted = false)
        val retained = listOf(original.last())
        sessions.compact(original, retained, "Prior work completed")
        sessions.save(retained + AgentMessage.assistant(""), interrupted = true)

        sessions.save(retained, interrupted = false)

        val reopened = CodingSessions(app)
        assertEquals(original, reopened.recent(id))
        assertEquals(3, reopened.fullEventCount(id))
        assertEquals(retained, reopened.load().messages)
        assertEquals("Prior work completed", reopened.load().summary)
    }

    @Test fun projectedToolPruningKeepsCanonicalHistoryAndValidatesStoredSummary() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val call = AgentToolCall("read", "read_file", "{\"path\":\"A.kt\"}")
        val original = listOf(
            AgentMessage.user("Inspect A"),
            AgentMessage.assistant("", listOf(call)),
            AgentMessage.tools(listOf(AgentToolResult("read", "read_file",
                "{\"content\":\"${"x".repeat(22_000)}\"}", summary = "Read A.kt"))),
            AgentMessage.user("Continue the task"),
        )
        sessions.save(original, interrupted = false)
        val projected = AgentContext.pruneOldResults(original, protectedTailBytes = 1024)!!
        sessions.compact(original, projected, "Prior work was inspected")

        assertEquals(projected, CodingSessions(app).load().messages)
        assertEquals(original, CodingSessions(app).recent(id))
        app.openOrCreateDatabase("coding-sessions.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("UPDATE sessions SET summary='Unverified replacement' WHERE id=?", arrayOf(id))
        }
        val recovered = CodingSessions(app).load()
        assertEquals("", recovered.summary)
        assertTrue(recovered.messages.any { it.role == com.kaiser.rivet.agent.AgentRole.User &&
            it.text == "Continue the task" })
        assertEquals(original, CodingSessions(app).recent(id))
    }

    @Test fun changedCanonicalPrefixCannotReuseCompactionSummary() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val original = listOf(AgentMessage.user("Original task"), AgentMessage.assistant("Done"),
            AgentMessage.user("Continue"))
        sessions.save(original, interrupted = false)
        sessions.compact(original, listOf(original.last()), "Original task was done")
        app.openOrCreateDatabase("coding-sessions.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("UPDATE events SET payload=? WHERE id=(SELECT MIN(id) FROM events WHERE session_id=?)",
                arrayOf(Json.encodeToString(AgentMessage.serializer(), AgentMessage.user("Changed canonical task")), id))
        }

        val restored = CodingSessions(app).load()
        assertEquals("", restored.summary)
        assertEquals("Changed canonical task", CodingSessions(app).recent(id).first().text)
        assertTrue(restored.messages.any { it.text == "Continue" })
    }

    @Test fun versionFourCompactionMigratesOnceWithoutLosingHistoryOrUsage() = runBlocking {
        val id = "v4-session"
        val events = listOf(AgentMessage.user("Original objective"), AgentMessage.assistant("Investigated"),
            AgentMessage.user("Continue"))
        app.openOrCreateDatabase("coding-sessions.db", Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, title TEXT NOT NULL, workspace_id TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, interrupted INTEGER NOT NULL DEFAULT 0, summary TEXT NOT NULL DEFAULT '', active_generation INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE active_events (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE compactions (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, through_event_id INTEGER NOT NULL, summary TEXT NOT NULL, before_count INTEGER NOT NULL, after_count INTEGER NOT NULL, created_at INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE usage (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, turn_id TEXT NOT NULL, provider_id TEXT NOT NULL, model TEXT NOT NULL, source TEXT NOT NULL, input_tokens INTEGER, output_tokens INTEGER, cache_read_tokens INTEGER, reasoning_tokens INTEGER, total_tokens INTEGER, created_at INTEGER NOT NULL, base_count INTEGER NOT NULL DEFAULT 0, base_last_hash TEXT, system_hash TEXT, active_generation INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO sessions(id,title,workspace_id,created_at,updated_at,summary,active_generation) VALUES(?,?,?,?,?,?,?)",
                arrayOf(id, "Previous work", "content://v4/project", 1L, 1L, "Original objective remains", 1))
            events.forEach { message -> db.execSQL("INSERT INTO events(session_id,payload) VALUES(?,?)",
                arrayOf(id, Json.encodeToString(AgentMessage.serializer(), message))) }
            db.execSQL("INSERT INTO active_events(session_id,payload) VALUES(?,?)",
                arrayOf(id, Json.encodeToString(AgentMessage.serializer(), events.last())))
            db.execSQL("INSERT INTO compactions(session_id,through_event_id,summary,before_count,after_count,created_at) VALUES(?,?,?,?,?,?)",
                arrayOf(id, 3L, "Original objective remains", 3, 1, 1L))
            db.execSQL("INSERT INTO usage(session_id,turn_id,provider_id,model,source,input_tokens,output_tokens,created_at) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf(id, "turn", "provider", "model", "reported", 100, 10, 1L))
            db.execSQL("INSERT INTO metadata(key,value) VALUES('active_session',?)", arrayOf(id))
            db.execSQL("INSERT INTO metadata(key,value) VALUES('legacy_migrated','1')")
            db.version = 4
        }

        repeat(2) {
            val sessions = CodingSessions(app)
            assertEquals(events.last(), sessions.load().messages.single())
            assertEquals("Original objective remains", sessions.load().summary)
            assertEquals(events, sessions.recent(id))
            assertEquals(1, sessions.usage(id).reportedRequests)
        }
        app.openOrCreateDatabase("coding-sessions.db", Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT prefix_hash,active_hash,summary_hash FROM compactions", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue((0..2).all { !cursor.isNull(it) })
            }
        }
    }

    @Test fun usageAnchorPricesOnlyNewDeltaAndInvalidatesOnCompactionOrModelSwitch() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        var active = listOf(AgentMessage.user("initial request"))
        sessions.save(active, interrupted = true)
        sessions.recordUsage(id, "turn", "provider-a", "model-a", AgentUsage(100, 25), active, "system")
        assertEquals(ContextEstimate(100, "reported"),
            sessions.contextEstimate(id, "provider-a", "model-a", active, "system"))
        active = active + AgentMessage.assistant("answer") + AgentMessage.user("more " + "x".repeat(400))
        sessions.save(active, interrupted = false)
        val delta = sessions.contextEstimate(id, "provider-a", "model-a", active, "system")
        assertEquals("estimated", delta.source)
        assertTrue(delta.tokens!! > 100)
        assertEquals("estimated", sessions.contextEstimate(id, "provider-a", "model-b", active, "system").source)

        val retained = listOf(active.last())
        sessions.compact(active, retained, "short summary")
        assertEquals("estimated", sessions.contextEstimate(id, "provider-a", "model-a", retained, "system").source)
    }

    @Test fun exactRequestAnchorIncludesToolsEndpointAndAnthropicCachedInput() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val messages = listOf(AgentMessage.user("Project instructions and task"))
        val tools = listOf(AgentToolDefinition("read_file", "Read a bounded chunk",
            buildJsonObject { put("type", "object") }))
        sessions.save(messages, interrupted = true)
        sessions.recordUsage(id, "turn", "anthropic", "claude", AgentUsage(20, 5, 70,
            cacheCreationTokens = 30), messages, "system", tools = tools,
            endpoint = "https://api.anthropic.com", providerType = ProviderType.Anthropic)

        val reopened = CodingSessions(app)
        assertEquals(ContextEstimate(120, "reported"), reopened.contextEstimate(id, "anthropic",
            "claude", messages, "system", tools = tools, endpoint = "https://api.anthropic.com"))
        assertEquals(20L, reopened.usage(id).reportedInputTokens)
        assertEquals("estimated", reopened.contextEstimate(id, "anthropic", "claude",
            listOf(AgentMessage.user("Changed project instructions")), "system", tools = tools,
            endpoint = "https://api.anthropic.com").source)
        assertEquals("estimated", reopened.contextEstimate(id, "anthropic", "claude", messages,
            "system", tools = emptyList(), endpoint = "https://api.anthropic.com").source)
        assertEquals("estimated", reopened.contextEstimate(id, "anthropic", "claude", messages,
            "system", tools = tools, endpoint = "https://other.example").source)
    }

    @Test fun outputOnlyUsageCannotBecomeTrustedInputAnchor() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val messages = listOf(AgentMessage.user("Question"))
        sessions.save(messages, interrupted = true)
        sessions.recordUsage(id, "turn", "provider", "model", AgentUsage(outputTokens = 10), messages,
            "system")
        assertEquals(1, sessions.usage(id).unknownRequests)
        assertEquals("estimated", sessions.contextEstimate(id, "provider", "model", messages,
            "system").source)
    }

    @Test fun compactionRequestUsageDoesNotShadowNormalRequestAnchor() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val messages = listOf(AgentMessage.user("Continue the task"))
        sessions.save(messages, interrupted = true)
        sessions.recordUsage(id, "turn", "provider", "model", AgentUsage(500, 20), messages,
            "normal system")
        sessions.recordUsage(id, "compaction-turn", "provider", "model", AgentUsage(40, 10),
            listOf(AgentMessage.user("Older history")), "summary system")

        assertEquals(ContextEstimate(500, "reported"), sessions.contextEstimate(id, "provider",
            "model", messages, "normal system"))
        assertEquals(2, sessions.usage(id).reportedRequests)
    }

    @Test fun boundedLocalCompactionAttemptsKeepReasonBoundaryAndFailure() = runBlocking {
        val sessions = CodingSessions(app)
        val id = sessions.load().id!!
        val history = listOf(AgentMessage.user("Original task"), AgentMessage.assistant("Done"),
            AgentMessage.user("Continue"))
        sessions.save(history, interrupted = false)
        sessions.compact(history, listOf(history.last()), "task state",
            ContextAttempt("automatic", "estimated", 12_000, 4_000, "provider", "model"))
        sessions.recordContextFailure(id,
            ContextAttempt("overflow", "reported", 20_000, null, "provider", "model",
                failure = "context_summary_invalid"))
        app.openOrCreateDatabase("coding-sessions.db", Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT reason,estimate_source,through_event_id,outcome,failure_code " +
                "FROM context_attempts WHERE session_id=? ORDER BY id", arrayOf(id)).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("automatic", cursor.getString(0))
                assertEquals("estimated", cursor.getString(1))
                assertTrue(cursor.getLong(2) > 0)
                assertEquals("compacted", cursor.getString(3))
                assertTrue(cursor.moveToNext())
                assertEquals("overflow", cursor.getString(0))
                assertEquals("reported", cursor.getString(1))
                assertEquals("failed", cursor.getString(3))
                assertEquals("context_summary_invalid", cursor.getString(4))
                assertFalse(cursor.moveToNext())
            }
        }
        assertEquals(listOf(history.last()), CodingSessions(app).load().messages)
        assertEquals(history, sessions.recent(id))
        repeat(65) { index ->
            sessions.recordContextFailure(id, ContextAttempt("overflow", "estimated", index.toLong(),
                null, "provider", "model", failure = "context_headroom_unavailable"))
        }
        app.openOrCreateDatabase("coding-sessions.db", Context.MODE_PRIVATE, null).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM context_attempts WHERE session_id=?", arrayOf(id)).use {
                it.moveToFirst()
                assertEquals(64, it.getInt(0))
            }
        }
    }

    @Test fun compactionCannotApplyToAnotherSelectedSessionWithMatchingMessages() = runBlocking {
        val sessions = CodingSessions(app)
        val first = sessions.load().id!!
        val history = listOf(AgentMessage.user("Same task"), AgentMessage.assistant("Done"))
        sessions.save(history, interrupted = false)
        val second = sessions.create(null).id!!
        sessions.save(history, interrupted = false)

        try {
            sessions.compact(history, listOf(history.last()), "wrong task", expectedSessionId = first)
            throw AssertionError("Expected selected-session guard")
        } catch (_: IllegalStateException) { Unit }

        assertEquals(history, sessions.load().messages)
        assertEquals("", sessions.load().summary)
        assertEquals(history, sessions.select(first).messages)
        assertEquals(history, sessions.select(second).messages)
    }

    @Test fun staleCompactionFromAnotherStoreCannotDropNewlySavedEvent() = runBlocking {
        val first = CodingSessions(app)
        val id = first.load().id!!
        val original = listOf(AgentMessage.user("Inspect"), AgentMessage.assistant("Done"))
        first.save(original, interrupted = false)
        val second = CodingSessions(app)
        assertEquals(original, second.load().messages)
        val newer = original + AgentMessage.user("New instruction")
        second.save(newer, interrupted = false)

        try {
            first.compact(original, listOf(original.last()), "stale task", expectedSessionId = id)
            throw AssertionError("Expected stale compaction rejection")
        } catch (_: IllegalStateException) { Unit }

        assertEquals(newer, CodingSessions(app).load().messages)
        assertEquals(newer, first.recent(id))
        assertEquals("", first.load().summary)
    }
}
