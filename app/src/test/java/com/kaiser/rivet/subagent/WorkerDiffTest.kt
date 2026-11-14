package com.kaiser.rivet.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerDiffTest {
    @Test fun reportsOneChangedLineWithBothCounts() {
        val diff = WorkerDiff.diff(
            "src/a.kt",
            "a\nb\nc".toByteArray(),
            "a\nB\nc".toByteArray(),
        )
        assertEquals(1, diff.additions)
        assertEquals(1, diff.deletions)
        assertFalse(diff.truncated)
        assertTrue(diff.text.contains("--- a/src/a.kt"))
        assertTrue(diff.text.contains("-b"))
        assertTrue(diff.text.contains("+B"))
    }

    @Test fun addedFileHasNoDeletionsAndTheRightHeader() {
        val diff = WorkerDiff.diff("new.kt", ByteArray(0), "x\ny".toByteArray())
        assertEquals(2, diff.additions)
        assertEquals(0, diff.deletions)
        assertTrue(diff.text.contains("@@ -0,0 +1,2 @@"))
        assertTrue(diff.text.contains("+x"))
    }

    @Test fun identicalBytesProduceAnEmptyDiff() {
        val diff = WorkerDiff.diff("same.kt", "same".toByteArray(), "same".toByteArray())
        assertEquals("", diff.text)
        assertEquals(0, diff.additions)
        assertEquals(0, diff.deletions)
        assertFalse(diff.truncated)
    }

    @Test fun truncationIsBoundedAndKeepsFullCounts() {
        val before = (1..40).joinToString("\n") { "old-$it" }.toByteArray()
        val after = (1..40).joinToString("\n") { "new-$it" }.toByteArray()
        val maxBytes = 120
        val diff = WorkerDiff.diff("big.kt", before, after, maxBytes)
        assertTrue(diff.truncated)
        assertEquals(40, diff.additions)
        assertEquals(40, diff.deletions)
        assertTrue(diff.text.toByteArray(Charsets.UTF_8).size <= maxBytes)
    }
}
