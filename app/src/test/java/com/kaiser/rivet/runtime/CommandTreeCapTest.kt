package com.kaiser.rivet.runtime

import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The terminal panel's 4-arg `runCommand` and the agent both land in CommandProcess,
 * whose cap is this watcher. The test uses a real `/proc` tree and a real `setsid`
 * command session, so it exercises the same enumeration and teardown a device uses.
 */
class CommandTreeCapTest {
    @Test fun watcherStopsARealTreeOverTheCap() = runBlocking {
        assumeTrue("needs /proc", File("/proc").isDirectory)
        assumeTrue("needs a POSIX shell", File("/bin/sh").canExecute())
        assumeTrue("needs setsid", File("/usr/bin/setsid").canExecute() ||
            File("/bin/setsid").canExecute())
        assumeTrue("needs external kill", File("/bin/kill").canExecute())

        val pidFile = File.createTempFile("rivet-cap-", ".pid")
        val shell = ProcessBuilder("setsid", "/bin/sh", "-c",
            "echo \$\$ > '${pidFile.absolutePath}'; " +
                "i=0; while [ \$i -lt 70 ]; do sleep 30 & i=\$((i+1)); done; wait").start()
        val pid = withTimeout(10_000) {
            var found: Int? = null
            while (found == null) {
                found = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
                if (found == null) delay(25)
            }
            found
        }!!
        fun killGroup() {
            ProcessBuilder("/bin/kill", "-s", "KILL", "--", "-$pid").start().waitFor()
        }
        var stopped = false
        try {
            val stop = withTimeout(20_000) {
                watchCommandTree(
                    root = pid,
                    memoryCapBytes = null,
                    onSample = {},
                    onTooManyChildren = {},
                    onMemoryLimit = {},
                    terminate = {
                        stopped = true
                        killGroup()
                    },
                )
            }

            assertEquals(TreeWatchStop.TooManyChildren, stop)
            assertTrue(stopped)
        } finally {
            killGroup()
            shell.destroyForcibly()
            shell.waitFor()
            pidFile.delete()
        }
    }

    @Test fun watcherStopsARealMemoryLoopBeforeTheDeviceRunsOut() = runBlocking {
        assumeTrue("needs /proc", File("/proc").isDirectory)
        assumeTrue("needs a POSIX shell", File("/bin/sh").canExecute())
        assumeTrue("needs setsid", File("/usr/bin/setsid").canExecute() ||
            File("/bin/setsid").canExecute())
        val python = listOf(File("/usr/bin/python3"), File("/bin/python3"))
            .firstOrNull { it.canExecute() }
        assumeTrue("needs python3", python != null)

        val pidFile = File.createTempFile("rivet-memory-", ".pid")
        val script = "echo \$\$ > '${pidFile.absolutePath}'; " +
            "exec '${python!!.absolutePath}' -c 'x=[b\"x\"*1048576 for _ in range(120)]; import time; time.sleep(30)'"
        val shell = ProcessBuilder("setsid", "/bin/sh", "-c", script).start()
        val pid = withTimeout(10_000) {
            var found: Int? = null
            while (found == null) {
                found = pidFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
                if (found == null) delay(25)
            }
            found
        }!!
        fun killGroup() {
            ProcessBuilder("/bin/kill", "-s", "KILL", "--", "-$pid").start().waitFor()
        }
        var stopped = false
        try {
            val stop = withTimeout(20_000) {
                watchCommandTree(
                    root = pid,
                    memoryCapBytes = 32L * 1024 * 1024,
                    onSample = {},
                    onTooManyChildren = {},
                    onMemoryLimit = {},
                    terminate = {
                        stopped = true
                        killGroup()
                    },
                )
            }

            assertEquals(TreeWatchStop.MemoryLimit, stop)
            assertTrue(stopped)
        } finally {
            killGroup()
            shell.destroyForcibly()
            shell.waitFor()
            pidFile.delete()
        }
    }

    @Test fun allocationFailureIsReportedAsTheGovernorStop() {
        assertTrue(reportsMemoryFailure("Traceback...\nMemoryError"))
        assertTrue(reportsMemoryFailure("python: can't allocate memory"))
        assertTrue(reportsMemoryFailure("sh: Cannot allocate memory"))
        assertFalse(reportsMemoryFailure("command exited 1"))
    }

    @Test fun addressSpaceBackstopHasHeadroomAndZeroMeansOff() {
        assertEquals(0L, addressSpaceLimitBytes(null))
        assertEquals(0L, addressSpaceLimitBytes(0L))
        assertEquals(3L * 256L * 1024L * 1024L, addressSpaceLimitBytes(256L * 1024L * 1024L))
    }
}
