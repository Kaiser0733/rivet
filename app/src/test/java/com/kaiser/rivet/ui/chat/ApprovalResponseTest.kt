package com.kaiser.rivet.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalResponseTest {
    @Test fun onlyTheButtonsAnswerForTheUser() {
        assertEquals(ApprovalResponse.Allow, approvalResponse(ApprovalEvent.Allow))
        assertEquals(ApprovalResponse.Deny, approvalResponse(ApprovalEvent.Deny))
    }

    @Test fun dismissingTheCardNeverDeniesARequestTheUserDidNotAnswer() {
        // A card hidden under a sheet could be dismissed by a stray Back or scrim tap;
        // that used to resolve as a denial the user never chose.
        assertEquals(ApprovalResponse.KeepWaiting, approvalResponse(ApprovalEvent.Dismiss))
    }
}
