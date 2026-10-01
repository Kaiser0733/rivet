package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptPrefixTest {
    @Test fun previousUserBlockStaysIdenticalAcrossUserTurns() {
        val first = AgentMessage.user("Inspect the project")
        val request1 = addUntrustedTaskContext(listOf(first), "AGENTS.md: Use Kotlin.", "")
        val request2 = addUntrustedTaskContext(listOf(first, AgentMessage.assistant("Inspected"),
            AgentMessage.user("Edit one file")), "AGENTS.md: Use Kotlin.", "")
        assertEquals(request1.first(), request2.first())
    }
}
