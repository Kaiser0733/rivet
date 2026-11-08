package com.kaiser.rivet.runtime

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The terminal panel's 4-arg `runCommand` and the agent both land in CommandProcess,
 * whose cap is this watcher. The test uses a real `/proc` tree so it exercises the same
 * process enumeration a device uses, not a synthetic map.
 */
class CommandTreeCapTest {
    @Test fun watcherStopsARealTreeOverTheCap() = runBlocking {
        assumeTrue("needs /proc", File("/proc").isDirectory)
        assumeTrue("needs a POSIX shell", File("/bin/sh").canExecute())

        val shell = ProcessBuilder("/bin/sh", "-c",
            "i=0; while [ \$i -lt 70 ]; do sleep 30 & i=\$((i+1)); done; wait").start()
        var stopped = false
        try {
            val stop = withTimeout(20_000) {
                watchCommandTree(
                    root = shell.pid(),
                    memoryCapBytes = null,
                    onSample = {},
                    onTooManyChildren = {},
                    onMemoryLimit = {},
                    terminate = {
                        stopped = true
                        shell.toHandle().descendants().forEach { it.destroyForcibly() }
                        shell.destroyForcibly()
                    },
                )
            }

            assertEquals(TreeWatchStop.TooManyChildren, stop)
            assertTrue(stopped)
        } finally {
            shell.toHandle().descendants().forEach { it.destroyForcibly() }
            shell.destroyForcibly()
            shell.waitFor()
        }
    }
}
