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
}
