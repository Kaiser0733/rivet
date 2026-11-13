package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalMemoryTest {
    @Test fun goalMarkdownRoundTripsAndKeepsProgress() {
        val goal = GoalDocument.newGoal("Ship the login flow", "s1")
            .withProgress("auth screens rebuilt; build green", "s1")
            .withProgress("blocked: needs Play Console key", "s2")

        val parsed = GoalDocument.parse(GoalDocument.render(goal))

        assertEquals(goal, parsed)
        assertTrue(parsed!!.calmLine().contains("last progress: blocked: needs Play Console key"))
    }

    @Test fun replacingAGoalKeepsTheOldTextInTheLog() {
        val old = GoalDocument.newGoal("Old goal", "s1")
        val replacement = GoalDocument.replaceGoal(old, "New goal", "s2")

        assertEquals(GoalState.Active, replacement.state)
        assertTrue(replacement.progress.any { it.text.contains("Old goal") })
        assertEquals("New goal", GoalDocument.parse(GoalDocument.render(replacement))!!.text)
    }

    @Test fun userCommandsAreRecognisedOnlyAtTheGoalWord() {
        assertEquals(GoalCommand.Status, parseGoalCommand("/goal"))
        assertEquals(GoalCommand.Status, parseGoalCommand("/goal status"))
        assertEquals(GoalCommand.Pause, parseGoalCommand("/goal pause"))
        assertEquals(GoalCommand.Done, parseGoalCommand("/goal done"))
        assertEquals(GoalCommand.Clear, parseGoalCommand("/goal clear"))
        assertEquals(GoalCommand.Set("fix tests"), parseGoalCommand("/goal fix tests"))
        assertNull(parseGoalCommand("/goalkeeper is a position"))
        assertNull(parseGoalCommand("please /goal this"))
    }

    @Test fun memoryAppendRefusesSecretsAndGardensAtTheThreshold() {
        assertTrue(MemoryFormat.append("", "api_key = sk-live-abcdefghijklmnop")
            is MemoryFormat.Append.Refused)
        val saved = MemoryFormat.append("", "Kotlin 2.1, Compose BOM 2025.03") as MemoryFormat.Append.Saved
        assertTrue(saved.markdown.contains("Kotlin 2.1"))
        assertEquals(1, saved.lines)

        val overSoft = MemoryFormat.render((1..MemoryFormat.SOFT_LINES).map { "fact $it" })
        assertTrue(MemoryFormat.needsGardening(overSoft))
        val softAppend = MemoryFormat.append(overSoft, "new fact") as MemoryFormat.Append.Saved
        assertTrue(softAppend.garden)
        assertTrue(MemoryFormat.append(MemoryFormat.render((1..MemoryFormat.HARD_LINES).map { "f$it" }),
            "new fact") is MemoryFormat.Append.Refused)
    }

    @Test fun forgetAndEditStayLineBased() {
        val memory = MemoryFormat.render(listOf("stack: Kotlin", "gotcha: don't edit generated"))
        val forgotten = MemoryFormat.forget(memory, 0)
        assertFalse(forgotten.contains("stack: Kotlin"))
        assertTrue(forgotten.contains("gotcha"))
        assertTrue(MemoryFormat.replaceAll(memory, "- stack: Kotlin\n- secret = abcdefgh")
            is MemoryFormat.Append.Refused)
    }

    @Test fun contextIsResentOnlyWhenTheSnapshotChanges() {
        val goal = GoalDocument.newGoal("ship it", "s1")
        val messages = mutableListOf<AgentMessage>()
        val first = GoalMemoryContext.updates(messages, "tree://p", goal, "- stack: Kotlin")
        assertEquals(1, first.size)
        messages += first
        assertTrue(GoalMemoryContext.updates(messages, "tree://p", goal, "- stack: Kotlin").isEmpty())
        val changed = GoalMemoryContext.updates(messages, "tree://p", goal.withState(GoalState.Paused, "s1"),
            "- stack: Kotlin")
        assertEquals(1, changed.size)
        assertTrue(changed.single().internalContext!!.content.contains("paused"))
    }

    @Test fun goalMemoryStaysValidAtMaxFactsAndWithoutWorkspace() {
        val goal = GoalDocument.newGoal("ship safely", "s1")
        val hugeMemory = (1..200).joinToString("\n") { i -> "- fact $i: ${"é".repeat(280)}" }
        val bounded = GoalMemoryContext.content(goal, hugeMemory)
        assertTrue(bounded.toByteArray(Charsets.UTF_8).size <= InternalContext.MAX_CONTENT_BYTES)

        val boundMsg = GoalMemoryContext.updates(emptyList(), "tree://p", goal, hugeMemory).single()
        assertNotNull(boundMsg.validatedContext())

        val sessionOnly = GoalMemoryContext.updates(emptyList(), null, goal, "").single()
        assertNotNull(sessionOnly.validatedContext())
    }
}
