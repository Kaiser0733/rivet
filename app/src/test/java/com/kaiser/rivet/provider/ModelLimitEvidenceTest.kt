package com.kaiser.rivet.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelLimitEvidenceTest {

    @Test
    fun openAiContextErrorCarriesBothNumbers() {
        val evidence = ModelLimitEvidence.parse(
            400,
            """{"error":{"type":"invalid_request_error","code":"context_length_exceeded","message":"This model's maximum context length is 128000 tokens. However, your messages resulted in 132000 tokens."}}""",
        )
        assertNotNull(evidence)
        assertEquals(128_000, evidence!!.contextLimitTokens)
        assertEquals(132_000L, evidence.attemptedTokens)
    }

    @Test
    fun anthropicPromptTooLongCarriesBothNumbers() {
        val evidence = ModelLimitEvidence.parse(
            400,
            """{"type":"error","error":{"type":"invalid_request_error","message":"prompt is too long: 213462 tokens > 200000 maximum"}}""",
        )
        assertEquals(200_000, evidence!!.contextLimitTokens)
        assertEquals(213_462L, evidence.attemptedTokens)
    }

    @Test
    fun geminiInputTokenErrorCarriesBothNumbers() {
        val evidence = ModelLimitEvidence.parse(
            400,
            """{"error":{"code":400,"status":"INVALID_ARGUMENT","message":"The input token count (213462) exceeds the maximum number of tokens allowed (200000)."}}""",
        )
        assertEquals(200_000, evidence!!.contextLimitTokens)
        assertEquals(213_462L, evidence.attemptedTokens)
    }

    @Test
    fun openAiMaxTokensErrorNamesTheRealOutputLimit() {
        val evidence = ModelLimitEvidence.parse(
            400,
            """{"error":{"message":"max_tokens is too large: 64000. This model supports at most 16384 completion tokens."}}""",
        )
        assertEquals(16_384, evidence!!.outputLimitTokens)
    }

    @Test
    fun anthropicMaxTokensErrorNamesTheRealOutputLimit() {
        val evidence = ModelLimitEvidence.parse(
            400,
            """{"type":"error","error":{"type":"invalid_request_error","message":"max_tokens: 64000 > 8192, which is the maximum allowed number of output tokens for claude-3-5-sonnet"}}""",
        )
        assertEquals(8_192, evidence!!.outputLimitTokens)
    }

    @Test
    fun byteSizeAndRateLimitErrorsAreNotTokenEvidence() {
        assertNull(ModelLimitEvidence.parse(413, """{"error":{"message":"Request exceeds the maximum allowed number of bytes (33554432)."}}"""))
        assertNull(ModelLimitEvidence.parse(429, """{"error":{"message":"Request too large for model on tokens per minute (TPM): limit 6000, requested 7000"}}"""))
    }

    @Test
    fun plainContextWordingStillYieldsANumber() {
        val evidence = ModelLimitEvidence.parse(
            400,
            """{"error":{"message":"This model's maximum context length is 8192 tokens."}}""",
        )
        assertEquals(8_192, evidence!!.contextLimitTokens)
    }
}
