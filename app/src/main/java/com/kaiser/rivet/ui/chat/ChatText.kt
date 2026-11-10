package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.storage.SessionUsage
import java.util.Locale

internal const val COMMAND_APPROVAL_WARNING =
    "Project commands can read, change, or delete project files and Rivet's saved data. They can also use the network."

// Escapes bidi controls so a provider's text cannot reorder what is around it. Nothing to escape
// is the common case, and it arrives on every streamed delta with the whole answer attached, so it
// returns the same instance instead of copying that whole string to say "unchanged". Callers only
// ever read the value, so which instance they get cannot matter; the escaped case produces exactly
// the text it always did.
internal fun displaySafeText(value: String): String {
    for (index in value.indices) {
        if (value[index].code in BIDI_FORMATTING_CONTROLS) return escapeBidi(value)
    }
    return value
}

private fun escapeBidi(value: String): String = buildString(value.length) {
    value.forEach { char ->
        if (char.code in BIDI_FORMATTING_CONTROLS) append("\\u%04X".format(Locale.ROOT, char.code))
        else append(char)
    }
}

// Vendor-qualified ids ("anthropic/claude-sonnet-4-5") are unreadable in a
// one-line selector. Claude ids become words, GPT ids keep their shape, and
// everything else is left exactly as the provider spelled it.
/** Completed turns shown in the footer. Local providers report no token usage, but
 * the turn still happened, so unknown-usage requests count too. */
internal fun footerTurnCount(usage: SessionUsage?): Int =
    ((usage?.reportedRequests ?: 0) + (usage?.unknownRequests ?: 0)).coerceAtLeast(0)

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
//
// Callers that see the chain grow (the streaming row) pass their own [PreviewScan] so a
// delta only reads what arrived; everything else runs a scan from zero. Both go through
// the same scanner, so a test of this function covers the composable's path.
internal fun thinkingPreview(text: String, streaming: Boolean, scan: PreviewScan = PreviewScan()): String =
    if (!streaming) firstLine(text) else scan.advance(text).ifEmpty { tailOfLastLine(text) }

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

// The scan behind the streaming preview. A long chain publishes a delta every few tokens
// and each of them used to read the whole chain, so the cost of one turn was its own length
// times its delta count. The scan therefore remembers the paragraph it stopped in: every
// paragraph that already finished a line is represented by [summary] and is never read again,
// and `advance` resumes at `paragraphStart`. The reader of a growing string cannot skip the
// paragraph still being written (its first line may arrive at any delta), so a delta costs one
// open paragraph rather than the whole chain.
//
// Resuming is exact because the text only grows during a turn: the paragraphs before
// `paragraphStart` are fully consumed, and re-reading the open one recomputes the same line.
// The one thing growth can still change is a blank-line run that was unfinished at the end of
// the text, which becomes a longer separator and moves where the next paragraph starts, so the
// resume point never sits inside a trailing run of blanks. A blank line that is *complete*
// before the run is untouched by later text and stays behind the resume point.
//
// A scan handed a shorter text is a different chain and starts over.
//
// Not thread-safe and not reusable: it mutates and the streaming row owns one instance.
internal class PreviewScan {
    private var summary = ""
    private var paragraphStart = 0
    private var read = 0

    /** The preview for [text], reading only what arrived since the previous call. */
    fun advance(text: String): String {
        if (text.length < read) {
            summary = ""
            paragraphStart = 0
            read = 0
        }
        if (text.length == read) return summary.take(PREVIEW_CHARS)
        var start = paragraphStart
        while (true) {
            val next = BLANK_LINE.find(text, start)
            val paragraphEnd = next?.let { it.range.first + it.value.indexOf('\n') } ?: text.length
            val newline = text.indexOf('\n', start)
            if (newline != -1 && newline <= paragraphEnd) {
                val candidate = text.substring(start, newline).trim()
                if (candidate.isNotEmpty()) summary = candidate
            }
            if (next == null) {
                paragraphStart = minOf(start, blankTailStart(text))
                read = text.length
                return summary.take(PREVIEW_CHARS)
            }
            start = next.range.last + 1
        }
    }
}

// Where the trailing run of whitespace begins. Whitespace is a superset of the characters a
// separator is made of, so resuming at or before this index is always safe: it can only make
// the scan re-read a few characters, never skip one that a longer separator would move.
private fun blankTailStart(text: String): Int {
    var index = text.length
    while (index > 0 && text[index - 1].isWhitespace()) index--
    return index
}

private val BIDI_FORMATTING_CONTROLS = setOf(
    0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
    0x2066, 0x2067, 0x2068, 0x2069,
)
