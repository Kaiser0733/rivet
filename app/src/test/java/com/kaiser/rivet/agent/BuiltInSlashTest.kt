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

    @Test fun invisibleFormattingBetweenSlashPrefixesDoesNotMakeGoalUnknown() {
        val input = "/\u200B／Goal"
        assertEquals(BuiltInSlash.Goal, SlashCommands.builtIn(input))
        assertNull(SlashCommands.unknownLeading(input, emptyList()))
    }

    @Test fun goalStatusDoesNotCopyAndClipThePreviousAssistantAnswer() {
        val date = "2026-11-18"
        val goal = GoalDocument("ship the snake game", GoalState.Active, date)
            .withProgress("I don't have a live session's transcript. ".repeat(12), "session")
        assertEquals("Goal: ship the snake game · active · set $date", goal.statusLine())
    }
}
