package com.kaiser.rivet.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {
    @Test fun anthropicCachedInputCountsTowardPromptOccupancy() {
        val response = Json.parseToJsonElement("""{"usage":{"input_tokens":20,"output_tokens":5,"cache_creation_input_tokens":30,"cache_read_input_tokens":70}}""").jsonObject
        val usage = anthropicUsage(response)!!
        assertEquals(20L, usage.inputTokens)
        assertEquals(120L, usage.contextInputTokens(ProviderType.Anthropic))
        assertEquals(30L, usage.cacheCreationTokens)
        assertEquals(70L, usage.cacheReadTokens)
        assertEquals(5L, usage.outputTokens)
    }
    @Test fun openAiCacheReadsAndReasoningDoNotIncreaseReportedPrompt() {
        val root = Json.parseToJsonElement("""{"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":80,"cache_write_tokens":10},"completion_tokens_details":{"reasoning_tokens":12}}}""").jsonObject
        val native = openAiUsage(root)!!
        assertEquals(100L, native.contextInputTokens(ProviderType.OpenAi))
        assertEquals(80L, native.cacheReadTokens)
        assertEquals(12L, native.reasoningTokens)
        assertNull(native.cacheCreationTokens)
        val routed = openAiUsage(root, documentedCacheWrites = true)!!
        assertEquals(10L, routed.cacheCreationTokens)
        assertEquals(100L, routed.contextInputTokens(ProviderType.OpenRouter))
        assertEquals(120L, routed.totalTokens)
    }

    @Test fun unknownCompatibleCacheWritesAreNotAssumedAndMissingUsageStaysUnknown() {
        val root = Json.parseToJsonElement("""{"usage":{"prompt_tokens":10,"prompt_tokens_details":{"cache_write_tokens":9}}}""").jsonObject
        assertNull(openAiUsage(root)!!.cacheCreationTokens)
        assertNull(openAiUsage(Json.parseToJsonElement("{}").jsonObject))
        assertNull(openAiUsage(Json.parseToJsonElement("""{"usage":{"prompt_tokens":-1}}""").jsonObject))
    }

    @Test fun geminiCachedAndThoughtTokensRetainNativeCategories() {
        val root = Json.parseToJsonElement("""{"usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":20,"cachedContentTokenCount":80,"thoughtsTokenCount":12,"totalTokenCount":132}}""").jsonObject
        val usage = geminiUsage(root)!!
        assertEquals(100L, usage.contextInputTokens(ProviderType.Gemini))
        assertEquals(80L, usage.cacheReadTokens)
        assertEquals(12L, usage.reasoningTokens)
        assertEquals(132L, usage.totalTokens)
        assertNull(usage.cacheCreationTokens)
    }

    @Test fun malformedOptionalUsageIsNotFabricated() {
        val root = Json.parseToJsonElement("""{"usage":{"prompt_tokens":10,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":-3,"cache_write_tokens":"bad"}}}""").jsonObject
        val usage = openAiUsage(root, documentedCacheWrites = true)!!
        assertNull(usage.cacheReadTokens)
        assertNull(usage.cacheCreationTokens)
    }
    @Test fun partialCacheUsageDoesNotInventPromptOrTotal() {
        val root = Json.parseToJsonElement("""{"usage":{"prompt_tokens_details":{"cached_tokens":7,"cache_write_tokens":3}}}""").jsonObject
        val usage = openAiUsage(root, documentedCacheWrites = true)!!
        assertEquals(7L, usage.cacheReadTokens)
        assertEquals(3L, usage.cacheCreationTokens)
        assertNull(usage.inputTokens)
        assertNull(usage.totalTokens)
        assertNull(usage.contextInputTokens(ProviderType.OpenRouter))
    }
}
