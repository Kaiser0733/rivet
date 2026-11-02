package com.kaiser.rivet.provider

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderErrorClassificationTest {
    @Test fun providerJsonDepthIsBoundedBeforeParsing() {
        val deeplyNested = "{\"args\":" + "[".repeat(10_000) + "0" + "]".repeat(10_000) + "}"
        assertFalse(jsonNestingWithinLimit(deeplyNested))
        assertTrue(parseJsonObject(deeplyNested) == null)
        val braces = "{".repeat(100)
        val quoted = """{"text":"$braces"}"""
        assertTrue(jsonNestingWithinLimit(quoted))
        assertTrue(parseJsonObject(quoted) != null)
    }

    @Test fun onlyExplicitContextSignalsClassifyAsOverflow() {
        assertTrue(httpError(400, """{"error":{"code":"context_length_exceeded","message":"too long"}}""")
            is ProviderError.ContextOverflow)
        assertTrue(providerMessage("prompt is too long") is ProviderError.ContextOverflow)
        assertTrue(httpError(400, """{"error":{"message":"input length exceeds this model's context window"}}""")
            is ProviderError.ContextOverflow)
        assertTrue(httpError(400, """{"error":{"message":"prompt exceeds the context window"}}""")
            is ProviderError.ContextOverflow)
        assertTrue(httpError(400, """{"error":{"message":"invalid option"}}""")
            is ProviderError.ProviderMessage)
        assertTrue(httpError(400, """{"error":{"message":"set a context window option"}}""")
            is ProviderError.ProviderMessage)
    }

    @Test fun geminiInputLimitMessagesAreOverflowBeforeModelNotFoundFallback() {
        val parenthesized = httpError(
            400,
            """{"error":{"status":"INVALID_ARGUMENT","message":"The input token count (12345) exceeds the maximum number of tokens allowed (8192)."}}""",
        )
        val modelWording = httpError(
            400,
            """{"error":{"status":"INVALID_ARGUMENT","message":"The input token count is 12345 but model only supports up to 8192."}}""",
        )

        assertTrue(parenthesized is ProviderError.ContextOverflow)
        assertTrue(modelWording is ProviderError.ContextOverflow)
    }

    @Test fun invalidArgumentAndUnrelatedModelMentionsKeepTheirOwnClassification() {
        assertTrue(httpError(400, """{"error":{"status":"INVALID_ARGUMENT","message":"invalid temperature for this model"}}""")
            is ProviderError.ProviderMessage)
        assertTrue(httpError(400, """{"error":{"message":"model 'missing' does not exist"}}""")
            is ProviderError.ModelNotFound)
    }

    @Test fun resourceExhaustionIsDistinctFromRateLimit() {
        assertTrue(httpError(429, """{"error":{"message":"ResourceExhausted: Worker local total request limit reached (16/16)"}}""")
            is ProviderError.ResourceExhausted)
        assertTrue(httpError(429, """{"error":{"message":"slow down"}}""") is ProviderError.RateLimited)
    }

    // Google says "Quota exceeded … per minute" for a limit that clears by itself. Reading
    // that as the billing limit tells the user to check their plan when waiting is the fix.
    @Test fun aPerMinuteRateLimitIsNotABillingProblem() {
        val error = httpError(429, """{"error":{"code":429,"status":"RESOURCE_EXHAUSTED","message":"Quota exceeded for quota metric 'Generate Content API requests per minute' and limit 'GenerateContentRequestsPerMinutePerProjectPerModel-FreeTier' of 15 per minute per project."}}""")
        assertTrue(error is ProviderError.ResourceExhausted)
        assertFalse(error.text().contains("billing"))
    }

    @Test fun exhaustedAccountQuotaDoesNotSuggestWaiting() {
        val error = httpError(429, """{"error":{"code":"insufficient_quota","message":"quota exceeded"}}""")
        assertTrue(error is ProviderError.UsageLimit)
        assertTrue(error.text().contains("billing or usage settings"))
    }

    // A 403 usually explains itself ("the API is not enabled", "region not supported"), and
    // the fixed sentence alone sends the user to re-check a key that is fine. The provider's
    // words must reach the banner; a bodyless rejection keeps the short sentence.
    @Test fun authAndServerBodiesReachTheUser() {
        val disabled = httpError(403, """{"error":{"message":"Generative Language API has not been used in project 123 before or it is disabled."}}""")
        assertTrue(disabled is ProviderError.Forbidden)
        assertTrue(disabled.text().contains("has not been used in project 123"))

        val key = httpError(401, """{"error":{"message":"Incorrect API key provided"}}""")
        assertTrue(key is ProviderError.Unauthorized)
        assertTrue(key.text().contains("Incorrect API key provided"))

        val down = httpError(503, """{"error":{"message":"The model is overloaded"}}""")
        assertTrue(down is ProviderError.Server)
        assertTrue(down.text().contains("The model is overloaded"))

        assertEquals("a bodyless rejection keeps the short sentence",
            "Authentication failed. Check the API key.", httpError(401, null).text())
        assertEquals("Provider server error (HTTP 500).", httpError(500, "").text())
    }

    @Test fun providerWireMessageIsNotShownAsChatError() {
        val error = ProviderError.ProviderMessage("internal_request_id=abc123")
        assertFalse(error.text().contains("internal_request_id"))
    }

    @Test fun dnsFailureAllowsForTransientResolutionAndGivesNextSteps() {
        val text = ProviderError.Network("dns").text()
        assertTrue(text.contains("can be temporary"))
        assertTrue(text.contains("Check your connection and try again"))
        assertTrue(text.contains("check the base URL"))
    }

    @Test fun responseTooLargeNamesRivetAndGivesActionableStep() {
        val text = ProviderError.ResponseTooLarge().text()
        assertTrue(text.contains("Rivet"))
        assertFalse(text.contains("The provider sent"))
        assertTrue(text.contains("smaller parts"))
        assertTrue(MAX_SSE_RESPONSE_BYTES == 16 * 1024 * 1024)
        assertTrue(MAX_PROVIDER_JSON_BODY_BYTES == 16 * 1024 * 1024)
    }
}
