package com.kaiser.rivet.ui.chat

internal sealed interface ChatMarkdownBlock {
    data class Paragraph(val text: String) : ChatMarkdownBlock
    data class Heading(val level: Int, val text: String) : ChatMarkdownBlock
    data class Quote(val text: String) : ChatMarkdownBlock
    data class ListItems(val ordered: Boolean, val start: Int, val items: List<String>) : ChatMarkdownBlock
    data class Code(val language: String, val text: String) : ChatMarkdownBlock
    data class Image(val alt: String, val source: String) : ChatMarkdownBlock
}

internal data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
)

internal fun parseAssistantMarkdown(source: String): List<ChatMarkdownBlock> = parseBlocks(source).first

// The block parse also reports where its last block starts, which is what lets a streaming
// answer re-parse only that block. See MarkdownBlocks below.
private fun parseBlocks(source: String): Pair<List<ChatMarkdownBlock>, IntArray> {
    val lines = source.split('\n')
    val lineStarts = IntArray(lines.size)
    var offset = 0
    for (index in lines.indices) {
        lineStarts[index] = offset
        offset += lines[index].length + 1
    }
    val blocks = ArrayList<ChatMarkdownBlock>()
    val blockStarts = ArrayList<Int>()
    var line = 0
    while (line < lines.size) {
        if (lines[line].isBlank()) {
            line++
            continue
        }
        val current = lines[line]
        val trimmed = current.trimStart()
        if (trimmed.startsWith("```")) {
            blockStarts += lineStarts[line]
            val language = trimmed.drop(3).trim()
            line++
            val code = ArrayList<String>()
            while (line < lines.size && !lines[line].trimStart().startsWith("```")) {
                code += lines[line]
                line++
            }
            if (line < lines.size) line++
            blocks += ChatMarkdownBlock.Code(language, code.joinToString("\n"))
            continue
        }
        val image = image(trimmed)
        if (image != null) {
            blockStarts += lineStarts[line]
            blocks += image
            line++
            continue
        }
        val heading = heading(trimmed)
        if (heading != null) {
            blockStarts += lineStarts[line]
            blocks += heading
            line++
            continue
        }
        if (trimmed.startsWith(">")) {
            blockStarts += lineStarts[line]
            val quote = ArrayList<String>()
            while (line < lines.size) {
                val value = lines[line].trimStart()
                if (!value.startsWith(">")) break
                quote += value.drop(1).removePrefix(" ")
                line++
            }
            blocks += ChatMarkdownBlock.Quote(quote.joinToString("\n"))
            continue
        }
        val firstItem = listItem(trimmed)
        if (firstItem != null) {
            blockStarts += lineStarts[line]
            val items = ArrayList<String>()
            val ordered = firstItem.ordered
            val start = firstItem.number
            while (line < lines.size) {
                val item = listItem(lines[line].trimStart()) ?: break
                if (item.ordered != ordered) break
                items += item.text
                line++
            }
            blocks += ChatMarkdownBlock.ListItems(ordered, start, items)
            continue
        }
        blockStarts += lineStarts[line]
        val paragraph = ArrayList<String>()
        while (line < lines.size && lines[line].isNotBlank()) {
            val value = lines[line].trimStart()
            if (paragraph.isNotEmpty() && (value.startsWith("```") || heading(value) != null ||
                    value.startsWith(">") || listItem(value) != null || image(value) != null)) break
            paragraph += lines[line]
            line++
        }
        // The loop only runs while the line is non-blank, so there is always one to add.
        blocks += ChatMarkdownBlock.Paragraph(paragraph.joinToString("\n"))
    }
    return blocks to blockStarts.toIntArray()
}

/**
 * Block parsing for one reply that grows while it streams.
 *
 * A streaming answer republishes its whole text every few milliseconds, and a full parse splits
 * every line and re-joins every block each time, so a long reply costs its own length per
 * publication. The parser is line-based, so blocks further back are a function of the lines before
 * them and appending text cannot move them: only the last two blocks need rebuilding.
 *
 * Two, not one, because the last line of the text may not have settled: "- a\n-" is a list and a
 * paragraph, and one more character ("- ") makes it a single list of two items, so the last block
 * can be absorbed by the block before it. Everything before that pair is fixed.
 *
 * The result is exactly the full parse's value — the same blocks, in the same order, with the same
 * text; `ChatMarkdownTest` checks that at every prefix of a set of documents and
 * `round3/markdown-incremental-check.py` exhaustively. Anything else (a shorter text, or a
 * different one, as a retry or an edit produces) falls back to a full parse.
 */
internal class MarkdownBlocks {
    private var source: String = ""
    private var blocks: List<ChatMarkdownBlock> = emptyList()
    private var starts: IntArray = IntArray(0)

    fun forText(text: String): List<ChatMarkdownBlock> {
        if (text == source) return blocks
        if (blocks.isNotEmpty() && text.length > source.length && text.startsWith(source)) {
            val keep = (blocks.size - 2).coerceAtLeast(0)
            val from = starts[keep]
            val (tail, tailStarts) = parseBlocks(text.substring(from))
            if (tail.isNotEmpty()) {
                val next = ArrayList<ChatMarkdownBlock>(keep + tail.size)
                next.addAll(blocks.subList(0, keep))
                next.addAll(tail)
                val nextStarts = IntArray(keep + tailStarts.size)
                for (index in 0 until keep) nextStarts[index] = starts[index]
                for (index in tailStarts.indices) nextStarts[keep + index] = from + tailStarts[index]
                source = text
                blocks = next
                starts = nextStarts
                return next
            }
        }
        val (full, fullStarts) = parseBlocks(text)
        source = text
        blocks = full
        starts = fullStarts
        return full
    }
}

