package com.kaiser.rivet.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLimitsTest {

    private fun config(
        model: String = "unknown-model",
        type: ProviderType = ProviderType.OpenAiCompatible,
        baseUrl: String = "https://api.example/v1",
    ) = ProviderConfig("id", type, "Provider", baseUrl, model)

    @Test
    fun unknownModelFallsBackTo32KAndIsHonestAboutIt() {
        val resolved = config().resolveLimits()
        assertEquals(32_768, resolved.input.tokens)
        assertEquals(LimitSource.Default, resolved.input.source)
        assertTrue(resolved.windowIsGuessed)
        assertNull(config().trustedInputLimitTokens())
    }

    @Test
    fun tableMatchesKnownModelWithoutAnyNetwork() {
        val resolved = config("gpt-4o", ProviderType.OpenAi, "https://api.openai.com/v1").resolveLimits()
        assertEquals(128_000, resolved.input.tokens)
        assertEquals(LimitSource.KnownTable, resolved.input.source)
        assertEquals(16_384, resolved.output?.tokens)
        assertFalse(resolved.windowIsGuessed)
    }

    @Test
    fun aggregatorPrefixMatchesTheBareModelButKeepsTheTypedId() {
        val cfg = config("openai/gpt-4o").withMatchedLimits(
            "openai/gpt-4o",
            ModelInfo("gpt-4o", "GPT-4o", 128_000, outputLimitTokens = 16_384),
        )
        assertEquals("openai/gpt-4o", cfg.model)
        val resolved = cfg.resolveLimits("openai/gpt-4o")
        assertEquals(128_000, resolved.input.tokens)
        assertEquals(LimitSource.Provider, resolved.input.source)
    }

    @Test
    fun datedAliasMatchesItsCanonicalEntry() {
        val cfg = config("claude-sonnet-4-5")
        val table = BundledModelLimits.parse(listOf(
            "*\tclaude-sonnet-4-5-20250929\t200000\t64000",
        ))
        val resolved = cfg.resolveLimits(table = table)
        assertEquals(200_000, resolved.input.tokens)
        assertEquals(64_000, resolved.output?.tokens)
        assertEquals(LimitSource.KnownTable, resolved.input.source)
    }

    @Test
    fun providerReportedLimitBeatsTheTable() {
        val cfg = config("gpt-4o", ProviderType.OpenAi, "https://api.openai.com/v1").copy(
            modelContextLimit = ModelContextLimit(
                "gpt-4o", "https://api.openai.com/v1", 64_000,
                maxOutputTokens = 8_000, fetchedAt = 1,
            ),
        )
        val resolved = cfg.resolveLimits()
        assertEquals(64_000, resolved.input.tokens)
        assertEquals(LimitSource.Provider, resolved.input.source)
        assertEquals(8_000, resolved.output?.tokens)
    }

    @Test
    fun manualWinsOverEverythingAndWarnsWhenAboveProvider() {
        val cfg = config("gpt-4o", ProviderType.OpenAi, "https://api.openai.com/v1")
            .withManualContextWindow(32_000)
            .withManualMaxOutputTokens(64_000)
            .copy(
                modelContextLimit = ModelContextLimit(
                    "gpt-4o", "https://api.openai.com/v1", 128_000,
                    maxOutputTokens = 16_384, fetchedAt = 1,
                ),
            )
        val resolved = cfg.resolveLimits()
        assertEquals(32_000, resolved.input.tokens)
        assertEquals(LimitSource.Manual, resolved.input.source)
        assertEquals(64_000, resolved.output?.tokens)
        assertTrue(LimitWarning.ManualAboveProvider in resolved.warnings)
    }

    @Test
    fun learnedCapBeatsProviderButProviderStillCapsTheLowerValue() {
        val now = 1_000_000L
        val cfg = config("gpt-4o", ProviderType.OpenAi, "https://api.openai.com/v1").copy(
            modelContextLimit = ModelContextLimit(
                "gpt-4o", "https://api.openai.com/v1", 128_000, fetchedAt = 1,
            ),
            modelLimitLearning = mapOf(
                "gpt-4o" to ModelLimitLearning(
                    baseUrl = "https://api.openai.com/v1",
                    contextWindowTokens = 64_000,
                    expiresAt = now + 1_000,
                ),
            ),
        )
        val learned = cfg.resolveLimits(now = now)
        assertEquals(64_000, learned.input.tokens)
        assertEquals(LimitSource.Learned, learned.input.source)

        val lowerProvider = cfg.copy(
            modelContextLimit = ModelContextLimit(
                "gpt-4o", "https://api.openai.com/v1", 32_000, fetchedAt = 1,
            ),
        )
        val providerWins = lowerProvider.resolveLimits(now = now)
        assertEquals(32_000, providerWins.input.tokens)
        assertEquals(LimitSource.Provider, providerWins.input.source)
    }

    @Test
    fun expiredLearnedRecordIsIgnored() {
        val cfg = config("unknown-model").copy(
            modelLimitLearning = mapOf(
                "unknown-model" to ModelLimitLearning(
                    baseUrl = "https://api.example/v1",
                    contextWindowTokens = 16_384,
                    expiresAt = 5,
                ),
            ),
        )
        val resolved = cfg.resolveLimits(now = 10)
        assertEquals(32_768, resolved.input.tokens)
        assertEquals(LimitSource.Default, resolved.input.source)
    }

    @Test
    fun successfulUsageRaisesOnlyAnAssumedOrTableValue() {
        val floor = ModelLimitLearning(baseUrl = "https://api.example/v1", observedInputTokens = 100_000)
        val assumed = config().copy(modelLimitLearning = mapOf("unknown-model" to floor))
        val raised = assumed.resolveLimits()
        assertEquals(131_072, raised.input.tokens)
        assertEquals(LimitSource.UsageFloor, raised.input.source)

        val manual = assumed.withManualContextWindow(32_000)
        assertEquals(32_000, manual.resolveLimits().input.tokens)
    }

    @Test
    fun localCeilingClampsAManualValueAndTheSourceSaysSo() {
        val cfg = config("local-model", ProviderType.Local, "local://").copy(
            localContextCeilingTokens = 8_192,
            manualContextWindowTokens = 100_000,
        )
        val resolved = cfg.resolveLimits()
        assertEquals(8_192, resolved.input.tokens)
        assertEquals(LimitSource.Local, resolved.input.source)
        assertTrue(LimitWarning.ManualAboveLocalCeiling in resolved.warnings)
    }

    @Test
    fun ambiguousTableEntriesAreNotChosen() {
        val table = BundledModelLimits.parse(listOf(
            "*\tgpt-4-0613\t128000\t16000",
            "*\tgpt-4-2024\t64000\t8000",
        ))
        assertNull(table.lookup("gpt-4", "openai"))
    }

    @Test
    fun endpointChangeClearsMeasuredAndLearnedValuesButKeepsManual() {
        val cfg = config("gpt-4o", ProviderType.OpenAi, "https://api.openai.com/v1")
            .withManualContextWindow(100_000)
            .copy(
                modelContextLimit = ModelContextLimit("gpt-4o", "https://api.openai.com/v1", 128_000),
                modelLimitLearning = mapOf("gpt-4o" to ModelLimitLearning(contextWindowTokens = 64_000)),
            )
        val moved = cfg.withEndpoint(ProviderType.OpenAiCompatible, "https://other.example/v1")
        assertNull(moved.modelContextLimit)
        assertTrue(moved.modelLimitLearning.isEmpty())
        assertEquals(100_000, moved.manualContextWindowForModel("gpt-4o"))
    }
}
