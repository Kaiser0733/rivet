package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.AgentRequest
import com.kaiser.rivet.provider.DEFAULT_CONTEXT_WINDOW_TOKENS
import com.kaiser.rivet.provider.ModelContextLimit
import com.kaiser.rivet.provider.ModelInfo
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.ReasoningLevel
import com.kaiser.rivet.provider.anthropicOutputCeiling
import com.kaiser.rivet.provider.resolvedContextWindowTokens
import com.kaiser.rivet.provider.selectListedModel
import com.kaiser.rivet.provider.withManualContextWindow
import com.kaiser.rivet.provider.withManualMaxOutputTokens
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
            ModelContextLimit("small", config.baseUrl, 5000))
        assertTrue(ContextBudget.assess(smaller, 7000, TokenEstimateSource.Reported).needsReduction)

        val unknown = ContextBudget.assess(config.copy(model = "unlisted"), 7000,
            TokenEstimateSource.Estimated)
        assertNull(unknown.knownInputLimitTokens)
        assertEquals(CapacitySource.Unknown, unknown.capacitySource)
        assertEquals(TokenEstimateSource.Estimated, unknown.estimateSource)
    }

    @Test fun trustedInputOnlyGeminiLimitsRemainKnownAcrossSmallCapacities() {
        for (capacity in listOf(1, 2, 100, 1024, 2048, 2049, 4096, 8192, 16384, 32768, 65536)) {
            val selected = config.copy(modelContextLimit =
                ModelContextLimit(config.model, config.baseUrl, capacity))
            val assessed = ContextBudget.assess(selected, capacity.toLong(), TokenEstimateSource.Estimated)
            assertEquals(capacity, assessed.knownInputLimitTokens)
            assertEquals(CapacitySource.ProviderMetadata, assessed.capacitySource)
            assertEquals(capacity, assessed.allowedInputTokens)
            assertEquals(0, assessed.reservedTokens)
            assertTrue(assessed.needsReduction)
        }
    }

    @Test fun tinyTotalWindowsKeepExactCapacityAndActualReserve() {
        for (type in listOf(ProviderType.Anthropic, ProviderType.OpenRouter)) {
            for (capacity in listOf(1, 2, 100, 1024, 2048)) {
                val selected = config.copy(type = type, modelContextLimit =
                    ModelContextLimit(config.model, config.baseUrl, capacity))
                val assessed = ContextBudget.assess(selected, 1, TokenEstimateSource.Estimated)
                assertEquals(capacity, assessed.knownInputLimitTokens)
                assertEquals(CapacitySource.ProviderMetadata, assessed.capacitySource)
                assertEquals(capacity, assessed.allowedInputTokens + assessed.reservedTokens)
                assertTrue(assessed.allowedInputTokens >= 0)
                if (capacity == 1) assertEquals(0, assessed.allowedInputTokens)
            }
        }
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

    // Compacting at the ceiling leaves nothing to send. The trigger sits a margin below
    // it and the plan aims a margin below that (dsh's two-margin scheme).
    @Test fun compactionTriggersBeforeTheCeilingAndAimsLowerStill() {
        val wide = ProviderConfig("c", ProviderType.OpenAiCompatible, "Compat",
            "https://api.example/v1", "glm-5.1")
        val assessed = ContextBudget.assess(wide, 0, TokenEstimateSource.Estimated)
        // No window was entered or detected, so the 256K default is a guess and planning uses
        // half of it (F4). The trigger/aim relationship this test checks is unchanged.
        assertEquals(131_072 - 16_000, assessed.allowedInputTokens)
        assertTrue("trigger must leave room", assessed.compactAtTokens < assessed.allowedInputTokens)
        assertTrue("target must sit below the trigger", assessed.compactToTokens < assessed.compactAtTokens)

        val justUnderCeiling = assessed.copy(inputTokens = (assessed.allowedInputTokens - 1).toLong())
        assertFalse("a request that still fits does not hit the hard limit", justUnderCeiling.needsReduction)
        assertTrue("but it does compact while there is room to send the result", justUnderCeiling.shouldCompact)

        val underTrigger = assessed.copy(inputTokens = (assessed.compactAtTokens - 1).toLong())
        assertFalse(underTrigger.shouldCompact)
        assertFalse(underTrigger.needsReduction)
    }

    @Test fun aSmallWindowKeepsAUsableTrigger() {
        val narrow = config.copy(modelContextLimit =
            ModelContextLimit(config.model, config.baseUrl, 4096))
        val assessed = ContextBudget.assess(narrow, 4000, TokenEstimateSource.Estimated)
        assertEquals(4096, assessed.allowedInputTokens)
        assertTrue(assessed.compactAtTokens in 1 until 4096)
        assertTrue(assessed.compactToTokens in 0 until assessed.compactAtTokens)
        assertTrue(assessed.shouldCompact)
    }

    // Setting a manual output limit at or above the window used to reserve the whole window
    // and leave nothing to send, so every request failed with "context too small".
    @Test fun aManualOutputLimitAtOrAboveTheWindowLeavesRoomToSend() {
        val base = ProviderConfig("c", ProviderType.OpenAiCompatible, "Compat",
            "https://api.example/v1", "glm-5.1")
        val equal = base.withManualContextWindow(32_000).withManualMaxOutputTokens(32_000)
        val assessed = ContextBudget.assess(equal, 1, TokenEstimateSource.Estimated)
        assertEquals(16_000, assessed.reservedTokens)
        assertEquals(16_000, assessed.allowedInputTokens)
        assertTrue(assessed.allowedInputTokens > 0)

        val larger = base.withManualContextWindow(32_000).withManualMaxOutputTokens(64_000)
        assertEquals(16_000, ContextBudget.assess(larger, 1, TokenEstimateSource.Estimated).allowedInputTokens)
    }

    // A guessed window must not be planned as fact; an entered or detected one is used
    // exactly as it always was.
    @Test fun aGuessedWindowPlansLowerWhileMeasuredWindowsAreUnchanged() {
        val guessed = ProviderConfig("c", ProviderType.OpenAiCompatible, "Compat",
            "https://api.example/v1", "glm-5.1")
        assertEquals(131_072 - 16_000,
            ContextBudget.assess(guessed, 0, TokenEstimateSource.Estimated).allowedInputTokens)

        val manual = guessed.withManualContextWindow(262_144)
        assertEquals("an entered window plans at its own size", 262_144 - 16_000,
            ContextBudget.assess(manual, 0, TokenEstimateSource.Estimated).allowedInputTokens)

        val detected = guessed.selectListedModel(ModelInfo("glm-5.1", "GLM 5.1", inputLimitTokens = 262_144))
        assertEquals("a detected window plans at its own size", 262_144 - 16_000,
            ContextBudget.assess(detected, 0, TokenEstimateSource.Estimated).allowedInputTokens)
    }

    @Test fun contextWindowResolutionOrderManualBeatsDetectedBeats256kDefault() {
        val base = ProviderConfig(
            id = "compat",
            type = ProviderType.OpenAiCompatible,
            name = "Custom",
            baseUrl = "https://api.example/v1",
            model = "glm-5.1",
        )
        assertEquals(DEFAULT_CONTEXT_WINDOW_TOKENS, base.resolvedContextWindowTokens())
        assertEquals(262_144, base.resolvedContextWindowTokens())
        val defaultAssessment = ContextBudget.assess(base, 100_000, TokenEstimateSource.Estimated)
        assertNull(defaultAssessment.knownInputLimitTokens)
        // Resolution still reports 262 144 (asserted above); only the planning target for a
        // guessed window changed, and this test's subject is the resolution order (F4).
        assertEquals(131_072 - 16_000, defaultAssessment.allowedInputTokens)
        assertFalse(defaultAssessment.needsReduction)

        val detected = base.selectListedModel(ModelInfo("glm-5.1", "GLM 5.1", inputLimitTokens = 200_000))
        assertEquals(200_000, detected.resolvedContextWindowTokens())
        val detectedAssessment = ContextBudget.assess(detected, 120_000, TokenEstimateSource.Estimated)
        assertEquals(200_000, detectedAssessment.knownInputLimitTokens)
        assertEquals(200_000 - 16_000, detectedAssessment.allowedInputTokens)
        assertFalse(detectedAssessment.needsReduction)

        val manual = detected.withManualContextWindow(100_000).withManualMaxOutputTokens(8_000)
        assertEquals(100_000, manual.resolvedContextWindowTokens())
        val manualAssessment = ContextBudget.assess(manual, 95_000, TokenEstimateSource.Estimated)
        assertEquals(100_000, manualAssessment.knownInputLimitTokens)
        assertEquals(8_000, manualAssessment.reservedTokens)
        assertEquals(92_000, manualAssessment.allowedInputTokens)
        assertTrue(manualAssessment.needsReduction)
    }
}
