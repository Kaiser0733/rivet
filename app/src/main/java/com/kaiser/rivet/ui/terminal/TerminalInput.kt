package com.kaiser.rivet.ui.terminal

/**
 * Maps text arriving from the IME or a hardware character key to terminal bytes.
 *
 * The sticky CTRL/ALT keys are one-shot: the caller clears them after one mapped key.
 * A terminal wants CR for Enter; the IME supplies LF, so normalize it here.
 */
internal fun terminalText(text: String, ctrl: Boolean, alt: Boolean): String {
    if (text.isEmpty()) return ""
    val normalized = text.replace("\r\n", "\r").replace('\n', '\r')
    val mapped = if (ctrl) {
        val first = normalized.first()
        (controlCode(first)?.toString() ?: first.toString()) + normalized.drop(1)
    } else normalized
    return if (alt) "\u001b$mapped" else mapped
}

/** C0 control code for a CTRL-modified printable key, or null when it has none. */
internal fun controlCode(char: Char): Char? = when (char) {
    in 'a'..'z' -> (char - 'a' + 1).toChar()
    in 'A'..'Z' -> (char - 'A' + 1).toChar()
    '@', ' ' -> '\u0000'
    '[' -> '\u001b'
    '\\' -> '\u001c'
    ']' -> '\u001d'
    '^' -> '\u001e'
    '_' -> '\u001f'
    '?' -> '\u007f'
    else -> null
}