/** A standalone screenshot line is an attachment, not raw markdown in a paragraph. */
private fun image(line: String): ChatMarkdownBlock.Image? {
    if (!line.startsWith("![")) return null
    val close = line.indexOf("](")
    if (close <= 1 || !line.endsWith(")")) return null
    val source = line.substring(close + 2, line.length - 1).trim()
    if (source.isBlank() || source.any { it.isWhitespace() }) return null
    return ChatMarkdownBlock.Image(line.substring(2, close), source)
}

private fun heading(line: String): ChatMarkdownBlock.Heading? {
    val level = line.takeWhile { it == '#' }.length
    return if (level in 1..3 && line.length > level && line[level].isWhitespace()) {
        ChatMarkdownBlock.Heading(level, line.drop(level).trimStart())
    } else null
}

private data class ListMarker(val ordered: Boolean, val number: Int, val text: String)

private fun listItem(line: String): ListMarker? {
    if (line.length >= 2 && line[0] in "-*+" && line[1].isWhitespace()) {
        return ListMarker(false, 1, line.drop(2))
    }
    var end = 0
    while (end < line.length && line[end].isDigit()) end++
    if (end == 0 || end > 9 || end + 1 >= line.length ||
        line[end] !in ".)" || !line[end + 1].isWhitespace()) return null
    val number = line.substring(0, end).toIntOrNull() ?: return null
    return ListMarker(true, number, line.drop(end + 2))
}

internal fun parseInlineMarkdown(source: String): List<InlineRun> {
    val codeSpans = pairSpans(source, "`", InlineStyle.Code)
    val boldSpans = pairSpans(source, "**", InlineStyle.Bold, codeSpans)
    val italicSpans = pairSpans(source, "*", InlineStyle.Italic, codeSpans + boldSpans,
        skipDoubleAsterisk = true)
    val spans = codeSpans + boldSpans + italicSpans
    if (spans.isEmpty()) return listOf(InlineRun(source))

    val events = HashMap<Int, MutableList<Pair<InlineStyle, Int>>>()
    val skipped = HashMap<Int, Int>()
    spans.forEach { span ->
        events.getOrPut(span.contentStart) { ArrayList() }.add(span.style to 1)
        events.getOrPut(span.closeStart) { ArrayList() }.add(span.style to -1)
        skipped[span.openStart] = span.delimiter.length
        skipped[span.closeStart] = span.delimiter.length
    }

    val output = ArrayList<InlineRun>()
    val text = StringBuilder()
    var bold = 0
    var italic = 0
    var code = 0
    var style = InlineStyleState(false, false, false)
    fun flush() {
        if (text.isNotEmpty()) {
            output += InlineRun(text.toString(), style.bold, style.italic, style.code)
            text.setLength(0)
        }
    }
    var index = 0
    while (index < source.length) {
        events[index]?.forEach { (kind, delta) ->
            when (kind) {
                InlineStyle.Bold -> bold += delta
                InlineStyle.Italic -> italic += delta
                InlineStyle.Code -> code += delta
            }
        }
        val next = InlineStyleState(bold > 0, italic > 0, code > 0)
        if (next != style) {
            flush()
            style = next
        }
        val skip = skipped[index]
        if (skip != null) {
            index += skip
        } else {
            text.append(source[index])
            index++
        }
    }
    flush()
    return output
}

private enum class InlineStyle { Bold, Italic, Code }
private data class InlineStyleState(val bold: Boolean, val italic: Boolean, val code: Boolean)
private data class DelimitedSpan(
    val delimiter: String,
    val style: InlineStyle,
    val openStart: Int,
    val contentStart: Int,
    val closeStart: Int,
)

private fun pairSpans(
    source: String,
    delimiter: String,
    style: InlineStyle,
    excluded: List<DelimitedSpan> = emptyList(),
    skipDoubleAsterisk: Boolean = false,
): List<DelimitedSpan> {
    val result = ArrayList<DelimitedSpan>()
    val excludedRanges = excluded.sortedBy { it.openStart }
    var excludedIndex = 0
    var open = -1
    var index = 0
    while (index <= source.length - delimiter.length) {
        while (excludedIndex < excludedRanges.size &&
            index >= excludedRanges[excludedIndex].closeStart + excludedRanges[excludedIndex].delimiter.length) {
            excludedIndex++
        }
        val blocked = excludedRanges.getOrNull(excludedIndex)
        if (blocked != null && index >= blocked.openStart) {
            index = blocked.closeStart + blocked.delimiter.length
            continue
        }
        if (skipDoubleAsterisk && source.startsWith("**", index)) {
            index += 2
            continue
        }
        if (!source.startsWith(delimiter, index)) {
            index++
            continue
        }
        if (open < 0) open = index
        else if (index > open + delimiter.length) {
            result += DelimitedSpan(delimiter, style, open, open + delimiter.length, index)
            open = -1
        }
        index += delimiter.length
    }
    return result
}
