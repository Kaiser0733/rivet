package com.kaiser.rivet.runtime

internal data class TermuxRunCommandIntent(
    val action: String,
    val service: String,
    val path: String,
    val arguments: List<String>,
    val cwd: String,
    val background: Boolean,
)

internal fun buildRunCommandIntent(command: String, cwd: String): TermuxRunCommandIntent {
    require(command.isNotBlank() && '\u0000' !in command && command.toByteArray(Charsets.UTF_8).size <= 8192)
    require(cwd.length <= 4096 && '\u0000' !in cwd)
    // Termux only captures stdout/stderr for its background app-shell runner. Before
    // EXTRA_RUNNER existed (0.118.0 and older) this boolean was the only selector, and
    // false ran the command in a terminal session that returns no output at all.
    return TermuxRunCommandIntent(
        "com.termux.RUN_COMMAND", "com.termux.app.RunCommandService",
        TermuxCommand.BASH, listOf("-c", command), cwd, background = true,
    )
}

internal object TermuxCommand {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val BASH = "/data/data/com.termux/files/usr/bin/bash"
    const val SETUP = "In Android Settings → Apps → Rivet → Permissions → Additional permissions, allow Run commands in Termux environment. " +
        "In Termux, set allow-external-apps=true in ~/.termux/termux.properties, then run termux-reload-settings."

    fun timeout(value: Long?): Long {
        require(value == null || value > 0)
        return (value ?: 120_000L).coerceAtMost(600_000L)
    }

    fun withFileOutput(command: String, folder: String, previewBytes: Int = 6 * 1024): String = """
        folder=${quote(folder)}
        if ! mkdir -p -- "${'$'}folder" || ! { : > "${'$'}folder/out.txt" && : > "${'$'}folder/err.txt"; }; then
            exec $BASH -c ${quote(command)}
        fi
        $BASH -c ${quote(command)} > "${'$'}folder/out.txt" 2> "${'$'}folder/err.txt"
        code=${'$'}?
        printf '%s\n' "${'$'}code" > "${'$'}folder/code.tmp" && mv -- "${'$'}folder/code.tmp" "${'$'}folder/code"
        head -c ${previewBytes + 1} -- "${'$'}folder/out.txt"
        head -c ${previewBytes + 1} -- "${'$'}folder/err.txt" >&2
        exit "${'$'}code"
    """.trimIndent()

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
