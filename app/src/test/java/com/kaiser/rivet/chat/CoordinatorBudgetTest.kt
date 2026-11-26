package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.ArchiveRecall
import com.kaiser.rivet.agent.ContextBudget
import com.kaiser.rivet.browser.BrowserTools
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.subagent.SubagentTools
import org.junit.Assert.assertEquals
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

    // `run_command` gained byte_offset/output_stream for cached-output byte paging
    // (row 32). The serialized schema adds 26 estimated tokens to the empty
    // coordinator request; the baseline stays pinned so future prompt growth is deliberate.
    @Test fun emptyCloudProjectPinsTheDeviceBaseline() {
        assertEmptyCloudProjectBaseline("deepseek-ai/DeepSeek-V4.1-Flash", 7_276)
    }

    @Test fun emptyCloudProjectPinsTheSyntheticBaseline() {
        assertEmptyCloudProjectBaseline("test-model", 7_269)
    }

    private fun assertEmptyCloudProjectBaseline(model: String, expectedTokens: Long) {
        val config = ProviderConfig(id = "baseline", name = "Baseline", type = ProviderType.OpenAiCompatible,
            baseUrl = "https://example.invalid/v1", model = model, manualContextWindowTokens = 16_000)
        val system = ChatViewModel.systemInstruction(workspace = true, phoneAccess = true,
            presentAvailable = true, archiveAvailable = true)
        val tools = BrowserTools.definitions + AgentToolExecutor.definitions +
            SubagentTools.definitions + ArchiveRecall.definition
        val request = AgentRequest(config.model, emptyList(), system, config.reasoning, tools)
        val systemBytes = system.toByteArray(Charsets.UTF_8).size
        val toolBytes = tools.sumOf {
            it.name.toByteArray(Charsets.UTF_8).size + it.description.toByteArray(Charsets.UTF_8).size +
                it.parameters.toString().toByteArray(Charsets.UTF_8).size
        }
        val framingBytes = 128 + 96 * tools.size
        val modelBytes = config.model.toByteArray(Charsets.UTF_8).size
        val historyBytes = "[]".length
        val baseline = (systemBytes + toolBytes + framingBytes + modelBytes + historyBytes + 2L) / 3L
        val reading = contextReadingForRequest(config, request)

        assertEquals(0, ContextBudget.attachedImageCount(request.messages))
        assertEquals(baseline, ContextBudget.estimateRequestTokens(request))
        assertEquals(baseline, reading.usedTokens)
        assertEquals(expectedTokens, reading.usedTokens)
        assertEquals(16_000, reading.contextWindow)
        assertEquals(45, reading.percent)
        println("Empty cloud project: ${tools.size} tools, system=$systemBytes B, tools=$toolBytes B, " +
            "estimated framing=$framingBytes B, model=$modelBytes B, history=$historyBytes B, estimate=$baseline")
    }
}
