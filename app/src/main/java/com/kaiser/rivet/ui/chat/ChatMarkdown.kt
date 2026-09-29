package com.kaiser.rivet.ui.chat

internal sealed interface ChatMarkdownBlock {
    data class Paragraph(val text: String) : ChatMarkdownBlock
    data class Heading(val level: Int, val text: String) : ChatMarkdownBlock
    data class Quote(val text: String) : ChatMarkdownBlock
    data class ListItems(val ordered: Boolean, val start: Int, val items: List<String>) : ChatMarkdownBlock
    data class Code(val language: String, val text: String) : ChatMarkdownBlock
}

internal data class InlineRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
)

internal fun parseAssistantMarkdown(source: String): List<ChatMarkdownBlock> {
    val lines = source.split('\n')
    val blocks = ArrayList<ChatMarkdownBlock>()
    var line = 0
    while (line < lines.size) {
        if (lines[line].isBlank()) {
            line++
            continue
        }
        val current = lines[line]
        val trimmed = current.trimStart()
        if (trimmed.startsWith("```")) {
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
        val heading = heading(trimmed)
        if (heading != null) {
            blocks += heading
            line++
            continue
        }
        if (trimmed.startsWith(">")) {
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
        val paragraph = ArrayList<String>()
        while (line < lines.size && lines[line].isNotBlank()) {
            val value = lines[line].trimStart()
            if (paragraph.isNotEmpty() && (value.startsWith("```") || heading(value) != null ||
                    value.startsWith(">") || listItem(value) != null)) break
            paragraph += lines[line]
            line++
        }
        if (paragraph.isNotEmpty()) blocks += ChatMarkdownBlock.Paragraph(paragraph.joinToString("\n"))
    }
    return blocks
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
