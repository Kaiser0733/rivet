package com.kaiser.rivet.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerResultParserTest {
    @Test fun splitsTheThreeClaimSections() {
        val claim = WorkerResultParser.parse(
            """
            ## Summary
            Fixed the parser.
            ## Findings
            - Added a guard.
            - Tests pass in the observed run.
            ## Open questions
            - Should the warning stay?
            """.trimIndent()
        )
        assertEquals("Fixed the parser.", claim.summary)
        assertEquals(listOf("Added a guard.", "Tests pass in the observed run."), claim.findings)
        assertEquals(listOf("Should the warning stay?"), claim.openQuestions)
        assertFalse(claim.truncated)
    }

    @Test fun textWithoutSectionsBecomesASingleBoundedSummary() {
        val short = WorkerResultParser.parse("  Just one paragraph.  ")
        assertEquals("Just one paragraph.", short.summary)
        assertTrue(short.findings.isEmpty())
        assertFalse(short.truncated)

        val long = "x".repeat(WorkerResultParser.MAX_UNSECTIONED_CHARS + 1)
        val truncated = WorkerResultParser.parse(long)
        assertEquals(WorkerResultParser.MAX_UNSECTIONED_CHARS, truncated.summary.length)
        assertTrue(truncated.truncated)
    }

    @Test fun headingsCanBeBoldAndContentCanStartOnTheHeadingLine() {
        val claim = WorkerResultParser.parse(
            "**Summary:** Did the job.\n**Findings**\n- one"
        )
        assertEquals("Did the job.", claim.summary)
        assertEquals(listOf("one"), claim.findings)
    }

    @Test fun emptyFinalTextIsAnEmptyClaim() {
        val claim = WorkerResultParser.parse("   \n ")
        assertEquals("", claim.summary)
        assertTrue(claim.findings.isEmpty())
        assertTrue(claim.openQuestions.isEmpty())
    }

    @Test fun sectionOverflowMarksTheClaimTruncated() {
        val claim = WorkerResultParser.parse(
            "Summary\n" + "s".repeat(WorkerResultParser.MAX_SECTION_CHARS + 1)
        )
        assertEquals(WorkerResultParser.MAX_SECTION_CHARS, claim.summary.length)
        assertTrue(claim.truncated)
    }
}
