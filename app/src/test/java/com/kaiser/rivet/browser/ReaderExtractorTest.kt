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

    @Test fun headingAndParagraphKeepTheirInlineLinks() {
        val html = """<body><main><h1>Example <a href="/help">Domain</a></h1>
            <p>For details, <a href="/more">learn more</a>.</p></main></body>"""
        val doc = ReaderExtractor.extract(html, "https://example.com")
        val blocks = ReaderBlocks.parse(doc.text)
        assertEquals(ReaderBlockKind.Heading, blocks[0].kind)
        assertEquals("Example Domain", blocks[0].spans.joinToString("") { it.text })
        assertEquals("https://example.com/help", blocks[0].spans.single { it.text == "Domain" }.url)
        assertEquals("https://example.com/more", blocks[1].spans.single { it.text == "learn more" }.url)
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

    @Test fun stripsNonHttpAndCaseInsensitiveScriptLinks() {
        val html = """<html><body><article>
            <p>Article body paragraph with enough text content for readability extraction to keep it.</p>
            <div>
              <a href="JAVASCRIPT:alert(1)">ClickJs</a>
              <a href="data:text/html,hi">ClickData</a>
              <a href="vbscript:msgbox(1)">ClickVbs</a>
              <a href="HTTPS://example.com/safe">SafeHttps</a>
              <a href="http://example.com/plain">SafeHttp</a>
            </div>
        </article></body></html>"""
        val docNoBase = ReaderExtractor.extract(html, "")
        assertFalse(docNoBase.text.contains("](JAVASCRIPT:", ignoreCase = true))
        assertFalse(docNoBase.text.contains("](data:", ignoreCase = true))
        assertFalse(docNoBase.text.contains("](vbscript:", ignoreCase = true))
        assertTrue(docNoBase.text.contains("ClickJs"))
        assertTrue(docNoBase.text.contains("ClickData"))
        assertTrue(docNoBase.text.contains("ClickVbs"))
        assertTrue(docNoBase.text.contains("[SafeHttps](https://example.com/safe)", ignoreCase = true))
        assertTrue(docNoBase.text.contains("[SafeHttp](http://example.com/plain)"))
    }

    @Test fun computedHiddenTagsAndCollapsedVisibilityAreRemovedOnlyForVisibleReads() {
        val html = """<html><body><article>
            <p>Main article body with enough words to score well in reader mode.</p>
            <div data-rivet-hidden="1"><p>Computed hidden panel</p></div>
            <div style="visibility:collapse"><p>Collapsed hidden panel</p></div>
            <div class="hidden"><p>Class hidden panel needs a computed-style pass</p></div>
        </article></body></html>"""
        val visible = ReaderExtractor.extract(html, "https://game.example")
        assertTrue(visible.text.contains("Main article body"))
        assertFalse(visible.text.contains("Computed hidden panel"))
        assertFalse(visible.text.contains("Collapsed hidden panel"))
        val all = ReaderExtractor.extract(html, "https://game.example", visibleOnly = false)
        assertTrue(all.text.contains("Computed hidden panel"))
        assertTrue(all.text.contains("Collapsed hidden panel"))
    }
}
