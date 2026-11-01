package com.kaiser.rivet.chat

import org.junit.Assert.assertTrue
import org.junit.Test

// The unavailability message tells the user the same numbers the model is told.
class CheckpointLimitsHintTest {

    @Test fun namesTheRealLimitsAndTheWayOut() {
        val hint = ChatViewModel.CHECKPOINT_LIMITS_HINT
        assertTrue(hint, hint.contains("50,000 items"))
        assertTrue(hint, hint.contains("1 GiB"))
        assertTrue(hint, hint.contains(".rivetignore"))
    }
}
