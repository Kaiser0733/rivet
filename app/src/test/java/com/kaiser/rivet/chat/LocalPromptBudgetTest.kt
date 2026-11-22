package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.ContextBudget
import com.kaiser.rivet.agent.TokenEstimateSource
import com.kaiser.rivet.local.LocalPrompt
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.ReasoningLevel
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPromptBudgetTest {
    private val config = ProviderConfig(
        id = "local-test", type = ProviderType.Local, name = "Local test",
        baseUrl = "local://", model = "Qwen3-0.6B-Q4_K_M.gguf",
        manualContextWindowTokens = 4096, manualMaxOutputTokens = 1024,
    )

    private fun request(message: String, window: Int = config.manualContextWindowTokens!!): AgentRequest {
        val wire = LocalPrompt.wire(
            ChatViewModel.systemInstruction(workspace = true),
            AgentToolExecutor.definitions,
            window,
            workspace = true,
        )
        return AgentRequest(
            model = config.model,
            messages = listOf(AgentMessage.user(message)),
            system = wire.system,
            reasoning = ReasoningLevel.Default,
            tools = wire.tools,
        )
    }

    @Test
    fun aShortTurnFitsARealFourKLocalWindow() {
        val assembled = request("Say hi.")
        val tokens = ContextBudget.estimateRequestTokens(assembled)
        val allowed = ContextBudget.assess(config, tokens, TokenEstimateSource.Estimated)
            .allowedInputTokens
        assertTrue("assembled=${assembled.system.length}/${assembled.tools.size} estimated=$tokens allowed=$allowed",
            tokens < allowed)
        assertTrue(assembled.tools.map { it.name }.contains("write_file"))
        assertTrue(assembled.tools.size < AgentToolExecutor.definitions.size)
    }

    @Test
    fun eightAndSixteenKWindowsStillFitTheFullLocalSystem() {
        for (window in listOf(8192, 16384)) {
            val assembled = request("Say hi.", window)
            val tokens = ContextBudget.estimateRequestTokens(assembled)
            val allowed = ContextBudget.assess(config.copy(manualContextWindowTokens = window),
                tokens, TokenEstimateSource.Estimated).allowedInputTokens
            assertTrue("window=$window estimated=$tokens allowed=$allowed", tokens < allowed)
            // An 8K+ window carries the full workspace system prompt, not the compact
            // variant, which keeps its own shorter opener and "untrusted data" wording.
            assertTrue(assembled.system.contains("You are Rivet's coding agent."))
            assertTrue(assembled.system.contains("untrusted project data"))
        }
    }

    @Test
    fun aGenuinelyOversizedMessageIsStillRejected() {
        val assembled = request("x".repeat(40_000))
        val tokens = ContextBudget.estimateRequestTokens(assembled)
        val allowed = ContextBudget.assess(config, tokens, TokenEstimateSource.Estimated)
            .allowedInputTokens
        assertTrue("oversized estimate=$tokens allowed=$allowed", tokens >= allowed)
    }
    @org.junit.Test fun compactPromptKeepsTheDeliverableRuleWithTheTool() {
        val tool = com.kaiser.rivet.agent.AgentToolExecutor.definitions.single { it.name == "present" }
        val rule = com.kaiser.rivet.agent.PRESENT_DELIVERABLES_RULE
        org.junit.Assert.assertTrue(LocalPrompt.wire("Base", listOf(tool), 4096, workspace = true).system.contains(rule))
        org.junit.Assert.assertFalse(LocalPrompt.wire("Base", emptyList(), 4096, workspace = false).system.contains(rule))
    }

}
