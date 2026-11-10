package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderExtractorTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResource("browser/$name")!!.readText()

    @Test fun extractsArticleTextAndStripsBoilerplate() {
        val doc = ReaderExtractor.extract(fixture("docs.html"), "https://docs.example.com/api")
        assertEquals("Widget API · Docs", doc.title)
        assertTrue(doc.text.contains("Widget.create()"))
        assertTrue(doc.text.contains("Authentication"))
        assertTrue(doc.text.contains("[the widget endpoint]"))
        assertFalse(doc.text.contains("Copyright"))
        assertFalse(doc.text.contains("track()"))
        assertFalse(doc.needsRenderer)
    }

    @Test fun chunksLongTextWithHeadingAnchors() {
        val text = buildString {
            repeat(20) { append("## Section $it\n").append("word ".repeat(1200)).append("\n") }
        }
        val chunks = ReaderExtractor.chunk(text, "Long")
        assertTrue(chunks.size > 1)
        assertEquals(0, chunks.first().index)
        assertEquals(chunks.size - 1, chunks.last().index)
        assertTrue(chunks.last().anchor.startsWith("Section"))
    }

    @Test fun javascriptShellIsReportedAsNeedingTheRenderer() {
        val doc = ReaderExtractor.extract(fixture("shell.html"), "https://app.example.com")
        assertTrue(doc.needsRenderer)
    }

    @Test fun findReturnsBoundedSnippets() {
        val hits = ReaderExtractor.find("one two three one two three", "TWO", limit = 2)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.snippet.contains("two") })
    }
}
