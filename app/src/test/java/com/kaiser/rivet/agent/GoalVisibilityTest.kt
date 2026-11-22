package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalVisibilityTest {
    @Test fun aProjectGoalRowBelongsOnlyToItsOwningSession() {
        val goal = GoalDocument.newGoal("Ship the dashboard", "original")
        assertEquals(goal.calmLine(), goal.lineForSession("original"))
        assertNull(goal.lineForSession("different"))
        assertNull(goal.lineForSession(null))
        assertNull(goal.withState(GoalState.Dropped, "original").lineForSession("original"))
    }
}
