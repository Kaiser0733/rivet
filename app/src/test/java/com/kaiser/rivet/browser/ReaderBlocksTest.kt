package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderBlocksTest {
    @Test fun whitespaceBeforeAnMdnHeadingDoesNotTurnItIntoProse() {
        val block = ReaderBlocks.parse("    ## [Try it](https://example.org/#try_it)").single()

        assertEquals(ReaderBlockKind.Heading, block.kind)
        assertEquals(2, block.level)
        assertEquals("Try it", block.spans.single().text)
        assertEquals("https://example.org/#try_it", block.spans.single().url)
    }

    @Test fun bracketedCitationLabelsStayTappable() {
        val span = ReaderBlocks.parse("[[1]](https://example.org/#cite_note-1)").single().spans.single()

        assertEquals("[1]", span.text)
        assertEquals("https://example.org/#cite_note-1", span.url)
    }

    @Test fun headingsAndLinksBecomeStyledSpansWithoutSourceSyntax() {
        val blocks = ReaderBlocks.parse("## Authentication\n\nUse [the endpoint](https://example.com/v1) now.")
        assertEquals(ReaderBlockKind.Heading, blocks[0].kind)
        assertEquals(2, blocks[0].level)
        assertEquals("Authentication", blocks[0].spans.single().text)
        val paragraph = blocks[1]
        assertTrue(paragraph.spans.any { it.url == "https://example.com/v1" })
        val rendered = blocks.flatMap { it.spans }.joinToString(" ") { it.text }
        assertFalse(rendered.contains("##"))
        assertFalse(rendered.contains("]("))
    }

    @Test fun bulletsAndCodeDoNotLeakMarkdownFences() {
        val blocks = ReaderBlocks.parse("- one\n- two\n\n```kotlin\nval x = 1\n```")
        assertEquals(2, blocks.count { it.kind == ReaderBlockKind.Bullet })
        val code = blocks.single { it.kind == ReaderBlockKind.Code }
        assertEquals("val x = 1", code.spans.single().text)
        assertFalse(blocks.flatMap { it.spans }.any { it.text.startsWith("```") })
    }

    @Test fun ordinaryProseIsOneCleanParagraph() {
        val blocks = ReaderBlocks.parse("A cache is a promise.\n\nThe future keeps changing.")
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it.kind == ReaderBlockKind.Paragraph })
        assertEquals("A cache is a promise.", blocks[0].spans.single().text)
    }

    @Test fun linkLabelsAndUrlsStayTappable() {
        val blocks = ReaderBlocks.parse("[Rivet docs](https://example.com/docs)")
        val span = blocks.single().spans.single()
        assertEquals("Rivet docs", span.text)
        assertEquals("https://example.com/docs", span.url)
    }
}
