package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.ArchiveRecall
import com.kaiser.rivet.agent.ContextBudget
import com.kaiser.rivet.browser.BrowserTools
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.subagent.SubagentTools
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatorBudgetTest {
    @Test fun emptyCloudProjectLeavesAtLeastHalfOfASixteenThousandTokenWindowFree() {
        val system = ChatViewModel.systemInstruction(workspace = true, phoneAccess = true,
            presentAvailable = true, archiveAvailable = true)
        val tools = BrowserTools.definitions + AgentToolExecutor.definitions +
            SubagentTools.definitions + ArchiveRecall.definition
        val request = AgentRequest("test-model", emptyList(), system, ReasoningLevel.Default, tools)
        val estimate = ContextBudget.estimateRequestTokens(request)
        assertTrue("empty coordinator request costs $estimate tokens", estimate < 8_000)
        assertTrue(tools.size >= 45)
    }
}
