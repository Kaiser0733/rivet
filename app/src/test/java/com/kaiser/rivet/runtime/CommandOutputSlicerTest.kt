package com.kaiser.rivet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandOutputSlicerTest {
    private val numbered = (1..1_200).joinToString("\n") { "line $it" }

    @Test fun offsetAndMaxLinesPageToAnExactNextOffset() {
        val slice = CommandOutputSlicer.slice(numbered, "", offset = 500, maxLines = 50)
        assertEquals(1_200, slice.totalLines)
        assertEquals(50, slice.returnedLines)
        assertEquals("line 501", slice.stdout.lineSequence().first())
        assertEquals("line 550", slice.stdout.lineSequence().last())
        assertEquals(550, slice.nextOffset)
        assertFalse(slice.stdoutTruncated)
    }

    @Test fun tailReturnsTheLastLinesAndNoNextOffset() {
        val slice = CommandOutputSlicer.slice(numbered, "", tail = 10)
        assertEquals("line 1191", slice.stdout.lineSequence().first())
        assertEquals("line 1200", slice.stdout.lineSequence().last())
        assertEquals(10, slice.returnedLines)
        assertNull(slice.nextOffset)
    }

    @Test fun stdoutPreservesOriginalLineTerminators() {
        assertEquals("hello\n", CommandOutputSlicer.slice("hello\n", "").stdout)
        assertEquals("a\nb", CommandOutputSlicer.slice("a\nb", "").stdout)
        assertEquals("a\nb\n", CommandOutputSlicer.slice("a\nb\n", "").stdout)
        assertEquals("b\n", CommandOutputSlicer.slice("a\nb\n", "",
            offset = 1, maxLines = 1).stdout)
        assertEquals("a", CommandOutputSlicer.slice("a\nb\n", "",
            offset = 0, maxLines = 1).stdout)
    }

    @Test fun grepIsLiteralAndReportsMatchedLines() {
        val output = buildString {
            repeat(1_200) {
                append(if (it % 100 == 0) "ERROR item $it" else "ok item $it").append('\n')
            }
        }
        val slice = CommandOutputSlicer.slice(output, "", grep = "ERROR", maxLines = 5)
        assertEquals(12, slice.matchedLines)
        assertEquals(5, slice.returnedLines)
        assertTrue(slice.stdout.lineSequence().all { it.startsWith("ERROR") })
        assertEquals(5, slice.nextOffset)
    }

    @Test fun offsetAndTailAreMutuallyExclusive() {
        val failure = runCatching {
            CommandOutputSlicer.slice(numbered, "", offset = 1, tail = 2)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("offset or tail"))
    }

    @Test fun byteCapStopsAtACompleteLineAndOffersTheNextCursor() {
        val long = (1..100).joinToString("\n") { "line $it " + "x".repeat(200) }
        val slice = CommandOutputSlicer.slice(long, "", maxLines = 100, maxBytes = 1_024)
        assertTrue(slice.stdoutTruncated)
        assertTrue(slice.returnedLines in 1..9)
        assertTrue(slice.stdout.lineSequence().all { it.startsWith("line ") })
        assertTrue(slice.nextOffset != null)
    }

    @Test fun stderrStaysSeparateAndReportsPresenceBeforeTruncation() {
        val slice = CommandOutputSlicer.slice(numbered, "first error\nlast error",
            offset = 900, maxLines = 10)
        assertEquals(10, slice.returnedLines)
        assertEquals("first error\nlast error", slice.stderr)
        assertTrue(slice.stderrPresent)
        assertFalse(slice.stderrTruncated)
        assertTrue(CommandOutputSlicer.slice("", "").stderrPresent.not())
    }

    @Test fun stderrIsBoundedWithoutBeingHiddenByStdoutPaging() {
        val stderr = "first\n" + "e".repeat(20_000) + "\nlast"
        val slice = CommandOutputSlicer.slice("out", stderr, maxBytes = 1_024)
        assertTrue(slice.stderrPresent)
        assertTrue(slice.stderrTruncated)
        assertTrue(slice.stderr.startsWith("first"))
        assertTrue(slice.stderr.endsWith("last"))
    }

    @Test fun grepAndLineLimitsNameTheActionableBounds() {
        val failure = runCatching {
            CommandOutputSlicer.slice(numbered, "", grep = "x".repeat(257))
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("256"))
    }
}
