package com.kaiser.rivet.browser

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

data class ReaderChunk(
    val index: Int,
    val total: Int,
    val anchor: String,
    val text: String,
)

data class ReaderHit(val snippet: String, val offset: Int)

data class ReaderDocument(
    val url: String,
    val title: String,
    val text: String,
    val chunks: List<ReaderChunk>,
    val needsRenderer: Boolean,
)

/** Readability-style extraction with a small, deterministic scoring rule. */
object ReaderExtractor {
    const val CHUNK_CHARS = 12_000
    private const val MIN_TEXT = 240
    private val BLOCK_TAGS = setOf("p", "div", "section", "article", "main", "ul", "ol", "table",
        "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "br", "li")

    fun extract(html: String, url: String): ReaderDocument {
        val doc = Jsoup.parse(html, url)
        doc.select("script,style,noscript,template,svg,nav,header,footer,aside,form,iframe").remove()
        doc.select("[aria-hidden=true]").remove()
        val title = doc.title().trim().ifEmpty {
            doc.selectFirst("h1")?.text()?.trim().orEmpty()
        }
        val candidates = doc.select("article,main,[role=main],#content,.content,.post,.article,body")
        val winner = candidates.maxByOrNull(::score)
        val text = winner?.let(::renderMarkdown)?.trim().orEmpty().ifBlank {
            doc.body()?.text()?.trim().orEmpty()
        }
        val needsRenderer = needsRenderer(html, text, doc)
        val chunks = chunk(text, title)
        return ReaderDocument(url, title.ifBlank { url }, text, chunks, needsRenderer)
    }

    fun find(text: String, query: String, limit: Int = 10): List<ReaderHit> {
        if (query.isBlank()) return emptyList()
        val needle = query.lowercase()
        val lower = text.lowercase()
        val hits = mutableListOf<ReaderHit>()
        var from = 0
        while (hits.size < limit) {
            val at = lower.indexOf(needle, from)
            if (at < 0) break
            val start = (at - 80).coerceAtLeast(0)
            val end = (at + query.length + 120).coerceAtMost(text.length)
            hits += ReaderHit(text.substring(start, end).replace(Regex("\\s+"), " ").trim(), at)
            from = at + needle.length.coerceAtLeast(1)
        }
        return hits
    }

    fun chunk(text: String, title: String = "", size: Int = CHUNK_CHARS): List<ReaderChunk> {
        if (text.isBlank()) return emptyList()
        val total = ((text.length + size - 1) / size).coerceAtLeast(1)
        return (0 until total).map { index ->
            val start = index * size
            val end = (start + size).coerceAtMost(text.length)
            val before = text.take(start)
            val anchor = Regex("(?m)^(#{1,6})\\s+(.+)$").findAll(before).lastOrNull()
                ?.groupValues?.get(2)?.trim().orEmpty().ifBlank { title }
            ReaderChunk(index, total, anchor, text.substring(start, end))
        }
    }

    private fun score(element: Element): Int {
        val text = element.text().length
        val link = element.select("a").sumOf { it.text().length }
        val paragraphs = element.select("p").count()
        return text - link * 2 + paragraphs * 40
    }

    private fun renderMarkdown(element: Element): String {
        val out = StringBuilder()
        fun block(text: String, prefix: String = "", suffix: String = "\n\n") {
            val value = text.replace(Regex("[ \\t]+"), " ").trim()
            if (value.isNotEmpty()) out.append(prefix).append(value).append(suffix)
        }
        fun visit(node: Node) {
            when (node) {
                is TextNode -> out.append(node.text())
                is Element -> {
                    when (node.tagName().lowercase()) {
                        "h1" -> block(node.text(), "# ")
                        "h2" -> block(node.text(), "## ")
                        "h3" -> block(node.text(), "### ")
                        "h4" -> block(node.text(), "#### ")
                        "h5" -> block(node.text(), "##### ")
                        "h6" -> block(node.text(), "###### ")
                        "li" -> block(node.text(), "- ")
                        "pre" -> block(node.text(), "```\n", "\n```\n\n")
                        "p", "blockquote" -> block(node.text())
                        "br" -> out.append('\n')
                        "a" -> {
                            val href = node.absUrl("href").ifBlank { node.attr("href") }
                            val label = node.text().trim()
                            if (label.isNotEmpty()) {
                                if (href.isNotBlank() && !href.startsWith("javascript:")) {
                                    out.append('[').append(label).append("](").append(href).append(')')
                                } else out.append(label)
                            }
                        }
                        else -> {
                            if (node.tagName().lowercase() in BLOCK_TAGS) {
                                for (child in node.childNodes()) visit(child)
                                out.append('\n')
                            } else for (child in node.childNodes()) visit(child)
                        }
                    }
                }
            }
        }
        visit(element)
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun needsRenderer(html: String, text: String, doc: org.jsoup.nodes.Document): Boolean {
        if (text.length < MIN_TEXT) return true
        if (html.length > 4_000 && text.length < html.length / 25) return true
        val roots = doc.select("#root,#app,[data-reactroot],[ng-app]")
        return roots.any { it.children().isEmpty() } && doc.select("script").isNotEmpty()
    }
}
