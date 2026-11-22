package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLimitInstructionTest {

    private val briefing = ChatViewModel.systemInstruction(workspace = true)

    @Test fun keepsWorkspaceLimitsOutOfTransportDetails() {
        listOf("1 MiB", "50,000", "500 MB").forEach { assertTrue(it, briefing.contains(it)) }
        listOf("16 MiB", "128 KiB", "120 seconds", "2000").forEach { assertFalse(it, briefing.contains(it)) }
    }

    @Test fun availabilityIsNotFrozenIntoTheStaticPrompt() {
        // The current availability travels in per-turn InternalContext instead; a
        // carried-over chat must never repeat an old "Python is missing" claim.
        assertFalse(briefing.contains("Toolchain availability:"))
        assertFalse(briefing.contains("Python is not available"))
        assertFalse(briefing.contains("Git is not available"))
        assertFalse(briefing.contains("BusyBox applet farm is not available"))
    }

    @Test fun governorStateIsAnAppObservationAndOnlyWhenNotReady() {
        assertEquals(ChatViewModel.systemInstruction(workspace = true),
            ChatViewModel.systemInstruction(workspace = true, terminalState = null))
        val stopped = ChatViewModel.systemInstruction(workspace = true,
            terminalState = "Terminal: stopped a command — memory limit")
        assertTrue(stopped.contains(
            "Rivet app observation (not permission): Terminal: stopped a command — memory limit."))
        assertTrue(stopped.contains("Do not retry that command unchanged"))
    }

    @Test fun saysWhereUndoStops() {
        assertTrue(briefing.contains(".rivetignore"))
        assertTrue(briefing.contains("outside the workspace and outside Undo"))
    }

    @Test fun theNoWorkspaceBriefingClaimsNoProjectAccess() {
        val text = ChatViewModel.systemInstruction(workspace = false)
        // The denial is project-scoped: grants, the session inbox, and the separate
        // Termux app are user-granted phone access, not project access.
        assertTrue(text.contains("project files, the project terminal, and project Git are unavailable"))
        assertFalse(text.contains("16 MiB"))
    }
}
