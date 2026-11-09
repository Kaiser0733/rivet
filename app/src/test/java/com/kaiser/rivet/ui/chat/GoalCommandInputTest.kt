package com.kaiser.rivet.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalCommandInputTest {
    @Test fun goalCommandIsRecognisedWithoutAProject() {
        assertTrue(isGoalCommand("/goal"))
        assertTrue(isGoalCommand("  /goal ship docs"))
        assertFalse(isGoalCommand("/goalkeeper"))
        assertFalse(isGoalCommand("ship docs"))
    }
}
