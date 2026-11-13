package com.kaiser.rivet.provider

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigTest {

    @Test
    fun configRoundTripsThroughJson() {
        val config = ProviderConfig(
            id = "id-1",
            type = ProviderType.OpenRouter,
            name = "My Router",
            baseUrl = "https://openrouter.ai/api/v1",
            model = "model/x",
            reasoning = ReasoningLevel.High,
            headers = listOf(ProviderHeader("X-One", "1")),
        )
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(ListSerializer(ProviderConfig.serializer()), listOf(config))
        val decoded = json.decodeFromString(ListSerializer(ProviderConfig.serializer()), encoded)
        assertEquals(listOf(config), decoded)
    }

    @Test
    fun anthropicMetadataIsScopedToTheSelectedModelAndBaseUrl() {
        val metadata = AnthropicModelMetadata(
            model = "claude-opus-5",
            baseUrl = ProviderType.Anthropic.defaultBaseUrl,
            maxOutputTokens = 128_000,
            thinkingSupported = true,
            adaptiveThinkingSupported = true,
            manualThinkingSupported = false,
        )
        val config = ProviderConfig(
            id = "id",
            type = ProviderType.Anthropic,
            name = "Claude",
            baseUrl = metadata.baseUrl,
            model = metadata.model,
            reasoning = ReasoningLevel.High,
            anthropicModelMetadata = metadata,
        )

        assertEquals(AnthropicThinkingMode.Adaptive, anthropicThinkingMode(config))
        assertEquals(128_000, anthropicOutputCeiling(config))
        val restored = Json.decodeFromString(
            ProviderConfig.serializer(),
            Json.encodeToString(ProviderConfig.serializer(), config),
        )
        assertEquals(metadata, restored.anthropicModelMetadata)
        assertEquals(AnthropicThinkingMode.Unsupported, anthropicThinkingMode(config.copy(model = "other")))
        assertEquals(8_192, anthropicOutputCeiling(config.copy(model = "other")))
        assertEquals(
            AnthropicThinkingMode.Unsupported,
            anthropicThinkingMode(config.copy(baseUrl = "https://proxy.example")),
        )
    }

    @Test
    fun legacyAnthropicConfigsKeepKnownOfficialDefaultsButCustomEndpointsStayUnknown() {
        val official = ProviderConfig(
            "old", ProviderType.Anthropic, "Claude", ProviderType.Anthropic.defaultBaseUrl,
            "claude-haiku-4-5-20251001",
        )
        val custom = official.copy(baseUrl = "https://proxy.example")

        assertEquals(AnthropicThinkingMode.Manual, anthropicThinkingMode(official))
        assertEquals(AnthropicThinkingMode.Unsupported, anthropicThinkingMode(custom))
        assertEquals(listOf(ReasoningLevel.Default), offeredReasoning(custom))

        val oldJson = """{"id":"old","type":"anthropic","name":"Claude","baseUrl":"${official.baseUrl}","model":"${official.model}"}"""
        val decoded = Json.decodeFromString(ProviderConfig.serializer(), oldJson)
        assertNull(decoded.anthropicModelMetadata)
        assertEquals(official, decoded)
    }

    @Test
    fun manualThinkingBudgetKeepsRequiredOutputHeadroomUnderReportedMaximum() {
        val baseUrl = ProviderType.Anthropic.defaultBaseUrl
        val config = ProviderConfig(
            id = "id",
            type = ProviderType.Anthropic,
            name = "Claude",
            baseUrl = baseUrl,
            model = "claude-sonnet-4-5",
            reasoning = ReasoningLevel.High,
            anthropicModelMetadata = AnthropicModelMetadata(
                model = "claude-sonnet-4-5",
                baseUrl = baseUrl,
                maxOutputTokens = 20_000,
                thinkingSupported = true,
                adaptiveThinkingSupported = false,
                manualThinkingSupported = true,
            ),
        )

        assertEquals(20_000, anthropicOutputCeiling(config))
        assertEquals(11_808, anthropicThinkingBudget(config))
        assertTrue(anthropicThinkingBudget(config) < anthropicOutputCeiling(config))
    }

    @Test
    fun manualThinkingIsUnsupportedWhenSmallInputWindowCannotHoldMinimumBudgetAndOutput() {
        val baseUrl = ProviderType.Anthropic.defaultBaseUrl
        val config = ProviderConfig(
            id = "small",
            type = ProviderType.Anthropic,
            name = "Claude",
            baseUrl = baseUrl,
            model = "claude-sonnet-4-5",
            reasoning = ReasoningLevel.High,
            modelContextLimit = ModelContextLimit("claude-sonnet-4-5", baseUrl, 4_096),
            anthropicModelMetadata = AnthropicModelMetadata(
                model = "claude-sonnet-4-5",
                baseUrl = baseUrl,
                maxOutputTokens = 128_000,
                thinkingSupported = true,
                adaptiveThinkingSupported = false,
                manualThinkingSupported = true,
            ),
        )

        assertEquals(1_024, anthropicOutputCeiling(config))
        assertEquals(0, anthropicThinkingBudget(config))
        assertEquals(listOf(ReasoningLevel.Default), offeredReasoning(config))
    }

    @Test
    fun listedLimitAppliesOnlyToItsModelAndEndpoint() {
        val base = ProviderConfig("id", ProviderType.Gemini, "Gemini", "https://models.example", "m",
            modelContextLimit = ModelContextLimit("m", "https://models.example", 32000))
        assertEquals(32000, base.trustedInputLimitTokens())
        assertEquals(null, base.copy(model = "other").trustedInputLimitTokens())
        assertEquals(null, base.copy(baseUrl = "https://other.example").trustedInputLimitTokens())
        val json = Json.encodeToString(ProviderConfig.serializer(), base)
        assertEquals(32000, Json.decodeFromString(ProviderConfig.serializer(), json).trustedInputLimitTokens())
        val legacyLimit = Json.decodeFromString(ModelContextLimit.serializer(),
            """{"model":"m","baseUrl":"https://models.example","inputLimitTokens":32000}""")
        assertNull(legacyLimit.maxOutputTokens)

        val selected = base.copy(model = "other").selectListedModel(ModelInfo("small", "Small", 8000))
        assertEquals("small", selected.model)
        assertEquals(8000, selected.trustedInputLimitTokens())
    }

    @Test
    fun headerSanitizationDropsAuthAndMalformedHeaders() {
        val headers = listOf(
            ProviderHeader("Authorization", "Bearer evil"),
            ProviderHeader("authorization", "Bearer evil"),
            ProviderHeader("x-api-key", "evil"),
            ProviderHeader("X-Goog-Api-Key", "evil"),
            ProviderHeader("x-goog-api-key", "evil"),
            ProviderHeader("Bad Header", "space-in-name"),
            ProviderHeader("X-Ctl", "line1\r\nInjected: yes"),
            ProviderHeader("X-NonAscii", "val\u2603"),
            ProviderHeader("X-Custom", "keep"),
        )
        val kept = headers.sanitized()
        assertEquals(listOf("keep"), kept.map { it.value })
    }

    @Test
    fun headerParsing() {
        val parsed = parseHeaders("X-A: 1\nX-B:2\nbad line\n\nX-C: 3")
        assertEquals(listOf("X-A" to "1", "X-B" to "2", "X-C" to "3"), parsed.map { it.name to it.value })
    }

    @Test
    fun everyProviderOffersTheWholeEffortLadder() {
        // Custom OpenAI-compatible endpoints decide what they accept per model, so the
        // selector must not hide levels from them.
        assertEquals(ALL_REASONING_LEVELS, offeredReasoning(ProviderType.OpenAiCompatible, "custom-model"))
        assertEquals(ALL_REASONING_LEVELS, offeredReasoning(ProviderType.OpenRouter, "any-model"))
        assertEquals(ALL_REASONING_LEVELS, offeredReasoning(ProviderType.ChatGptPlan, "gpt-5"))
        // Gemini sends no reasoning configuration at all, so it keeps Off rather than
        // offering levels that would silently change nothing.
        assertEquals(
            listOf(ReasoningLevel.Default),
            offeredReasoning(ProviderType.Gemini, "gemini-2.5-pro"),
        )
    }

    @Test
    fun theLadderRunsOffLowMediumHighXHighMaxUltra() {
        assertEquals(
            listOf("off", "low", "medium", "high", "xhigh", "max", "ultra"),
            ALL_REASONING_LEVELS.map { it.wireName },
        )
    }

    @Test
    fun aRejectedEffortRecommendsTheNextStepDown() {
        assertEquals(ReasoningLevel.Max, recommendedFallbackReasoning(ReasoningLevel.Ultra))
        assertEquals(ReasoningLevel.Max, recommendedFallbackReasoning(ReasoningLevel.XHigh))
        assertEquals(ReasoningLevel.High, recommendedFallbackReasoning(ReasoningLevel.Max))
        assertEquals(ReasoningLevel.Medium, recommendedFallbackReasoning(ReasoningLevel.High))
        assertEquals(ReasoningLevel.Low, recommendedFallbackReasoning(ReasoningLevel.Medium))
        assertEquals(ReasoningLevel.Default, recommendedFallbackReasoning(ReasoningLevel.Low))
        assertNull(recommendedFallbackReasoning(ReasoningLevel.Default))
    }

    @Test
    fun chatGptPlanOffersTheWholeLadderIncludingMaxAndUltra() {
        // Max and Ultra now carry their own effort values instead of borrowing xhigh.
        assertEquals(ALL_REASONING_LEVELS, offeredReasoning(ProviderType.ChatGptPlan, "gpt-5"))
    }

    @Test
    fun documentedOpenAiPresetsExposeTheWholeLadder() {
        assertEquals(ALL_REASONING_LEVELS, offeredReasoning(ProviderType.OpenAi, "gpt-5"))
        // A preset that does not think keeps only Off: an effort it cannot take would
        // only earn the rejection this ladder exists to recover from.
        assertEquals(
            listOf(ReasoningLevel.Default),
            offeredReasoning(ProviderType.OpenAi, "gpt-4o"),
        )
    }

    @Test
    fun anthropicReasoningUsesOnlyDocumentedCurrentModelIds() {
        val reasoning = ALL_REASONING_LEVELS
        listOf(
            "claude-opus-4-5",
            "claude-opus-4-5-20251101",
            "claude-sonnet-4-5",
            "claude-sonnet-4-5-20250929",
            "claude-haiku-4-5",
            "claude-haiku-4-5-20251001",
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "claude-opus-4-7",
            "claude-opus-4-8",
            "claude-sonnet-5",
            "claude-opus-5",
            "claude-fable-5",
            "claude-mythos-5",
            "claude-fable-5-1",
            "claude-mythos-5-1",
            "claude-mythos-preview",
        ).forEach { model ->
            assertEquals(model, reasoning, offeredReasoning(ProviderType.Anthropic, model))
        }

        listOf(
            "claude-unknown",
            "claude-3-7-sonnet-latest",
            "claude-sonnet-4-4",
            "claude-opus-4-9",
            "claude-opus-4-5-future",
            "claude-opus-5-future",
        ).forEach { model ->
            assertEquals(
                model,
                listOf(ReasoningLevel.Default),
                offeredReasoning(ProviderType.Anthropic, model),
            )
        }
    }

    @Test
    fun anthropicThinkingModeAcceptsV1BaseUrlAndOpenRouterCeilingScopesModelLimit() {
        val anthropicV1 = ProviderConfig(
            id = "a",
            name = "Anthropic",
            type = ProviderType.Anthropic,
            baseUrl = "https://api.anthropic.com/v1/",
            model = "claude-sonnet-4-5",
        )
        assertEquals(AnthropicThinkingMode.Manual, anthropicThinkingMode(anthropicV1))

        val openRouter = ProviderConfig(
            id = "or",
            name = "OpenRouter",
            type = ProviderType.OpenRouter,
            baseUrl = ProviderType.OpenRouter.defaultBaseUrl,
            model = "other/model",
            modelContextLimit = ModelContextLimit(
                model = "stale/model",
                baseUrl = ProviderType.OpenRouter.defaultBaseUrl,
                inputLimitTokens = 100_000,
                maxOutputTokens = 1024,
            ),
            manualContextWindowTokens = 64_000,
        )
        assertEquals(null, openRouter.openRouterOutputCeiling("unconfigured/model"))
        assertEquals(12_000, openRouter.openRouterOutputCeiling("other/model"))
    }

    @Test
    fun settingManualLimitsBeforeModelPreservesThemWhenModelEntered() {
        val initial = ProviderConfig(
            id = "o",
            name = "OpenAI",
            type = ProviderType.OpenAi,
            baseUrl = ProviderType.OpenAi.defaultBaseUrl,
            model = "",
        )
            .withManualContextWindow(65_536)
            .withManualMaxOutputTokens(4_096)
        val afterModel = initial.withModelId("gpt-4.1")
        assertEquals(65_536, afterModel.manualContextWindowForModel("gpt-4.1"))
        assertEquals(4_096, afterModel.resolvedManualMaxOutputTokens("gpt-4.1"))
    }
}
