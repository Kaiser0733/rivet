package com.kaiser.rivet.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class KnownModelLimitsTest {

    @Test
    fun bundledSnapshotLoadsAndHasTheCommonEntries() {
        val table = KnownModelLimits.bundled
        val gpt = table.lookup("gpt-4o", "openai")
        assertEquals(128_000, gpt!!.contextTokens)
        assertEquals(16_384, gpt.outputTokens)

        val claude = table.lookup("claude-sonnet-4-5-20250929", "anthropic")
        assertEquals(200_000, claude!!.contextTokens)
        assertEquals(64_000, claude.outputTokens)

        val deepseek = table.lookup("deepseek-chat", "deepseek")
        assertEquals(128_000, deepseek!!.contextTokens)
        assertEquals(8_192, deepseek.outputTokens)
    }

    @Test
    fun providerScopedLookupWinsAndCanonicalIsTheFallback() {
        val table = KnownModelLimits.bundled
        val scoped = table.lookup("deepseek-chat", "openrouter")
        assertEquals(163_840, scoped!!.contextTokens)
        val canonical = table.lookup("deepseek-chat", null)
        assertEquals(128_000, canonical!!.contextTokens)
    }

    @Test
    fun ownerPrefixDateAndRoutingSuffixAreStripped() {
        val table = KnownModelLimits.bundled
        assertEquals(128_000, table.lookup("openai/gpt-4o", null)!!.contextTokens)
        assertEquals(128_000, table.lookup("gpt-4o-2024-08-06", "openai")!!.contextTokens)
        assertEquals(128_000, table.lookup("gpt-4o:free", "openai")!!.contextTokens)
        assertEquals(200_000, table.lookup("anthropic/claude-sonnet-4-5", null)!!.contextTokens)
    }

    @Test
    fun variantWordsAreNotStripped() {
        val table = BundledModelLimits.parse(listOf(
            "*\tgpt-4o\t128000\t16000",
            "*\tgpt-4o-mini\t128000\t16000",
        ))
        assertNull(table.lookup("gpt-4o-turbo", null))
        assertEquals(128_000, table.lookup("gpt-4o-mini", null)!!.contextTokens)
    }

    @Test
    fun normalizerHandlesFireworksPathsAndQuantSuffixesWithoutTouchingVersions() {
        assertEquals("llama-v3p3-70b-instruct",
            ModelIdNormalizer.normalize("accounts/fireworks/models/llama-v3p3-70b-instruct"))
        assertEquals("llama-3-2-1b-instruct",
            ModelIdNormalizer.normalize("Llama-3.2-1B-Instruct-Q4_K_M.gguf", local = true))
        assertEquals("gpt-4-1", ModelIdNormalizer.normalize("gpt-4.1"))
        assertFalse(ModelIdNormalizer.normalize("gpt-4") == ModelIdNormalizer.normalize("gpt-4o"))
    }

    @Test
    fun hostMappingRecognizesTheVerifiedProviders() {
        fun key(baseUrl: String) = ProviderConfig(
            "id", ProviderType.OpenAiCompatible, "p", baseUrl, "m",
        ).knownProviderKey()
        assertEquals("groq", key("https://api.groq.com/openai/v1"))
        assertEquals("togetherai", key("https://api.together.xyz/v1"))
        assertEquals("fireworks-ai", key("https://api.fireworks.ai/inference/v1"))
        assertEquals("deepseek", key("https://api.deepseek.com"))
        assertNull(key("https://gateway.example/v1"))
    }
}
