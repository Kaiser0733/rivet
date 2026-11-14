package com.kaiser.rivet.subagent

/**
 * A bounded unified line diff. It is deliberately not a full Myers diff: the
 * worker result needs a truthful, small excerpt of changed lines, not a patch
 * programmer. Additions/deletions always count the full change even when the
 * rendered text is truncated.
 */
data class WorkerDiff(
    val path: String,
    val text: String,
    val additions: Int,
    val deletions: Int,
    val truncated: Boolean,
) {
    companion object {
        const val MAX_BYTES = 10 * 1024

        fun diff(
            path: String,
            before: ByteArray,
            after: ByteArray,
            maxBytes: Int = MAX_BYTES,
        ): WorkerDiff {
            if (before.contentEquals(after)) {
                return WorkerDiff(path, "", 0, 0, truncated = false)
            }
            val beforeLines = textLines(before)
            val afterLines = textLines(after)
            var prefix = 0
            while (prefix < beforeLines.size && prefix < afterLines.size &&
                beforeLines[prefix] == afterLines[prefix]) {
                prefix++
            }
            var suffix = 0
            while (suffix < beforeLines.size - prefix &&
                suffix < afterLines.size - prefix &&
                beforeLines[beforeLines.lastIndex - suffix] ==
                afterLines[afterLines.lastIndex - suffix]) {
                suffix++
            }
            val deleted = beforeLines.subList(prefix, beforeLines.size - suffix)
            val added = afterLines.subList(prefix, afterLines.size - suffix)
            val oldStart = if (deleted.isEmpty()) prefix else prefix + 1
            val newStart = if (added.isEmpty()) prefix else prefix + 1
            val header = buildString {
                append("--- a/").append(path).append('\n')
                append("+++ b/").append(path).append('\n')
                append("@@ -").append(oldStart).append(',').append(deleted.size)
                append(" +").append(newStart).append(',').append(added.size)
                append(" @@\n")
            }
            val rendered = StringBuilder()
            var bytes = 0
            var truncated = false
            fun appendLine(line: String) {
                val size = line.toByteArray(Charsets.UTF_8).size + 1
                if (bytes + size > maxBytes) {
                    truncated = true
                    return
                }
                rendered.append(line).append('\n')
                bytes += size
            }
            if (header.toByteArray(Charsets.UTF_8).size <= maxBytes) {
                rendered.append(header)
                bytes += header.toByteArray(Charsets.UTF_8).size
                for (line in deleted) {
                    appendLine("-$line")
                    if (truncated) break
                }
                if (!truncated) {
                    for (line in added) {
                        appendLine("+$line")
                        if (truncated) break
                    }
                }
            } else {
                truncated = true
            }
            return WorkerDiff(
                path = path,
                text = rendered.toString(),
                additions = added.size,
                deletions = deleted.size,
                truncated = truncated,
            )
        }

        private fun textLines(bytes: ByteArray): List<String> =
            if (bytes.isEmpty()) emptyList() else String(bytes, Charsets.UTF_8).split('\n')
    }
}
