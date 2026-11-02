package com.kaiser.rivet.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {
    @Test fun parsesParagraphsHeadingsAndQuotes() {
        assertEquals(
            listOf(
                ChatMarkdownBlock.Heading(2, "A heading"),
                ChatMarkdownBlock.Paragraph("First paragraph.\nStill the same paragraph."),
                ChatMarkdownBlock.Quote("A quoted line"),
            ),
            parseAssistantMarkdown("## A heading\n\nFirst paragraph.\nStill the same paragraph.\n\n> A quoted line"),
        )
    }

    @Test fun parsesBothListForms() {
        assertEquals(
            listOf(
                ChatMarkdownBlock.ListItems(ordered = false, start = 1, items = listOf("one", "two")),
                ChatMarkdownBlock.ListItems(ordered = true, start = 3, items = listOf("three", "four")),
            ),
            parseAssistantMarkdown("- one\n- two\n\n3. three\n4. four"),
        )
    }

    @Test fun inlineEmphasisAndCodeBecomeStyledRuns() {
        val runs = parseInlineMarkdown("a **bold** and *italic* with `code`")

        assertEquals("a bold and italic with code", runs.joinToString("") { it.text })
        assertTrue(runs.any { it.text == "bold" && it.bold })
        assertTrue(runs.any { it.text == "italic" && it.italic })
        assertTrue(runs.any { it.text == "code" && it.code })
    }

    @Test fun fencedCodePreservesWhitespaceAndUnclosedFenceIsSafe() {
        assertEquals(
            listOf(ChatMarkdownBlock.Code("kotlin", "val answer = 42\nprintln(answer)")),
            parseAssistantMarkdown("```kotlin\nval answer = 42\nprintln(answer)\n```"),
        )
        assertEquals(
            listOf(ChatMarkdownBlock.Code("", "first\nsecond")),
            parseAssistantMarkdown("```\nfirst\nsecond"),
        )
    }

    @Test fun incompleteInlineMarkersStayLiteralAndHtmlRemainsText() {
        val source = "unfinished **bold and <script>alert(1)</script>"
        val runs = parseInlineMarkdown(source)

        assertEquals(source, runs.joinToString("") { it.text })
        assertFalse(runs.any { it.code || it.bold || it.italic })
        assertEquals(listOf(ChatMarkdownBlock.Paragraph("<script>alert(1)</script>")),
            parseAssistantMarkdown("<script>alert(1)</script>"))
        assertEquals("unfinished `code", parseInlineMarkdown("unfinished `code").joinToString("") { it.text })
        assertEquals(listOf(ChatMarkdownBlock.Paragraph("#")), parseAssistantMarkdown("#"))
    }

    @Test fun plainTextIsUnchanged() {
        val source = "ordinary response with no formatting"
        assertEquals(listOf(ChatMarkdownBlock.Paragraph(source)), parseAssistantMarkdown(source))
        assertEquals(source, parseInlineMarkdown(source).joinToString("") { it.text })
    }

    @Test fun everyStreamingPrefixParsesWithoutCrashingOnOpenMarkdown() {
        val streaming = "## **Update**\n\n- `first`\n1. *second*\n\n```kotlin\nprintln(\"done\")"
        for (end in 0..streaming.length) {
            parseAssistantMarkdown(streaming.take(end)).forEach { block ->
                when (block) {
                    is ChatMarkdownBlock.Paragraph -> parseInlineMarkdown(block.text)
                    is ChatMarkdownBlock.Heading -> parseInlineMarkdown(block.text)
                    is ChatMarkdownBlock.Quote -> parseInlineMarkdown(block.text)
                    is ChatMarkdownBlock.ListItems -> block.items.forEach(::parseInlineMarkdown)
                    is ChatMarkdownBlock.Code -> Unit
                }
            }
        }
    }

    // A reply streams one delta at a time, and the render path keeps the blocks before the last
    // one and re-parses only that block. This is the pin that the shortcut is invisible: every
    // prefix a stream can pass through, and every prefix followed by more text, must equal a full
    // parse. The documents cover each block kind, blank runs, an open fence, and CRLF.
    @Test fun theIncrementalParserEqualsAFullParseForEveryStreamingPrefix() {
        val documents = listOf(
            "## A heading\n\nFirst paragraph.\nStill the same paragraph.\n\n> A quoted line",
            "- one\n- two\n\n3. three\n4. four",
            "```kotlin\nval answer = 42\nprintln(answer)\n```",
            "```\nfirst\nsecond",
            "text with **bold**, *italic* and `code`\n\n- item **b**\n\n## H3 ###\n> quote\n> more\n\nfinal",
            "",
            "a",
            "\n\n\n",
            "para\r\nwith crlf\r\n\r\n- x\r\n",
            "```\nopen fence\n\n# not a heading inside\n```\nafter\n\n## heading\n",
        )
        for (document in documents) {
            val parser = MarkdownBlocks()
            for (end in 0..document.length) {
                val prefix = document.take(end)
                assertEquals("prefix $end of ${document.take(24).replace("\n", "\\n")}",
                    parseAssistantMarkdown(prefix), parser.forText(prefix))
            }
            // The same prefixes, each followed by more text: the shape growth actually takes.
            for (end in 0..document.length) {
                val parser2 = MarkdownBlocks()
                val prefix = document.take(end)
                assertEquals(parseAssistantMarkdown(prefix), parser2.forText(prefix))
                val grown = prefix + "\nmore text\n\n- extra\n"
                assertEquals("grown at $end", parseAssistantMarkdown(grown), parser2.forText(grown))
            }
        }
    }

    @Test fun aReplacedTextIsParsedInFullRatherThanExtended() {
        val parser = MarkdownBlocks()
        assertEquals(parseAssistantMarkdown("- one\n- two"), parser.forText("- one\n- two"))
        // A retry or an edit replaces the text instead of growing it.
        val replacement = "## something else\n\n```\ncode\n```"
        assertEquals(parseAssistantMarkdown(replacement), parser.forText(replacement))
        // And a shorter text goes back through the full parse too.
        assertEquals(parseAssistantMarkdown("## something"), parser.forText("## something"))
    }

    @Test fun anUnchangedTextIsReturnedAsItWas() {
        val parser = MarkdownBlocks()
        val text = "a\n\nb\n\n- c"
        val first = parser.forText(text)
        val again = parser.forText(text)
        assertEquals(parseAssistantMarkdown(text), first)
        assertTrue("the same text must not be parsed twice", first === again)
    }
}
