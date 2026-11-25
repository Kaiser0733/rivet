package com.kaiser.rivet.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLimitLearningPolicyTest {

    private val now = 1_000_000L

    private fun base(model: String = "gpt-4o") = ProviderConfig(
        id = "id", type = ProviderType.OpenAi, name = "OpenAI",
        baseUrl = "https://api.openai.com/v1", model = model,
    )

    @Test
    fun firstNumericErrorIsProvisionalAndLowerThanTheTable() {
        val result = ModelLimitLearningPolicy.observeContext(
            base(), "gpt-4o", ProviderError.ContextOverflow(64_000, 70_000), 69_000, now,
        )
        assertNotNull(result)
        assertEquals(64_000, result!!.learning.contextWindowTokens)
        assertEquals(1, result.learning.confirmations)
        assertTrue(result.learning.expiresAt > now)
        assertTrue(result.promptCap!! < 64_000)
    }

    @Test
    fun aSecondConsistentErrorConfirmsTheSameLimit() {
        val first = ModelLimitLearningPolicy.observeContext(
            base(), "gpt-4o", ProviderError.ContextOverflow(64_000, 70_000), 69_000, now,
        )!!
        val withFirst = base().withLimitLearning("gpt-4o", first.learning)
        val second = ModelLimitLearningPolicy.observeContext(
            withFirst, "gpt-4o", ProviderError.ContextOverflow(64_000, 70_000), 69_000, now + 100,
        )!!
        assertEquals(2, second.learning.confirmations)
        assertTrue(second.learning.expiresAt > first.learning.expiresAt)
    }

    @Test
    fun aMismatchedAttemptIsRejected() {
        assertNull(ModelLimitLearningPolicy.observeContext(
            base(), "gpt-4o", ProviderError.ContextOverflow(64_000, 10_000), 69_000, now,
        ))
        assertNull(ModelLimitLearningPolicy.observeContext(
            base(), "gpt-4o", ProviderError.ContextOverflow(64_000, 70_000), 1_000, now,
        ))
    }

    @Test
    fun manualValuesAreNeverOverwritten() {
        val manual = base().withManualContextWindow(32_000)
        assertNull(ModelLimitLearningPolicy.observeContext(
            manual, "gpt-4o", ProviderError.ContextOverflow(64_000, 70_000), 69_000, now,
        ))
    }

    @Test
    fun anErrorAboveTheCurrentWindowDoesNotLearnUpward() {
        assertNull(ModelLimitLearningPolicy.observeContext(
            base(), "gpt-4o", ProviderError.ContextOverflow(200_000, 210_000), 205_000, now,
        ))
    }

    @Test
    fun outputLimitIsLearnedDownwardOnly() {
        val result = ModelLimitLearningPolicy.observeOutput(
            base(), "gpt-4o", ProviderError.OutputLimitExceeded(8_192, 64_000), now,
        )
        assertEquals(8_192, result!!.learning.maxOutputTokens)

        val manual = base().withManualMaxOutputTokens(4_000)
        assertNull(ModelLimitLearningPolicy.observeOutput(
            manual, "gpt-4o", ProviderError.OutputLimitExceeded(8_192, 64_000), now,
        ))
    }

    @Test
    fun aSuccessfulLargeRequestClearsATooLowLearnedCap() {
        val learned = base().copy(
            modelLimitLearning = mapOf(
                "gpt-4o" to ModelLimitLearning(
                    baseUrl = "https://api.openai.com/v1",
                    contextWindowTokens = 64_000,
                    expiresAt = now + 1_000,
                ),
            ),
        )
        val updated = ModelLimitLearningPolicy.observeUsage(learned, "gpt-4o", 100_000, now)
        assertNull(updated!!.contextWindowTokens)
        assertEquals(100_000L, updated.observedInputTokens)
    }
}
