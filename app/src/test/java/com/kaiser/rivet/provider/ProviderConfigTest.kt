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
    fun openRouterExposesMaxReasoningOthersDoNot() {
        assertTrue(offeredReasoning(ProviderType.OpenRouter).contains(ReasoningLevel.Max))
        assertTrue(!offeredReasoning(ProviderType.OpenAiCompatible).contains(ReasoningLevel.Max))
        assertTrue(!offeredReasoning(ProviderType.Anthropic).contains(ReasoningLevel.Max))
        assertTrue(!offeredReasoning(ProviderType.Gemini).contains(ReasoningLevel.Max))
    }
}
