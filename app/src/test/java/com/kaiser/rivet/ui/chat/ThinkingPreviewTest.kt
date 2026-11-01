package com.kaiser.rivet.ui.chat

import org.junit.Assert.assertEquals
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

    @Test fun aChainWithoutFinishedLinesHasNoPreview() {
        assertEquals("", thinkingPreview("One long paragraph with no line end", streaming = true))
        assertEquals("", thinkingPreview("", streaming = true))
    }

    @Test fun thePreviewStaysOneShortLine() {
        val long = "x".repeat(400) + "\n\n" + "y".repeat(400)
        assertEquals("x".repeat(160), thinkingPreview(long, streaming = true))
    }
}
