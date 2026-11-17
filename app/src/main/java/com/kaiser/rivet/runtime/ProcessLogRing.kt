package com.kaiser.rivet.runtime

/** Thread-safe bounded tail of one background process's stdout/stderr. */
class ProcessLogRing(
    private val maxStdoutChars: Int = MAX_STDOUT_CHARS,
    private val maxStderrChars: Int = MAX_STDERR_CHARS,
) {
    private val stdout = StringBuilder()
    private val stderr = StringBuilder()
    var stderrPresent: Boolean = false
        private set

    @Synchronized
    fun append(out: String, err: String) {
        if (out.isNotEmpty()) {
            stdout.append(out)
            if (stdout.length > maxStdoutChars) stdout.delete(0, stdout.length - maxStdoutChars)
        }
        if (err.isNotEmpty()) {
            stderr.append(err)
            if (stderr.length > maxStderrChars) stderr.delete(0, stderr.length - maxStderrChars)
            stderrPresent = true
        }
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(stdout.toString(), stderr.toString(), stderrPresent)

    data class Snapshot(val stdout: String, val stderr: String, val stderrPresent: Boolean)

    companion object {
        const val MAX_STDOUT_CHARS = 128 * 1024
        const val MAX_STDERR_CHARS = 64 * 1024
        const val MAX_OUTPUT_CHARS = 8 * 1024
    }
}
