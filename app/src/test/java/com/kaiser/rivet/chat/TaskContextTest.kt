package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.InternalContext
import com.kaiser.rivet.agent.modelMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskContextTest {
    @Test
    fun projectInstructionsAndSummaryRemainDistinctUntrustedBlocks() {
        val old = AgentMessage.user("Earlier request")
        val current = AgentMessage.user("Fix the crash")
        val guidance = InternalContext(kind = "project", workspaceId = "tree", scope = "AGENTS.md",
            content = "Run ./gradlew test", digest = InternalContext.digest("Run ./gradlew test")).event()
        val summary = InternalContext.summary("Changed Auth.kt", "tree")
        val result = modelMessages(listOf(old, AgentMessage.assistant("Working"), guidance, summary, current))

        assertSame(old, result[0])
        assertEquals(AgentRole.User, result[2].role)
        assertTrue(result[2].text.indexOf("untrusted") < result[2].text.indexOf("Run ./gradlew test"))
        assertTrue(result[3].text.contains("Changed Auth.kt"))
        assertSame(current, result[4])
        assertEquals("Fix the crash", current.text)
    }

    @Test
    fun noContextLeavesProviderHistoryUnchanged() {
        val history = listOf(AgentMessage.user("Keep this exact"))
        assertEquals(history, modelMessages(history))
        assertSame(history.single(), modelMessages(history).single())
    }
}
