package com.kaiser.rivet.ui.chat

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kaiser.rivet.agent.AgentApprovalGate
import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentToolCall
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ApprovalDialogStopTest {
    @get:Rule val compose = createComposeRule()

    @Test fun stopTurnIsReachableAndClearsThePendingApproval() = runTest {
        val gate = AgentApprovalGate()
        val request = AgentApprovalRequest(AgentToolCall("one", "write_file", "{}"), "Edit", "index.html")
        val approval = async(start = CoroutineStart.UNDISPATCHED) { gate.await(request) }
        val pending = requireNotNull(gate.pending.value)
        compose.setContent {
            ApprovalDialog(pending, onApprove = {}, onDeny = {}, onStop = { token ->
                if (gate.pending.value?.approvalToken == token) gate.cancel()
            })
        }

        compose.onNodeWithText("Stop turn").performClick()

        approval.join()
        assertNull(gate.pending.value)
        assertTrue(approval.isCancelled)
    }
}
