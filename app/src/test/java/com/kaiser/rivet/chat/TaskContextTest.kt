package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskContextTest {
    @Test
    fun projectInstructionsAndSummaryStayInUserContextBeforeCurrentRequest() {
        val old = AgentMessage.user("Earlier request")
        val current = AgentMessage.user("Fix the crash")
        val history = listOf(old, AgentMessage.assistant("Working"), current)

        val result = addUntrustedTaskContext(history, "Run ./gradlew test", "Changed Auth.kt")

        assertSame(old, result[0])
        assertEquals(AgentRole.User, result[2].role)
        assertTrue(result[2].text.indexOf("untrusted project data") < result[2].text.indexOf("Run ./gradlew test"))
        assertTrue(result[2].text.indexOf("Run ./gradlew test") < result[2].text.indexOf("Current user request"))
        assertTrue(result[2].text.endsWith("Fix the crash"))
        assertEquals("Fix the crash", current.text)
    }

    @Test
    fun noContextLeavesProviderHistoryUnchanged() {
        val history = listOf(AgentMessage.user("Keep this exact"))
        assertSame(history, addUntrustedTaskContext(history, "", " "))
    }
}
