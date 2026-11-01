package com.kaiser.rivet.ui.chat

import java.util.Locale

internal const val COMMAND_APPROVAL_WARNING =
    "Project commands can read, change, or delete project files and Rivet's saved data. They can also use the network."

internal fun displaySafeText(value: String): String = buildString(value.length) {
    value.forEach { char ->
        if (char.code in BIDI_FORMATTING_CONTROLS) append("\\u%04X".format(Locale.ROOT, char.code))
        else append(char)
    }
}

// Vendor-qualified ids ("anthropic/claude-sonnet-4-5") are unreadable in a
// one-line selector. Claude ids become words, GPT ids keep their shape, and
// everything else is left exactly as the provider spelled it.
internal fun displayModelName(modelId: String): String {
    val tail = modelId.substringAfterLast('/')
    val lower = tail.lowercase(Locale.ROOT)
    if (lower.startsWith("claude-")) {
        return tail.drop("claude-".length)
            .split(' ', '-', '_')
            .filter { word -> word.isNotEmpty() }
            .joinToString(" ") { word ->
                if (word.all { it.isDigit() }) word
                else word.replaceFirstChar { it.uppercase(Locale.ROOT) }
            }
    }
    if (lower.startsWith("gpt-")) return "GPT-" + tail.drop("gpt-".length)
    return tail
}

// One cheap line for a collapsed thinking row, modelled on dsh: a finished chain shows
// its first line, a streaming one shows the first line of the last paragraph that has
// finished a line, so the preview does not change on every token.
internal fun thinkingPreview(text: String, streaming: Boolean): String =
    if (!streaming) firstLine(text) else lastFinishedParagraphFirstLine(text).ifEmpty { tailOfLastLine(text) }

private const val PREVIEW_CHARS = 160

private val BLANK_LINE = Regex("\r?\n(?:[\t ]*\r?\n)+")

private fun firstLine(text: String): String =
    text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty().take(PREVIEW_CHARS)

// Before the first line ends there is no finished paragraph to preview, and a blank
// header makes the thinking state look stuck. Show the end of the line being written.
private fun tailOfLastLine(text: String): String {
    val end = text.indexOfLast { !it.isWhitespace() }
    if (end < 0) return ""
    val start = text.lastIndexOf('\n', end - 1).let { if (it < 0) 0 else it + 1 }
    return text.substring(start, end + 1).trim().takeLast(PREVIEW_CHARS).trimStart()
}

private fun lastFinishedParagraphFirstLine(text: String): String {
    var summary = ""
    var paragraphStart = 0
    while (true) {
        val next = BLANK_LINE.find(text, paragraphStart)
        val paragraphEnd = next?.let { it.range.first + it.value.indexOf('\n') } ?: text.length
        val newline = text.indexOf('\n', paragraphStart)
        if (newline != -1 && newline <= paragraphEnd) {
            val candidate = text.substring(paragraphStart, newline).trim()
            if (candidate.isNotEmpty()) summary = candidate
        }
        if (next == null) return summary.take(PREVIEW_CHARS)
        paragraphStart = next.range.last + 1
    }
}

private val BIDI_FORMATTING_CONTROLS = setOf(
    0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
    0x2066, 0x2067, 0x2068, 0x2069,
)
