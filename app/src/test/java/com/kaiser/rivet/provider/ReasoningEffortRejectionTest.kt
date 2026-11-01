package com.kaiser.rivet.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A provider that refuses an effort usually says so in its body. That body decides
// whether the user gets the ladder back or a plain failure.
class ReasoningEffortRejectionTest {

    private fun rejected(message: String, level: ReasoningLevel = ReasoningLevel.Medium) =
        isUnsupportedReasoningError(ProviderError.ProviderMessage(message), level)

    @Test fun anExplicitEffortFieldRejectionIsRecognised() {
        assertTrue(rejected("Invalid reasoning_effort: medium"))
        assertTrue(rejected("""{"error":{"message":"Unsupported value: 'reasoning_effort' does not support 'medium'"}}"""))
        assertTrue(rejected("reasoning effort must be one of: low, high"))
    }

    @Test fun aThinkingBudgetRejectionIsRecognised() {
        assertTrue(rejected("thinking budget 8192 is not allowed for this model"))
        assertTrue(rejected("thinking_config.budget_tokens unsupported"))
    }

    @Test fun theRequestedEffortNamedWithoutAnEffortFieldStillCounts() {
        // Providers that echo the value back without naming the field fall through to
        // the wire-name check.
        assertTrue(rejected("xhigh effort is not available for this model", ReasoningLevel.XHigh))
        assertFalse(rejected("xhigh effort is not available for this model", ReasoningLevel.Low))
    }

    @Test fun anUnrelatedFailureIsNotBlamedOnTheEffort() {
        assertFalse(rejected("Insufficient balance for this request."))
        assertFalse(rejected("upstream connect error or disconnect/reset"))
        assertFalse(isUnsupportedReasoningError(ProviderError.ProviderMessage("reasoning_effort invalid"),
            ReasoningLevel.Default))
    }

    @Test fun theClientsOwnUnsupportedConfigurationCounts() {
        assertTrue(isUnsupportedReasoningError(ProviderError.UnsupportedConfiguration(),
            ReasoningLevel.High))
    }
}
