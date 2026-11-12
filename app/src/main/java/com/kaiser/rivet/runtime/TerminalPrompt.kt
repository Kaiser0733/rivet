package com.kaiser.rivet.runtime

/** Folder-only prompt; the leading newline keeps it off the tail of command output. */
internal object TerminalPrompt {
    const val PS1 = "\\n\\W \\$ "
}
