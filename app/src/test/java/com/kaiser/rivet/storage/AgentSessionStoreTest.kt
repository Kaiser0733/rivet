package com.kaiser.rivet.storage

import android.content.Context
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.chat.ChatMessage
import com.kaiser.rivet.chat.ChatRole
import java.io.File
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

    @Test
    fun migrationRestorationInterruptionAndClearAreDurable() = runTest {
        File(context.filesDir, "datastore/chat.preferences_pb").delete()
        val legacy = listOf(ChatMessage(ChatRole.User, "old"), ChatMessage(ChatRole.Assistant, "reply"))
        ChatStore(context).save(legacy)
        val store = AgentSessionStore(context)

        val first = store.load()
        store.save(first.messages + AgentMessage.user("new"), interrupted = false)
        val second = store.load()

        assertEquals(listOf("old", "reply"), first.messages.map { it.text })
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
}
