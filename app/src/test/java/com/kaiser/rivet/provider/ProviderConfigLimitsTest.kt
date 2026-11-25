package com.kaiser.rivet.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderConfigLimitsTest {

    @Test
    fun newLimitFieldsRoundTripThroughTheStoredJson() {
        val config = ProviderConfig(
            id = "id", type = ProviderType.OpenAi, name = "OpenAI",
            baseUrl = "https://api.openai.com/v1", model = "gpt-4o",
            modelContextLimit = ModelContextLimit(
                "gpt-4o", "https://api.openai.com/v1", 128_000,
                maxOutputTokens = 16_384,
                inputLimitKind = LimitKind.TotalWindow,
                fetchedAt = 123,
            ),
            modelLimitLearning = mapOf(
                "gpt-4o" to ModelLimitLearning(
                    baseUrl = "https://api.openai.com/v1",
                    contextWindowTokens = 120_000,
                    confirmations = 2,
                    expiresAt = 999,
                ),
            ),
            localContextCeilingTokens = 8_192,
        )
        val restored = Json.decodeFromString(
            ProviderConfig.serializer(),
            Json.encodeToString(ProviderConfig.serializer(), config),
        )
        assertEquals(config, restored)
    }

    @Test
    fun detectedOutputIsExposedForTheRequestLayer() {
        val config = ProviderConfig(
            id = "id", type = ProviderType.OpenAi, name = "OpenAI",
            baseUrl = "https://api.openai.com/v1", model = "gpt-4o",
            modelContextLimit = ModelContextLimit(
                "gpt-4o", "https://api.openai.com/v1", 128_000,
                maxOutputTokens = 16_384, inputLimitKind = LimitKind.TotalWindow,
            ),
        )
        assertEquals(16_384, config.resolvedMaxOutputTokens())
        assertEquals(16_384, config.requestOutputLimit())
        // The same output on a generic gateway is not sent: unknown proxies reject the field.
        val generic = config.copy(type = ProviderType.OpenAiCompatible, baseUrl = "https://api.example/v1")
            .copy(
                modelContextLimit = ModelContextLimit(
                    "gpt-4o", "https://api.example/v1", 128_000,
                    maxOutputTokens = 16_384, inputLimitKind = LimitKind.TotalWindow,
                ),
            )
        assertNull(generic.requestOutputLimit())
    }

    @Test
    fun normalizedListedMatchKeepsTheTypedId() {
        val config = ProviderConfig(
            id = "id", type = ProviderType.OpenAiCompatible, name = "Gateway",
            baseUrl = "https://gateway.example/v1", model = "",
        )
        val matched = config.withModelId(
            "openai/gpt-4o",
            listOf(ModelInfo("gpt-4o", "GPT-4o", 128_000, outputLimitTokens = 16_384)),
        )
        assertEquals("openai/gpt-4o", matched.model)
        assertEquals(128_000, matched.resolvedContextWindowTokens())
        assertEquals(16_384, matched.resolvedMaxOutputTokens())
    }

    @Test
    fun aManualOutputAboveTheProviderMaximumIsClampedOnTheWire() {
        val config = ProviderConfig(
            id = "id", type = ProviderType.OpenAi, name = "OpenAI",
            baseUrl = "https://api.openai.com/v1", model = "gpt-4o",
            modelContextLimit = ModelContextLimit(
                "gpt-4o", "https://api.openai.com/v1", 128_000, maxOutputTokens = 16_384,
            ),
            manualMaxOutputTokens = 64_000,
        )
        assertEquals(64_000, config.resolveLimits().output?.tokens)
        assertEquals(16_384, config.requestOutputLimit())
    }

    @Test
    fun tableOutputIsNotSentByARequest() {
        val config = ProviderConfig(
            id = "id", type = ProviderType.OpenAi, name = "OpenAI",
            baseUrl = "https://api.openai.com/v1", model = "gpt-4o",
        )
        assertEquals(16_384, config.resolvedMaxOutputTokens())
        assertNull(config.requestOutputLimit())
    }

    @Test
    fun manualOutputStillWinsOverDetected() {
        val config = ProviderConfig(
            id = "id", type = ProviderType.OpenAi, name = "OpenAI",
            baseUrl = "https://api.openai.com/v1", model = "gpt-4o",
            modelContextLimit = ModelContextLimit(
                "gpt-4o", "https://api.openai.com/v1", 128_000, maxOutputTokens = 16_384,
            ),
            manualMaxOutputTokens = 4_096,
        )
        assertEquals(4_096, config.resolvedMaxOutputTokens())
        assertEquals(4_096, config.requestOutputLimit())
        assertTrue(config.resolveLimits().warnings.isEmpty())
    }
}
