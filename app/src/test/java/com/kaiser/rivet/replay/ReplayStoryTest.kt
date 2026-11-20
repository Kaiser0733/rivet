package com.kaiser.rivet.replay

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.agent.RunRecord
import com.kaiser.rivet.agent.RunStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayStoryTest {
    private val run = RunRecord("run", "project", "Fix the toolbar", RunPhase.Done,
        startedAt = 0, updatedAt = 43_251)

    @Test fun ordersCardsAndUsesTheCollapsedWorkLine() {
        val call = AgentToolCall("edit", "write_file", """{"path":"toolbar.kt"}""")
        val messages = listOf(AgentMessage.user("Restore the toolbar"),
            AgentMessage.assistant("", listOf(call)), AgentMessage.tools(listOf(
                AgentToolResult("edit", "write_file", "{}"),
                AgentToolResult("shot", "browser_shot", "{}", imagePath = "/shot.webp"),
            )), AgentMessage.assistant("The toolbar is back."))
        val story = ReplayStory.from(run, messages, "Demo", "rivet", setOf("/shot.webp"))
        assertEquals("Demo", story.projectName)
        assertEquals("rivet", story.handle)
        assertEquals(listOf(
            ReplayBeat.PromptCard::class, ReplayBeat.StepCard::class,
            ReplayBeat.PreviewCard::class, ReplayBeat.ResultCard::class,
        ), story.beats.map { it::class })
        assertEquals("Edited 1 file", (story.beats[1] as ReplayBeat.StepCard).title)
        assertEquals("toolbar.kt", (story.beats[1] as ReplayBeat.StepCard).detail)
        assertEquals("The toolbar is back.", (story.beats.last() as ReplayBeat.ResultCard).text)
        assertEquals(43_251L, story.totalMs)
        assertEquals(story.totalMs, story.beats.sumOf { it.holdMs })
    }

    @Test fun capsCardsAndKeepsAClosingResultAndPreview() {
        val steps = List(40) { RunStep(it, it.toLong(), "x".repeat(240)) }
        val story = ReplayStory.from(run.copy(steps = steps), listOf(
            AgentMessage.user("a".repeat(279) + "😀 trailing"),
            AgentMessage.tools(listOf(AgentToolResult("phone", "phone_shot", "{}", imagePath = "/shot.png"))),
        ), "Demo", availableShots = setOf("/shot.png"))
        assertEquals(12, story.beats.size)
        val prompt = (story.beats.first() as ReplayBeat.PromptCard).text
        assertTrue(prompt.length <= 280)
        assertTrue(prompt.endsWith("…"))
        assertFalse(prompt.dropLast(1).last().isHighSurrogate())
        assertTrue(story.beats.filterIsInstance<ReplayBeat.StepCard>().all { it.detail!!.length <= 140 })
        assertEquals(1, story.beats.filterIsInstance<ReplayBeat.PreviewCard>().size)
        assertTrue(story.beats.last() is ReplayBeat.ResultCard)
    }

    @Test fun emptyRunUsesTheJournalTitleAndNoUnavailableShot() {
        val story = ReplayStory.from(run, listOf(AgentMessage.tools(listOf(
            AgentToolResult("shot", "browser_shot", "{}", imagePath = "/unavailable.webp"),
        ))), "Demo")
        assertEquals(2, story.beats.size)
        assertEquals(run.title, (story.beats.first() as ReplayBeat.PromptCard).text)
        assertEquals("Run finished.", (story.beats.last() as ReplayBeat.ResultCard).text)
    }

    @Test fun clampsBothDurationLimits() {
        assertEquals(30_000L, ReplayStory.from(run.copy(updatedAt = 10), emptyList(), "Demo").totalMs)
        assertEquals(60_000L, ReplayStory.from(run.copy(updatedAt = 600_000), emptyList(), "Demo").totalMs)
    }

    @Test fun legacyTranscriptShowsTheLastReplyWithoutClaimingSuccess() {
        val messages = listOf(AgentMessage.user("Fix it"), AgentMessage.assistant("One edit is ready."))
        val story = ReplayStory.from(run, messages, "Demo", outcomeSaved = false)
        assertEquals("Run outcome wasn't saved.\n\nLast reply\nOne edit is ready.",
            (story.beats.last() as ReplayBeat.ResultCard).text)
    }

    @Test fun failedAndStoppedRunsKeepHonestOutcomesDespiteAssistantText() {
        val messages = listOf(AgentMessage.user("Fix it"), AgentMessage.assistant("All done."))
        val failed = ReplayStory.from(run.copy(phase = RunPhase.Failed,
            failure = "workspace_changed"), messages, "Demo")
        assertEquals("The run failed.", (failed.beats.last() as ReplayBeat.ResultCard).text)
        val stopped = ReplayStory.from(run.copy(phase = RunPhase.Stopped), messages, "Demo")
        assertEquals("The run was stopped.", (stopped.beats.last() as ReplayBeat.ResultCard).text)
    }
}
