package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoalCommandParseTest {
    @Test fun bareGoalShowsStatusEvenWhenTheKeyboardCapitalisedIt() {
        assertEquals(GoalCommand.Status, parseGoalCommand("/goal"))
        assertEquals(GoalCommand.Status, parseGoalCommand("/Goal"))
        assertEquals(GoalCommand.Status, parseGoalCommand("  /GOAL  "))
        assertEquals(GoalCommand.Status, parseGoalCommand("/goal status"))
    }

    @Test fun otherGoalSubcommandsIgnoreTheFirstLetterCase() {
        assertEquals(GoalCommand.Pause, parseGoalCommand("/Goal pause"))
        assertEquals(GoalCommand.Resume, parseGoalCommand("/goal RESUME"))
        assertEquals(GoalCommand.Done, parseGoalCommand("/GOAL done"))
        assertEquals(GoalCommand.Clear, parseGoalCommand("/Goal clear"))
        assertEquals(GoalCommand.Set("ship docs"), parseGoalCommand("/Goal ship docs"))
    }

    @Test fun wordsThatOnlyStartWithGoalAreNotCommands() {
        assertNull(parseGoalCommand("/goalkeeper"))
        assertNull(parseGoalCommand("/goals ship"))
        assertNull(parseGoalCommand("goal"))
    }
}
