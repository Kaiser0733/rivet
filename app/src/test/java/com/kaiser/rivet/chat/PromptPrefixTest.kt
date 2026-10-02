package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.InternalContext
import com.kaiser.rivet.agent.modelMessages
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptPrefixTest {
    @Test fun previousUserBlockStaysIdenticalAcrossUserTurns() {
        val first = AgentMessage.user("Inspect the project")
        val context = InternalContext(kind = "project", workspaceId = "tree", scope = "AGENTS.md",
            content = "Use Kotlin.", digest = InternalContext.digest("Use Kotlin.")).event()
        val request1 = modelMessages(listOf(context, first))
        val request2 = modelMessages(listOf(context, first, AgentMessage.assistant("Inspected"),
            AgentMessage.user("Edit one file")))
        assertEquals(request1, request2.take(request1.size))
        assertEquals(first, request2[1])
    }
}
