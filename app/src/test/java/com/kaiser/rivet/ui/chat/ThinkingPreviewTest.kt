package com.kaiser.rivet.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// The collapsed thinking row shows one cheap line. While the chain streams, that line is
// the first line of the last paragraph that finished, so it does not churn per token.
class ThinkingPreviewTest {
    @Test fun aFinishedTurnPreviewsItsFirstLine() {
        assertEquals("Start with the parser",
            thinkingPreview("Start with the parser\nThen the renderer", streaming = false))
        assertEquals("", thinkingPreview("\n\n   ", streaming = false))
    }

    @Test fun aStreamingChainOnlyAdvancesWhenAParagraphLineFinishes() {
        // The second paragraph has not finished a line, so the preview stays on the first.
        assertEquals("First thought",
            thinkingPreview("First thought\nstill first\n\nSecond thought is still goi", streaming = true))
        // Both paragraphs finished a line, so the newest one wins.
        assertEquals("Second",
            thinkingPreview("First\n\nSecond\n\nThird has no line end yet", streaming = true))
    }

    // A blank header while the first paragraph streams looks like a stall, so the row
    // shows the end of the line being written instead.
    @Test fun anUnfinishedLinePreviewsItsOwnTail() {
        assertEquals("One long paragraph with no line end",
            thinkingPreview("One long paragraph with no line end", streaming = true))
        assertEquals("older line", thinkingPreview("older line\nfresh thought", streaming = true))
        assertEquals("", thinkingPreview("", streaming = true))
        assertEquals("", thinkingPreview("   \n\t", streaming = true))
    }

    @Test fun theTailPreviewStaysOneShortLine() {
        val long = "y".repeat(500)
        assertEquals(160, thinkingPreview(long, streaming = true).length)
    }

    // Pinned before the cost of computing this is changed: once a paragraph has finished a
    // line, the preview stays on that paragraph even after kilobytes of new unfinished text.
    // This is why a shortcut that only looks at the end of the text cannot be used — it would
    // move the preview to the open line.
    @Test fun aFinishedParagraphKeepsThePreviewWhileALongOpenOneStreams() {
        val finished = "Check the parser first\nsecond line\n\n"
        val open = "Now a much longer thought that never ends " + "word ".repeat(80)
        assertEquals("Check the parser first", thinkingPreview(finished + open, streaming = true))
        assertEquals("Check the parser first", thinkingPreview(finished.trimEnd(), streaming = true))
        assertTrue("the open tail is never what a finished paragraph shows",
            thinkingPreview(finished + open, streaming = true) != "word word word")
    }

    // Paragraphs are separated by blank lines: the preview follows the last finished one.
    @Test fun thePreviewFollowsTheLastFinishedParagraph() {
        assertEquals("Second", thinkingPreview("First\none\n\nSecond\ntwo\n\nThird", streaming = true))
        assertEquals("First", thinkingPreview("First\none\n\nSecond", streaming = true))
    }

    @Test fun thePreviewStaysOneShortLine() {
        val long = "x".repeat(400) + "\n\n" + "y".repeat(400)
        assertEquals("x".repeat(160), thinkingPreview(long, streaming = true))
    }
}
