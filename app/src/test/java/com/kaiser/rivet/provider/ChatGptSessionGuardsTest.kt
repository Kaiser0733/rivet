package com.kaiser.rivet.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatGptSessionGuardsTest {
    @Test fun refreshDoesNotResurrectSignedOutBundle() {
        assertFalse(ChatGptSessionGuards.shouldPersistRefreshedBundle(bundleStillPresentInStore = false))
        assertTrue(ChatGptSessionGuards.shouldPersistRefreshedBundle(bundleStillPresentInStore = true))
    }

    @Test fun staleSignInSessionCannotMutateCurrentEditor() {
        assertFalse(
            ChatGptSessionGuards.canApplySignInOutcome(
                isCurrentSession = false,
                currentEditorId = "p1",
                expectedEditorId = "p1",
            ),
        )
        assertFalse(
            ChatGptSessionGuards.canApplySignInOutcome(
                isCurrentSession = true,
                currentEditorId = "p2",
                expectedEditorId = "p1",
            ),
        )
        assertTrue(
            ChatGptSessionGuards.canApplySignInOutcome(
                isCurrentSession = true,
                currentEditorId = "p1",
                expectedEditorId = "p1",
            ),
        )
    }

    @Test fun concurrentSaveIsRejectedAndSignOutChecksEditorIdentity() {
        assertFalse(ChatGptSessionGuards.canStartSave(saveInFlight = true))
        assertTrue(ChatGptSessionGuards.canStartSave(saveInFlight = false))
        assertFalse(
            ChatGptSessionGuards.canApplySignOutState(
                currentEditorId = "p2",
                signedOutProviderId = "p1",
            ),
        )
        assertTrue(
            ChatGptSessionGuards.canApplySignOutState(
                currentEditorId = "p1",
                signedOutProviderId = "p1",
            ),
        )
    }
}
