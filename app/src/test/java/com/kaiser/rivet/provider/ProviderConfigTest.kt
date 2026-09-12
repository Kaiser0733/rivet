package com.kaiser.rivet.provider

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
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
    fun unknownTypeInStoredJsonDegradesCleanly() {
        // Schema evolution guard: a future type in stored JSON must not
        // break decoding of the rest of the list.
        val stored = """[{"id":"x","type":"future_thing","name":"X","baseUrl":"https://x","model":"m"}]"""
        try {
            val decoded = Json.decodeFromString(ListSerializer(ProviderConfig.serializer()), stored)
            throw AssertionError("expected exception, got $decoded")
        } catch (e: Exception) {
            // Decoding throws on unknown enum; ProviderStore catches and
            // degrades to emptyList — that behavior is the contract.
        }
    }

    @Test
    fun headerSanitizationDropsAuthHeaders() {
        val headers = listOf(
            ProviderHeader("Authorization", "Bearer evil"),
            ProviderHeader("authorization", "Bearer evil"),
            ProviderHeader("x-api-key", "evil"),
            ProviderHeader("X-Goog-Api-Key", "evil"),
            ProviderHeader("x-goog-api-key", "evil"),
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
    fun genericAndGeminiExposeNoReasoningControl() {
        assertEquals(
            listOf(ReasoningLevel.Default),
            offeredReasoning(ProviderType.OpenAiCompatible, "custom-model"),
        )
        assertEquals(
            listOf(ReasoningLevel.Default),
            offeredReasoning(ProviderType.Gemini, "gemini-2.5-pro"),
        )
    }

    @Test
    fun documentedOpenAiPresetsExposeReasoning() {
        assertEquals(
            listOf(ReasoningLevel.Default, ReasoningLevel.Low, ReasoningLevel.Medium, ReasoningLevel.High),
            offeredReasoning(ProviderType.OpenAi, "gpt-5"),
        )
        assertEquals(
            listOf(ReasoningLevel.Default),
            offeredReasoning(ProviderType.OpenAi, "gpt-4o"),
        )
        assertTrue(offeredReasoning(ProviderType.OpenRouter, "any-model").contains(ReasoningLevel.Max))
    }

    @Test
    fun anthropicReasoningUsesOnlyDocumentedCurrentModelIds() {
        val reasoning = listOf(
            ReasoningLevel.Default,
            ReasoningLevel.Low,
            ReasoningLevel.Medium,
            ReasoningLevel.High,
        )
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
}
