package com.kaiser.rivet.local

/**
 * Removes model reasoning from the user-visible stream. Qwen may emit real
 * `think` tags or the HTML-escaped form; a reply can also stop before the close
 * tag, which still has to stay out of the bubble.
 *
 * Markers can split across native token callbacks, so a short suffix is held
 * back until we know whether it starts a tag.
 */
internal class LocalThinkingFilter {
    private val pending = StringBuilder()
    private var thinkingDepth = 0
    // The short marker "</think" does not include the closing '>'. It can arrive
    // one callback later, so remember to consume that delimiter.
    private var consumeClosingBracket = false

    fun feed(delta: String): String {
        pending.append(delta)
        return drain(final = false)
    }

    fun finish(): String {
        val visible = drain(final = true)
        pending.clear()
        thinkingDepth = 0
        consumeClosingBracket = false
        return visible
    }

    private fun drain(final: Boolean): String {
        val out = StringBuilder()
        if (consumeClosingBracket) {
            if (pending.startsWith(">")) {
                pending.deleteCharAt(0)
                consumeClosingBracket = false
            } else if (pending.isNotEmpty()) {
                consumeClosingBracket = false
            }
        }
        while (pending.isNotEmpty()) {
            if (thinkingDepth > 0) {
                val start = earliestStart()
                val end = earliestEnd()
                val open = start != null && (end == null || start.first < end.first)
                if (open) {
                    pending.delete(0, start.first + start.second)
                    thinkingDepth++
                    continue
                }
                if (end != null) {
                    pending.delete(0, end.first + end.second)
                    if (pending.startsWith(">")) pending.deleteCharAt(0)
                    else consumeClosingBracket = true
                    thinkingDepth--
                    continue
                }
                if (final) {
                    pending.clear()
                    thinkingDepth = 0
                    break
                }
                holdSuffix(START_MARKERS + END_MARKERS)
                break
            }
            val start = earliestStart()
            if (start != null) {
                out.append(pending.substring(0, start.first))
                pending.delete(0, start.first + start.second)
                thinkingDepth = 1
                continue
            }
            if (final) {
                out.append(pending)
                pending.clear()
                break
            }
            val keep = heldSuffixLength(START_MARKERS)
            val emit = (pending.length - keep).coerceAtLeast(0)
            if (emit == 0) break
            out.append(pending.substring(0, emit))
            pending.delete(0, emit)
        }
        return out.toString()
    }

    /** Returns (index, length) of the earliest closing marker. */
    private fun earliestEnd(): Pair<Int, Int>? = earliest(END_MARKERS)

    private fun earliestStart(): Pair<Int, Int>? = earliest(START_MARKERS)

    private fun earliest(markers: List<String>): Pair<Int, Int>? {
        val lower = pending.toString().lowercase()
        var best: Pair<Int, Int>? = null
        for (marker in markers) {
            val index = lower.indexOf(marker)
            if (index >= 0 && (best == null || index < best.first)) best = index to marker.length
        }
        return best
    }

    /** Drop all but a suffix that could be the beginning of a marker or closing tag. */
    private fun holdSuffix(markers: List<String>) {
        val keep = heldSuffixLength(markers)
        if (pending.length > keep) pending.delete(0, pending.length - keep)
    }

    private fun heldSuffixLength(markers: List<String>): Int {
        val text = pending.toString().lowercase()
        val max = markers.maxOf { it.length }.coerceAtMost(text.length)
        for (length in max downTo 1) {
            val tail = text.takeLast(length)
            if (markers.any { it.startsWith(tail) }) return length
        }
        return 0
    }

    companion object {
        private val START_MARKERS = listOf("<thinking", "<think", "&lt;think&gt;")
        private val END_MARKERS = listOf("</thinking", "</think", "&lt;/think&gt;")

        fun strip(text: String): String {
            val filter = LocalThinkingFilter()
            return (filter.feed(text) + filter.finish()).trim()
        }
    }
}
