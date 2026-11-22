package com.kaiser.rivet.chat

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.ArchiveRecall
import com.kaiser.rivet.agent.ContextBudget
import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ModelContextLimit
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextMeterTest {
    @Test fun meterCountsOnlyTheEnvelopeSentAfterProjectionAndRecall() {
        val config = ProviderConfig("p", ProviderType.OpenAi, "Cloud", "https://example.invalid", "x")
        val prompt = AgentMessage.user("What's next?")
        val request = AgentRequest(config.model, emptyList(), "System", config.reasoning,
            listOf(ArchiveRecall.definition))
        val withOldTurns = request.copy(messages = listOf(AgentMessage.user("older".repeat(2800)), prompt))
        val compacted = request.copy(messages = ArchiveRecall.splice(
            listOf(AgentMessage.user("Checkpoint"), prompt), "Turn 1: monsoon-42", prompt))
        assertEquals(ContextBudget.estimateRequestTokens(compacted), contextReadingForRequest(config, compacted).usedTokens)
        assertTrue(contextReadingForRequest(config, compacted).usedTokens <
            contextReadingForRequest(config, withOldTurns).usedTokens)
        assertTrue(contextReadingForRequest(config, compacted).usedTokens >
            contextReadingForRequest(config, request).usedTokens)
    }

    @Test fun usesSelectedWindowAndRejectsACrossModelReading() {
        val endpoint = "https://example.invalid"
        val config = ProviderConfig("p", ProviderType.OpenAi, "Cloud", endpoint, "x",
            modelContextLimit = ModelContextLimit("x", endpoint, 20_000))
        val reading = contextReading(config, 7_000)
        assertEquals(35, reading.percent)
        assertFalse(reading.estimatedWindow)
        assertTrue(reading.matches(config))
        assertFalse(reading.matches(config.copy(model = "y")))
        assertFalse(reading.matches(config.copy(manualContextWindowTokens = 100_000)))
        assertTrue(contextReading(config.copy(model = "y"), 7_000).estimatedWindow)
        assertEquals(100, contextPercent(25_000, 20_000))
    }
}
