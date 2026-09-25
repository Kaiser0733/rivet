package com.kaiser.rivet.provider

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderErrorClassificationTest {
    @Test fun onlyExplicitContextSignalsClassifyAsOverflow() {
        assertTrue(httpError(400, """{"error":{"code":"context_length_exceeded","message":"too long"}}""")
            is ProviderError.ContextOverflow)
        assertTrue(providerMessage("prompt is too long") is ProviderError.ContextOverflow)
        assertTrue(httpError(400, """{"error":{"message":"invalid option"}}""")
            is ProviderError.ProviderMessage)
    }

    @Test fun resourceExhaustionIsDistinctFromRateLimit() {
        assertTrue(httpError(429, """{"error":{"message":"ResourceExhausted: Worker local total request limit reached (16/16)"}}""")
            is ProviderError.ResourceExhausted)
        assertTrue(httpError(429, """{"error":{"message":"slow down"}}""") is ProviderError.RateLimited)
    }

    @Test fun exhaustedAccountQuotaDoesNotSuggestWaiting() {
        val error = httpError(429, """{"error":{"code":"insufficient_quota","message":"quota exceeded"}}""")
        assertTrue(error is ProviderError.UsageLimit)
        assertTrue(error.text().contains("billing or usage settings"))
    }

    @Test fun providerWireMessageIsNotShownAsChatError() {
        val error = ProviderError.ProviderMessage("internal_request_id=abc123")
        assertFalse(error.text().contains("internal_request_id"))
    }
}
