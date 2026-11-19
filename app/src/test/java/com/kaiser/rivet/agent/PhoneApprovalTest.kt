package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneApprovalTest {
    @Test fun externalLaunchAndComposeAskInAskAndBasicModes() {
        for (name in listOf("open_app", "open_url", "open_file", "compose_intent")) {
            val tool = tool(name)
            assertEquals(AgentToolEffect.BrowserAction, tool.effect)
            assertEquals(ApprovalDecision.AskUser, ApprovalPolicy.decide(AutonomyMode.Ask, tool))
            assertEquals(ApprovalDecision.AskUser, ApprovalPolicy.decide(AutonomyMode.BasicYolo, tool))
            assertEquals(ApprovalDecision.AutoAuthorize, ApprovalPolicy.decide(AutonomyMode.Yolo, tool))
        }
    }

    @Test fun shareSheetProvidesItsOwnConsent() {
        val tool = tool("share_out")
        assertEquals(AgentToolEffect.ReadOnly, tool.effect)
        assertEquals(ApprovalDecision.AutoAuthorize, ApprovalPolicy.decide(AutonomyMode.Ask, tool))
    }

    private fun tool(name: String): PreparedAgentTool {
        val call = AgentToolCall(name, name, "{}")
        return PreparedAgentTool(call, AgentApprovalRequest(call, "Open", "Phone")) {
            error("No action is executed in an approval test")
        }
    }
}
