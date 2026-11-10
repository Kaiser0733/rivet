package com.kaiser.rivet.browser

enum class ReaderBlockKind { Heading, Paragraph, Bullet, Code }

data class ReaderSpan(val text: String, val url: String? = null)

data class ReaderBlock(
    val kind: ReaderBlockKind,
    val level: Int = 0,
    val spans: List<ReaderSpan> = emptyList(),
)

/**
 * Turns the extractor's light markdown into display blocks. The UI never shows the
 * source syntax: heading hashes, list bullets, fences, and [label](url) become spans.
 */
object ReaderBlocks {
    private val heading = Regex("^(#{1,6})\\s+(.+)$")
    private val bullet = Regex("^[-*]\\s+(.+)$")
    private val link = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")

    fun parse(markdown: String): List<ReaderBlock> {
        val blocks = mutableListOf<ReaderBlock>()
        val paragraph = StringBuilder()
        val code = StringBuilder()
        var inCode = false

        fun flushParagraph() {
            val text = paragraph.toString().trim()
            if (text.isNotEmpty()) blocks += ReaderBlock(ReaderBlockKind.Paragraph, spans = inline(text))
            paragraph.clear()
        }

        fun flushCode() {
            val text = code.toString().trimEnd()
            if (text.isNotEmpty()) blocks += ReaderBlock(ReaderBlockKind.Code,
                spans = listOf(ReaderSpan(text)))
            code.clear()
        }

        markdown.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCode) { flushCode(); inCode = false } else { flushParagraph(); inCode = true }
                return@forEach
            }
            if (inCode) { code.appendLine(line); return@forEach }
            when {
                line.isBlank() -> flushParagraph()
                heading.matches(line) -> {
                    flushParagraph()
                    val match = heading.matchEntire(line)!!
                    val level = match.groupValues[1].length.coerceIn(1, 6)
                    blocks += ReaderBlock(ReaderBlockKind.Heading, level,
                        inline(match.groupValues[2].trim()))
                }
                bullet.matches(line) -> {
                    flushParagraph()
                    blocks += ReaderBlock(ReaderBlockKind.Bullet, spans = inline(
                        bullet.matchEntire(line)!!.groupValues[1].trim()))
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(line.trim())
                }
            }
        }
        flushParagraph()
        if (inCode) flushCode()
        return blocks
    }

    private fun inline(raw: String): List<ReaderSpan> {
        val spans = mutableListOf<ReaderSpan>()
        var cursor = 0
        link.findAll(raw).forEach { match ->
            if (match.range.first > cursor) {
                spans += ReaderSpan(clean(raw.substring(cursor, match.range.first)))
            }
            spans += ReaderSpan(clean(match.groupValues[1]), match.groupValues[2].trim())
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) spans += ReaderSpan(clean(raw.substring(cursor)))
        return spans.filter { it.text.isNotEmpty() || it.url != null }
    }

    private fun clean(text: String): String = text
        .replace("**", "")
        .replace("__", "")
        .replace("`", "")
}
