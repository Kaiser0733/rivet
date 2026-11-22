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
    @Test fun completeBuiltInsPastedOverAnOpenSlashPrefixStillDispatch() {
        for (input in listOf("/goal", "/Goal", "/goal finish the tea essay", "/compact")) {
            val expected = SlashCommands.builtIn(input)
            assertEquals(expected, SlashCommands.builtIn("/" + input))
            assertEquals(expected, SlashCommands.builtIn("\uFEFF" + input + "\n"))
            assertNull(SlashCommands.unknownLeading("/" + input, emptyList()))
        }
        assertEquals(GoalCommand.Set("finish the tea essay"),
            parseGoalCommand(SlashCommands.normalize("//goal finish the tea essay")))
    }
}
