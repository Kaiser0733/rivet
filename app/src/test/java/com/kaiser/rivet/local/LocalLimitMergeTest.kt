package com.kaiser.rivet.local

import com.kaiser.rivet.provider.LimitKind
import com.kaiser.rivet.provider.ModelContextLimit
import com.kaiser.rivet.provider.ModelLimitLearning
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.provider.withLimitLearning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLimitMergeTest {

    private fun config() = ProviderConfig(
        id = "local-1", type = ProviderType.Local, name = "Local · Tiny",
        baseUrl = "local://", model = "repo/tiny.gguf",
    )

    @Test
    fun detectedCeilingIsRefreshedButManualAndLearningSurvive() {
        val base = config().copy(
            modelContextLimit = ModelContextLimit(
                "repo/tiny.gguf", "local://", 8_192,
                maxOutputTokens = 1_024, inputLimitKind = LimitKind.TotalWindow,
            ),
            localContextCeilingTokens = 8_192,
        )
        val existing = config()
            .withLimitLearning("repo/tiny.gguf", ModelLimitLearning(
                baseUrl = "local://", maxOutputTokens = 512,
            ))
            .copy(
                manualContextWindowTokens = 4_096,
                manualMaxOutputTokens = 512,
                reasoning = com.kaiser.rivet.provider.ReasoningLevel.Low,
            )
        val merged = mergeLocalProviderConfig(base, existing)
        assertEquals(4_096, merged.manualContextWindowTokens)
        assertEquals(512, merged.manualMaxOutputTokens)
        assertEquals(8_192, merged.localContextCeilingTokens)
        assertEquals(8_192, merged.modelContextLimit?.inputLimitTokens)
        assertEquals(com.kaiser.rivet.provider.ReasoningLevel.Low, merged.reasoning)
        assertTrue(merged.modelLimitLearning.containsKey("repo/tiny.gguf"))
    }

    @Test
    fun aFreshInstallGetsDetectedLimitsForTheFirstTime() {
        val base = config().copy(localContextCeilingTokens = 4_096)
        val merged = mergeLocalProviderConfig(base, null)
        assertEquals(4_096, merged.localContextCeilingTokens)
        assertEquals(null, merged.manualContextWindowTokens)
    }
}
