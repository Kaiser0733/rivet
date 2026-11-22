package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltInSlashTest {
    @Test fun typedCommandsUseTheSameTokensAsTheMenu() {
        for (command in BuiltInSlash.entries) {
            assertEquals(command, SlashCommands.builtIn("/${command.token.uppercase()}"))
            assertNull(SlashCommands.unknownLeading("/${command.token.uppercase()}", emptyList()))
        }
        assertEquals(BuiltInSlash.BasicYolo, SlashCommands.builtIn("/basic yolo"))
        assertEquals(BuiltInSlash.Goal, SlashCommands.builtIn("/Go\u200Bal status"))
        assertEquals("My goal", (parseGoalCommand(SlashCommands.normalize("/Goal My goal")) as GoalCommand.Set).text)
        assertNull(SlashCommands.builtIn("/goal-list"))
    }
}
