package com.kaiser.rivet.provider

/**
 * Pure concurrency/ownership guards for ChatGPT OAuth token refresh,
 * loopback cancellation, and provider editor sign-in/sign-out/save flows.
 */
internal object ChatGptSessionGuards {
    fun shouldPersistRefreshedBundle(bundleStillPresentInStore: Boolean): Boolean =
        bundleStillPresentInStore

    fun canApplySignInOutcome(
        isCurrentSession: Boolean,
        currentEditorId: String,
        expectedEditorId: String,
    ): Boolean = isCurrentSession && currentEditorId == expectedEditorId

    fun canStartSave(saveInFlight: Boolean): Boolean = !saveInFlight

    fun canApplySignOutState(
        currentEditorId: String,
        signedOutProviderId: String,
    ): Boolean = currentEditorId == signedOutProviderId
}
