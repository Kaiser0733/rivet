package com.kaiser.rivet.runtime

/**
 * Line-oriented paging for command output. Stdout is paged/filtered; stderr is kept
 * separate so an offset or tail can never hide the reason a command failed.
 */
object CommandOutputSlicer {
    const val MAX_TOOL_RESULT_BYTES = 24 * 1_024
    const val MAX_STDOUT_BYTES = 20 * 1_024
    const val MAX_STDERR_BYTES = 4 * 1_024
    const val MAX_LINES = 500
    const val MAX_GREP_CHARS = 256

    data class Slice(
        val stdout: String,
        val stderr: String,
        val totalLines: Int,
        val returnedLines: Int,
        val nextOffset: Int?,
        val matchedLines: Int?,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
        val stderrPresent: Boolean,
    )

    fun slice(
        stdout: String,
        stderr: String,
        offset: Int? = null,
        tail: Int? = null,
        grep: String? = null,
        maxLines: Int? = null,
        maxBytes: Int = MAX_TOOL_RESULT_BYTES,
    ): Slice {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val startRequest = (offset ?: 0).coerceAtLeast(0)
        if (tail != null && startRequest > 0) {
            throw IllegalArgumentException("Pass either offset or tail, not both.")
        }
        if (grep != null && grep.length > MAX_GREP_CHARS) {
            throw IllegalArgumentException(
                "grep is ${grep.length} chars; the limit is $MAX_GREP_CHARS.",
            )
        }
        val all = lines(stdout)
        val filtered = if (grep.isNullOrEmpty()) all else all.filter { it.contains(grep) }
        val start = if (tail != null) {
            (filtered.size - tail.coerceIn(1, MAX_LINES)).coerceAtLeast(0)
        } else {
            startRequest
        }
        val count = (maxLines ?: MAX_LINES).coerceIn(1, MAX_LINES)
        val available = filtered.drop(start).take(count)
        val stdoutBudget = minOf(MAX_STDOUT_BYTES, maxBytes)
        val selected = ArrayList<String>(available.size)
        var stdoutBytes = 0
        for (line in available) {
            val cost = line.toByteArray(Charsets.UTF_8).size + 1
            if (selected.isNotEmpty() && stdoutBytes + cost > stdoutBudget) break
            selected += line
            stdoutBytes += cost
        }
        val stdoutTruncated = selected.size < available.size
        val nextOffset = if (start + selected.size < filtered.size) {
            start + selected.size
        } else null
        val stderrBudget = minOf(MAX_STDERR_BYTES, maxBytes)
        val stderrTruncated = stderr.toByteArray(Charsets.UTF_8).size > stderrBudget
        return Slice(
            stdout = selected.joinToString("\n"),
            stderr = boundStderr(stderr, stderrBudget),
            totalLines = all.size,
            returnedLines = selected.size,
            nextOffset = nextOffset,
            matchedLines = if (grep.isNullOrEmpty()) null else filtered.size,
            stdoutTruncated = stdoutTruncated,
            stderrTruncated = stderrTruncated,
            stderrPresent = stderr.isNotEmpty(),
        )
    }

    private fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val split = text.split('\n')
        return if (split.lastOrNull().isNullOrEmpty()) split.dropLast(1) else split
    }

    private fun boundStderr(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val marker = "\n… [stderr truncated] …\n"
        val markerBytes = marker.toByteArray(Charsets.UTF_8).size
        val side = ((maxBytes - markerBytes).coerceAtLeast(2)) / 2
        val head = takeUtf8(text, side)
        val tail = takeUtf8(text.reversed(), side).reversed()
        return head + marker + tail
    }

    private fun takeUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var end = text.length
        while (end > 0 && text.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
            end = (end * 3) / 4
        }
        return text.substring(0, end)
    }
}
