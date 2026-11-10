package com.kaiser.rivet.local

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalThinkingFilterTest {
    @Test fun removesARealThinkingBlockBeforeTheAnswer() {
        assertEquals("Hello world.",
            LocalThinkingFilter.strip("Hello  \u003cthink\u003eweighing options\u003c/think\u003eworld."))
    }

    @Test fun removesTheEscapedThinkingForm() {
        assertEquals("The answer is 12.",
            LocalThinkingFilter.strip(
                "&lt;think&gt;I should add five and seven&lt;/think&gt;The answer is 12."))
    }

    @Test fun anUnterminatedBlockNeverLeaks() {
        assertEquals("Visible first.",
            LocalThinkingFilter.strip("Visible first.  \u003cthink\u003e this never closes"))
    }

    @Test fun aTagSplitAcrossStreamingChunksStaysHidden() {
        val filter = LocalThinkingFilter()
        val visible = buildString {
            append(filter.feed("Before <thi"))
            append(filter.feed("nk>secret"))
            append(filter.feed("</thi"))
            append(filter.feed("nk> after"))
            append(filter.finish())
        }
        assertEquals("Before  after", visible)
    }

    @Test fun escapedTagsSplitAcrossChunksAlsoStayHidden() {
        val filter = LocalThinkingFilter()
        val visible = buildString {
            append(filter.feed("&lt;thi"))
            append(filter.feed("nk&gt;secret&lt;/think&gt;"))
            append(filter.feed(" clean"))
            append(filter.finish())
        }
        assertEquals(" clean", visible)
    }
}
