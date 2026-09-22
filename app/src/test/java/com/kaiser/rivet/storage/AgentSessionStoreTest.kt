package com.kaiser.rivet.storage

import android.content.Context
import androidx.datastore.preferences.core.clear
import androidx.datastore.preferences.core.edit
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.chat.ChatMessage
import com.kaiser.rivet.chat.ChatRole
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentSessionStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private suspend fun resetStore() {
        context.chatData.edit { it.clear() }
    }

    @Test
    fun migrationRestorationInterruptionAndClearAreDurable() = runTest {
        resetStore()
        val legacy = listOf(ChatMessage(ChatRole.User, "old"), ChatMessage(ChatRole.Assistant, "reply"))
        ChatStore(context).save(legacy)
        val store = AgentSessionStore(context)

        val first = store.load()
        val repeatedMigration = store.load()
        store.save(first.messages + AgentMessage.user("new"), interrupted = false)
        val second = store.load()

        assertEquals(listOf("old", "reply"), first.messages.map { it.text })
        assertEquals(first, repeatedMigration)
        assertEquals(listOf("old", "reply", "new"), second.messages.map { it.text })
        assertEquals(legacy, ChatStore(context).messages.first())

        val messages = listOf(AgentMessage.tools(listOf(
            AgentToolResult("c", "delete_path", "{\"error\":\"denied\"}", error = true),
        )))
        store.save(messages, interrupted = true)

        val restored = store.load()

        assertEquals(messages, restored.messages)
        assertTrue(restored.interrupted)

        store.clear()
        val cleared = store.load()

        assertTrue(cleared.messages.isEmpty())
        assertFalse(cleared.interrupted)
    }

    @Test
    fun serializedSessionBelowLimitPersists() = runTest {
        resetStore()
        val messages = listOf(AgentMessage.user("x".repeat(100_000)))
        val store = AgentSessionStore(context)

        store.save(messages, interrupted = false)

        assertEquals(messages, store.load().messages)
        assertTrue(AgentSessionCodec.encode(messages).byteSize < AgentSessionCodec.MAX_SERIALIZED_BYTES)
    }

    @Test
    fun oversizedSavePreservesPreviousSessionAndInterruptedMarker() = runTest {
        resetStore()
        val store = AgentSessionStore(context)
        val previous = listOf(AgentMessage.user("keep"))
        store.save(previous, interrupted = true)
        val oversized = listOf(AgentMessage.user("🙂".repeat(AgentSessionCodec.MAX_SERIALIZED_BYTES)))

        var rejected = false
        try {
            store.save(oversized, interrupted = false)
        } catch (_: AgentSessionLimitException) {
            rejected = true
        }

        val restored = store.load()
        assertTrue(rejected)
        assertEquals(previous, restored.messages)
        assertTrue(restored.interrupted)
    }

    @Test
    fun repeatedToolReadsCannotGrowStoredValuePastLimit() = runTest {
        resetStore()
        val store = AgentSessionStore(context)
        var lastGood = listOf(AgentMessage.user("inspect"))
        store.save(lastGood, interrupted = true)
        var rejected = false

        for (index in 0 until 100) {
            val candidate = lastGood + AgentMessage.tools(listOf(
                AgentToolResult("read-$index", "read_file", "x".repeat(8 * 1024)),
            ))
            try {
                store.save(candidate, interrupted = true)
                lastGood = candidate
            } catch (_: AgentSessionLimitException) {
                rejected = true
                break
            }
        }

        val restored = store.load()
        assertTrue(rejected)
        assertEquals(lastGood, restored.messages)
        assertTrue(AgentSessionCodec.encode(restored.messages).byteSize <= AgentSessionCodec.MAX_SERIALIZED_BYTES)
    }

    @Test
    fun oversizedLegacyHistoryRemainsUntouchedWhenMigrationCannotFit() = runTest {
        resetStore()
        val legacy = listOf(ChatMessage(ChatRole.User, "x".repeat(AgentSessionCodec.MAX_SERIALIZED_BYTES)))
        ChatStore(context).save(legacy)
        val store = AgentSessionStore(context)

        var failures = 0
        repeat(2) {
            try {
                store.load()
            } catch (_: AgentSessionLimitException) {
                failures++
            }
        }

        assertEquals(2, failures)
        assertEquals(legacy, ChatStore(context).messages.first())
    }
}
