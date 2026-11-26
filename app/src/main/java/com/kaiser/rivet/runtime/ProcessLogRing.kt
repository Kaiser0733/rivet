package com.kaiser.rivet.runtime

/** Thread-safe bounded tails with absolute UTF-8 byte positions. */
class ProcessLogRing(
    maxStdoutChars: Int = MAX_STDOUT_CHARS,
    maxStderrChars: Int = MAX_STDERR_CHARS,
) {
    private val stdout = Stream(maxStdoutChars)
    private val stderr = Stream(maxStderrChars)

    @Synchronized
    fun append(out: String, err: String) {
        val outBytes = out.toByteArray()
        val errBytes = err.toByteArray()
        stdout.append(outBytes, outBytes.size)
        stderr.append(errBytes, errBytes.size)
    }

    @Synchronized
    internal fun append(isStderr: Boolean, bytes: ByteArray, count: Int) {
        (if (isStderr) stderr else stdout).append(bytes, count)
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(stdout.snapshot(), stderr.snapshot())

    data class Snapshot(val out: Output, val err: Output) {
        val stdout get() = out.text
        val stderr get() = err.text
        val stderrPresent get() = err.end > 0
    }

    data class Output(val text: String, val start: Long, val end: Long)

    private class Stream(capacity: Int) {
        private val buffer = ByteArray(capacity)
        private var total = 0L

        init { require(capacity > 0) }

        fun append(bytes: ByteArray, count: Int) {
            for (index in (count - buffer.size).coerceAtLeast(0) until count) {
                buffer[((total + index) % buffer.size).toInt()] = bytes[index]
            }
            total += count
        }

        fun snapshot(): Output {
            var start = (total - buffer.size).coerceAtLeast(0)
            // A byte-budget eviction can land in the middle of a character.
            while (start < total && buffer[(start % buffer.size).toInt()].toInt() and 0xc0 == 0x80) start++
            val bytes = ByteArray((total - start).toInt()) { buffer[((start + it) % buffer.size).toInt()] }
            return Output(bytes.toString(Charsets.UTF_8), start, total)
        }
    }

    companion object {
        const val MAX_STDOUT_CHARS = 128 * 1024
        const val MAX_STDERR_CHARS = 64 * 1024
        const val MAX_OUTPUT_CHARS = 8 * 1024
    }
}
