package com.kaiser.rivet.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerminalEnvironmentTest {
    @get:Rule val temp = TemporaryFolder()

    private val base = listOf(
        "HOME=/data/app/home",
        "PATH=$SYSTEM_PATH",
        "TMPDIR=/data/app/tmp",
        "PWD=/data/app/work",
        "LANG=C.UTF-8",
        "TERM=old",
    )

    @Test fun addsTermAndAFolderOnlyDollarPrompt() {
        val env = terminalEnvironment(base)

        assertEquals("ENV=/dev/null", env[env.size - 3])
        assertEquals("TERM=xterm-256color", env[env.size - 2])
        assertEquals("PS1=\\W \\$ ", env.last())
        assertFalse(env.last().contains("@"))
        assertFalse(env.last().contains("\\u"))
        assertFalse(env.last().contains("\\h"))
        assertEquals(base.dropLast(1), env.dropLast(3))
    }

    @Test fun absentToolchainLeavesPathAtTheSystemBase() {
        val env = terminalEnvironment(base)

        assertEquals("PATH=$SYSTEM_PATH", env.single { it.startsWith("PATH=") })
        assertFalse(env.joinToString("\n").contains("termux"))
    }

    @Test fun presentToolchainPathIsPreserved() {
        val farm = "/data/app/files/toolchain/bin"
        val env = terminalEnvironment(base.map {
            if (it.startsWith("PATH=")) "PATH=$farm:$SYSTEM_PATH" else it
        })

        assertEquals("PATH=$farm:$SYSTEM_PATH", env.single { it.startsWith("PATH=") })
    }

    @Test fun busyboxAshIsPreferredOnlyWhenItReallyExists() {
        val farm = temp.newFolder("farm")
        assertEquals(SYSTEM_SHELL, terminalShellPath(farm))

        val ash = File(farm, "ash").apply { writeText("") }
        assertTrue(ash.isFile)
        assertEquals(ash.absolutePath, terminalShellPath(farm))
        assertEquals(SYSTEM_SHELL, terminalShellPath(null))
    }
}
