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
        assertTrue(doc.text.contains("the widget endpoint"))
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

    @Test fun hiddenPanelsAreNotReaderTextByDefault() {
        val html = """<html><body><article>
            <p>Visible game instructions with enough words to be a real article body.</p>
            <div style="display:none"><p>Paused hidden panel</p></div>
            <div style="visibility:hidden"><p>Game over hidden panel</p></div>
        </article></body></html>"""
        val visible = ReaderExtractor.extract(html, "https://game.example")
        assertTrue(visible.text.contains("Visible game instructions"))
        assertFalse(visible.text.contains("Paused hidden panel"))
        assertFalse(visible.text.contains("Game over hidden panel"))
        val all = ReaderExtractor.extract(html, "https://game.example", visibleOnly = false)
        assertTrue(all.text.contains("Paused hidden panel"))
    }

    @Test fun findReturnsBoundedSnippets() {
        val hits = ReaderExtractor.find("one two three one two three", "TWO", limit = 2)
        assertEquals(2, hits.size)
        assertTrue(hits.all { it.snippet.contains("two") })
    }

    @Test fun translucentOpacityElementsAreRetainedWhileZeroOpacityAreRemoved() {
        val html = """<html><body><article>
            <p>Main article body with enough words to score well cleanly in reader mode.</p>
            <div style="opacity: 0.5"><p>Translucent element stays visible</p></div>
            <div style="opacity:0"><p>Fully transparent element removed</p></div>
            <div style="opacity: 0.00;"><p>Decimal zero transparent element removed</p></div>
        </article></body></html>"""
        val doc = ReaderExtractor.extract(html, "https://example.com/article")
        assertTrue(doc.text.contains("Translucent element stays visible"))
        assertFalse(doc.text.contains("Fully transparent element removed"))
        assertFalse(doc.text.contains("Decimal zero transparent element removed"))
    }
}
