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

    // River answers a rejected effort with a generic parameter message that never names
    // the effort, so the status code and the shape of the message have to carry it.
    private fun riverRejection(code: Int = 400) = ProviderError.ProviderMessage(
        "Invalid inference request. Check the model's supported parameters and input limits.", code)

    @Test fun theRiverParameterRejectionIsRecognisedAsAnEffortRejection() {
        assertTrue(isUnsupportedReasoningError(riverRejection(), ReasoningLevel.Ultra))
        // Off sends no effort at all, so nothing can be blamed on it.
        assertFalse(isUnsupportedReasoningError(riverRejection(), ReasoningLevel.Default))
    }

    @Test fun onlyClientSideRejectionsCarryTheBlame() {
        assertFalse(isUnsupportedReasoningError(riverRejection(500), ReasoningLevel.Ultra))
        assertFalse(isUnsupportedReasoningError(riverRejection(429), ReasoningLevel.Ultra))
        assertFalse(isUnsupportedReasoningError(
            ProviderError.ProviderMessage("Invalid inference request. Check the model's supported parameters."),
            ReasoningLevel.Ultra))
    }

    @Test fun anOversizedInputOrAnotherFieldIsNeverBlamedOnTheEffort() {
        assertFalse(isUnsupportedReasoningError(ProviderError.ProviderMessage(
            "Invalid request: input token count exceeds the maximum number of tokens allowed.", 400),
            ReasoningLevel.Ultra))
        assertFalse(isUnsupportedReasoningError(ProviderError.ProviderMessage(
            "Invalid parameter: temperature must be between 0 and 2.", 400), ReasoningLevel.Ultra))
        assertFalse(isUnsupportedReasoningError(ProviderError.ProviderMessage(
            "Invalid request: messages must not be empty.", 400), ReasoningLevel.Ultra))
        assertFalse(isUnsupportedReasoningError(ProviderError.ProviderMessage(
            "Invalid request. The model 'deepseek-x' does not exist.", 400), ReasoningLevel.Ultra))
    }

    @Test fun anEffortThatWasNotSentIsNotBlamed() {
        assertFalse(isUnsupportedReasoningError(riverRejection(), ReasoningLevel.Ultra,
            effortWasSent = false))
    }

    @Test fun theClientsOwnUnsupportedConfigurationCounts() {
        assertTrue(isUnsupportedReasoningError(ProviderError.UnsupportedConfiguration(),
            ReasoningLevel.High))
    }
}
