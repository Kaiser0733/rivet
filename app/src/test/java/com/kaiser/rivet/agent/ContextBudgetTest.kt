package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.ModelContextLimit
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.provider.anthropicOutputCeiling
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBudgetTest {
    private val config = ProviderConfig("p", ProviderType.Gemini, "Gemini", "https://models.example", "large",
        modelContextLimit = ModelContextLimit("large", "https://models.example", 16000))

    @Test fun estimateIncludesSystemProjectContextAndToolSchemas() {
        val base = AgentRequest("large", listOf(AgentMessage.user("Find the bug")), "policy",
            ReasoningLevel.Default, emptyList())
        val withContext = base.copy(
            messages = listOf(AgentMessage.user("project instructions " + "x".repeat(3000))),
            system = "policy " + "y".repeat(3000),
            tools = listOf(AgentToolDefinition("search_files", "Search " + "z".repeat(3000),
                buildJsonObject { put("type", "object") })),
        )
        assertTrue(ContextBudget.estimateRequestTokens(withContext) >
            ContextBudget.estimateRequestTokens(base) + 2000)
    }

    @Test fun smallerListedModelForcesReevaluationWithoutInventingUnknownCapacity() {
        val large = ContextBudget.assess(config, 7000, TokenEstimateSource.Estimated)
        assertEquals(16000, large.knownInputLimitTokens)
        assertEquals(CapacitySource.ProviderMetadata, large.capacitySource)
        assertFalse(large.needsReduction)

        val smaller = config.copy(model = "small", modelContextLimit =
            ModelContextLimit("small", config.baseUrl, 8000))
        assertTrue(ContextBudget.assess(smaller, 7000, TokenEstimateSource.Reported).needsReduction)

        val unknown = ContextBudget.assess(config.copy(model = "unlisted"), 7000,
            TokenEstimateSource.Estimated)
        assertNull(unknown.knownInputLimitTokens)
        assertEquals(CapacitySource.Unknown, unknown.capacitySource)
        assertEquals(TokenEstimateSource.Estimated, unknown.estimateSource)
    }

    @Test fun listedAnthropicHighReasoningReservesItsActualRequestedOutput() {
        val anthropic = ProviderConfig("a", ProviderType.Anthropic, "Anthropic",
            "https://api.anthropic.com", "claude-opus-4-5", ReasoningLevel.High,
            modelContextLimit = ModelContextLimit("claude-opus-4-5",
                "https://api.anthropic.com", 200_000))

        val assessed = ContextBudget.assess(anthropic, 180_000, TokenEstimateSource.Estimated)

        assertEquals(40_960, anthropicOutputCeiling(anthropic.model, anthropic.reasoning))
        assertEquals(40_960, assessed.reservedTokens)
        assertTrue(assessed.needsReduction)
        assertEquals(159_040, assessed.allowedInputTokens)
        val default = ContextBudget.assess(anthropic.copy(reasoning = ReasoningLevel.Default),
            180_000, TokenEstimateSource.Estimated)
        assertFalse(default.needsReduction)
    }
}
