package com.kaiser.rivet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessLogRingTest {
    @Test fun stdoutAndStderrStaySeparateAndBounded() {
        val ring = ProcessLogRing(maxStdoutChars = 8, maxStderrChars = 5)
        ring.append("123456", "")
        ring.append("7890", "err")
        val snapshot = ring.snapshot()
        assertEquals("34567890", snapshot.stdout)
        assertEquals("err", snapshot.stderr)
        assertTrue(snapshot.stderrPresent)
    }

    @Test fun emptyOutputHasNoStderr() {
        val ring = ProcessLogRing()
        ring.append("out", "")
        assertFalse(ring.snapshot().stderrPresent)
    }
}
