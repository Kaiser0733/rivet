package com.kaiser.rivet.chat

import com.kaiser.rivet.runtime.ToolchainAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The workspace briefing states limits the model has to plan around, so the numbers
// here must match what the runtime actually enforces.
class WorkspaceLimitInstructionTest {

    private val allToolchain = ToolchainAvailability(busybox = true, python = true, git = true)
    private val briefing = ChatViewModel.systemInstruction(workspace = true,
        availability = allToolchain)

    @Test fun namesTheShippedRuntimeLimits() {
        listOf("16 MiB", "128 KiB", "120 seconds", "2000", "1 MiB", "50,000", "1 GiB")
            .forEach { assertTrue(it, briefing.contains(it)) }
    }

    @Test fun namesTheShellAndTheToolchainItRunsWith() {
        assertTrue(briefing.contains("/system/bin/sh"))
        assertTrue(briefing.contains("BusyBox applet farm"))
        assertTrue(briefing.contains("coreutils"))
        listOf("Python 3.12.3", "python3", "ensurepip", "python3 -m pip",
            "no bz2 or lzma", "tkinter, curses, or readline")
            .forEach { assertTrue(it, briefing.contains(it)) }
        listOf("Git 2.56", "push over HTTPS", "Settings > GitHub",
            "public clones and local work still work", "Node", "system package managers")
            .forEach { assertTrue(it, briefing.contains(it)) }
    }

    @Test fun absenceIsStatedPerPiece() {
        val absent = ChatViewModel.systemInstruction(workspace = true,
            availability = ToolchainAvailability())
        assertTrue(absent.contains("BusyBox applet farm is not available in this build"))
        assertTrue(absent.contains("Python is not available in this build"))
        assertTrue(absent.contains("Git is not available in this build"))
        assertFalse(absent.contains("Python 3.12.3 is available"))
        assertFalse(absent.contains("Git 2.56 is available"))

        val noGit = ChatViewModel.systemInstruction(workspace = true,
            availability = allToolchain.copy(git = false))
        assertTrue(noGit.contains("Python 3.12.3 is available"))
        assertTrue(noGit.contains("Git is not available in this build"))
        assertFalse(noGit.contains("Pushing to GitHub works"))
    }

    @Test fun governorStateIsAnAppObservationAndOnlyWhenNotReady() {
        assertEquals(ChatViewModel.systemInstruction(workspace = true,
                availability = allToolchain),
            ChatViewModel.systemInstruction(workspace = true, terminalState = null,
                availability = allToolchain))
        val stopped = ChatViewModel.systemInstruction(workspace = true,
            terminalState = "Terminal: stopped a command — memory limit",
            availability = allToolchain)
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
        assertTrue(text.contains("no file, terminal, shell, Git, build, or test access"))
        assertFalse(text.contains("16 MiB"))
    }
}
