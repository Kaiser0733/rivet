package com.kaiser.rivet.runtime

/**
 * The raw fields Termux puts in the result bundle. Values arrive as objects so a
 * build that stores a number where a string is expected (or the reverse) still
 * parses instead of tripping Bundle's type warnings.
 */
internal data class TermuxResultBundle(
    val err: Any? = null,
    val errmsg: String? = null,
    val stdout: String? = null,
    val stderr: String? = null,
    val exitCode: Any? = null,
    val stdoutOriginalLength: Any? = null,
    val stderrOriginalLength: Any? = null,
)

internal data class TermuxOutcome(
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int? = null,
    val error: String? = null,
    val detail: String? = null,
    val truncated: Boolean = false,
)

internal const val TERMUX_NO_RESULT = "termux_no_result"

/**
 * Termux sends the original stdout/stderr lengths as strings and the exit code as
 * an int; reading them all through the same narrow accessor keeps every stage's
 * error honest. A non-zero err is Termux reporting its own failure, so its message
 * is kept as [TermuxOutcome.detail] instead of being flattened into one generic code.
 */
internal fun parseTermuxResult(raw: TermuxResultBundle): TermuxOutcome {
    val stdout = raw.stdout.orEmpty()
    val stderr = raw.stderr.orEmpty()
    val errmsg = raw.errmsg?.trim()?.takeIf { it.isNotEmpty() }
    val err = asInt(raw.err) ?: 0
    val error = when {
        err == 0 -> null
        errmsg?.contains("allow-external-apps", ignoreCase = true) == true -> "termux_external_apps_off"
        else -> "termux_command_failed"
    }
    return TermuxOutcome(
        stdout = stdout,
        stderr = stderr,
        exitCode = asInt(raw.exitCode),
        error = error,
        detail = errmsg?.take(400),
        truncated = asLong(raw.stdoutOriginalLength) > stdout.length ||
            asLong(raw.stderrOriginalLength) > stderr.length,
    )
}

private fun asInt(value: Any?): Int? = when (value) {
    is Int -> value
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}

private fun asLong(value: Any?): Long = when (value) {
    is Number -> value.toLong()
    is String -> value.trim().toLongOrNull() ?: 0L
    else -> 0L
}
