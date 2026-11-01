package com.kaiser.rivet.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The workspace briefing states limits the model has to plan around, so the numbers
// here must match what the runtime actually enforces.
class WorkspaceLimitInstructionTest {

    private val briefing = ChatViewModel.systemInstruction(workspace = true)

    @Test fun namesTheShippedRuntimeLimits() {
        listOf("16 MiB", "128 KiB", "120 seconds", "2000", "1 MiB", "50,000", "1 GiB")
            .forEach { assertTrue(it, briefing.contains(it)) }
    }

    @Test fun namesTheShellTheCommandsActuallyRunIn() {
        assertTrue(briefing.contains("/system/bin/sh"))
        assertTrue(briefing.contains("PATH=/system/bin:/system/xbin:/vendor/bin"))
        listOf("node", "python", "git", "npm").forEach { assertTrue(it, briefing.contains(it)) }
    }

    @Test fun saysWhereUndoStops() {
        assertTrue(briefing.contains(".rivetignore"))
        assertTrue(briefing.contains("outside the workspace and outside Undo"))
    }

    @Test fun theNoWorkspaceBriefingClaimsNoProjectAccess() {
        val text = ChatViewModel.systemInstruction(workspace = false)
        assertTrue(text.contains("no file, terminal, shell, Git, build, or test access"))
        assertFalse(text.contains("16 MiB"))
    }
}
