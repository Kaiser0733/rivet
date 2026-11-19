package com.kaiser.rivet.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTurnSegmentationTest {

    private data class Msg(val role: String, val text: String)

    @Test
    fun midTurnSliceAlignsUserTurnDurationWithoutCountingOrphanHead() {
        val items = listOf(
            // Orphan tail of an earlier cut-off turn before first user message
            TurnItem("proc-orphan", isUser = false, isAnswer = false, isProcess = true),
            TurnItem("answer-orphan", isUser = false, isAnswer = true, isProcess = false),
            // Visible User turn 0
            TurnItem("user-0", isUser = true, isAnswer = false, isProcess = false),
            TurnItem("proc-0", isUser = false, isAnswer = false, isProcess = true),
            TurnItem("answer-0", isUser = false, isAnswer = true, isProcess = false),
        )
        val turns = segmentTurnItems(
            items = items,
            isStreaming = false,
            turnDurationsMs = mapOf(0 to 4200L),
            isInterruptedTurn = false,
            hasError = false,
        )
        assertEquals(2, turns.size)
        assertNull(turns[0].spec.durationMs)
        assertEquals(4200L, turns[1].spec.durationMs)
    }


    @Test
    fun aNoticeRowIsItsOwnPartOutsideTheProcessFold() {
        val items = listOf(
            TurnItem("u", isUser = true, isAnswer = false, isProcess = false),
            TurnItem("p", isUser = false, isAnswer = false, isProcess = true),
            TurnItem("n", isUser = false, isAnswer = false, isProcess = false, isNotice = true),
            TurnItem("a", isUser = false, isAnswer = true, isProcess = false),
        )
        val turns = segmentTurnItems(
            items = items,
            isStreaming = false,
            turnDurationsMs = emptyMap(),
            isInterruptedTurn = false,
            hasError = false,
        )
        val parts = turns.single().parts
        assertEquals(3, parts.size)
        assertTrue(parts[0] is TurnPart.Process<*>)
        assertTrue(parts[1] is TurnPart.Notice<*>)
        assertEquals("n", (parts[1] as TurnPart.Notice<String>).item)
        assertTrue(parts[2] is TurnPart.Answer<*>)
    }

    @Test
    fun storedCompletedTurnRendersCompactDurationAndStoppedTurnDoesNot() {
        val stored = mapOf(0 to 65_000L)
        val completedItems = listOf(
            TurnItem("u", isUser = true, isAnswer = false, isProcess = false),
            TurnItem("a", isUser = false, isAnswer = true, isProcess = false),
        )
        val completed = segmentTurnItems(
            items = completedItems,
            isStreaming = false,
            turnDurationsMs = stored,
            isInterruptedTurn = false,
            hasError = false,
        ).single()
        assertEquals("Completed in 1m5s", completed.spec.foldLabel())

        val stopped = segmentTurnItems(
            items = completedItems,
            isStreaming = false,
            turnDurationsMs = stored,
            isInterruptedTurn = true,
            hasError = false,
        ).single()
        assertEquals("Stopped", stopped.spec.foldLabel())
    }

    @Test
    fun compactDurationFormatsMillisecondsSecondsAndMinutes() {
        assertEquals("1s", formatRunDuration(1_500L))
        assertEquals("2s", formatRunDuration(2_000L))
        assertEquals("1m5s", formatRunDuration(65_000L))
        assertEquals("1h1m1s", formatRunDuration(3_661_000L))
        assertEquals("1s", formatRunDuration(0L))
    }

    @Test
    fun remapsReasoningAndDurationsWhenContextStrippedAndPrefixTrimmed() {
        val oldMessages = listOf(
            Msg("User", "u0"),
            Msg("Assistant", "a0"),
            Msg("Context", "ctx-goal"),
            Msg("User", "u1"),
            Msg("Context", "ctx-skill"),
            Msg("Assistant", "a1"),
        )
        // Suppose visible window stripped Context rows AND trimmed [u0, a0].
        val newVisible = listOf(
            Msg("User", "u1"),
            Msg("Assistant", "a1"),
        )
        val remapped = remapTurnMetadataAfterVisibilityChange(
            oldMessages = oldMessages,
            newMessages = newVisible,
            oldReasoning = mapOf(1 to "thought-0", 5 to "thought-1"),
            oldDurations = mapOf(0 to 1000L, 1 to 2500L),
            isInternal = { it.role == "Context" },
            isUser = { it.role == "User" },
            sameMessage = { a, b -> a == b },
        )
        assertEquals(mapOf(1 to "thought-1"), remapped.reasoning)
        assertEquals(mapOf(0 to 2500L), remapped.durations)
    }

    @Test
    fun remapsReasoningWithinSameTurnAfterContextInjectionOnly() {
        val oldMessages = listOf(
            Msg("User", "u0"),
            Msg("Context", "ctx"),
            Msg("Assistant", "a0"),
        )
        val newVisible = listOf(
            Msg("User", "u0"),
            Msg("Assistant", "a0"),
        )
        val remapped = remapTurnMetadataAfterVisibilityChange(
            oldMessages = oldMessages,
            newMessages = newVisible,
            oldReasoning = mapOf(2 to "deep thought"),
            oldDurations = mapOf(0 to 900L),
            isInternal = { it.role == "Context" },
            isUser = { it.role == "User" },
            sameMessage = { a, b -> a == b },
        )
        assertEquals(mapOf(1 to "deep thought"), remapped.reasoning)
        assertEquals(mapOf(0 to 900L), remapped.durations)
    }
}
