package com.kaiser.rivet.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
