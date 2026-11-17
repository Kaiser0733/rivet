package com.kaiser.rivet.browser

import java.util.Locale

enum class BrowserKeyModifier {
    Ctrl,
    Shift,
    Alt,
    Meta,
}

/**
 * A parsed key, carrying both the Android dispatch values and the JavaScript
 * KeyboardEvent fields. This is intentionally independent of android.view.KeyEvent so
 * key parsing and approval decisions can be unit-tested as pure JVM code.
 */
data class BrowserKeySpec(
    val key: String,
    val code: String,
    val keyCode: Int,
    val charCode: Int,
    val modifiers: Set<BrowserKeyModifier>,
    val metaState: Int,
) {
    val ctrlKey: Boolean get() = BrowserKeyModifier.Ctrl in modifiers
    val shiftKey: Boolean get() = BrowserKeyModifier.Shift in modifiers
    val altKey: Boolean get() = BrowserKeyModifier.Alt in modifiers
    val metaKey: Boolean get() = BrowserKeyModifier.Meta in modifiers
}

object BrowserKeyMapping {
    const val META_SHIFT_ON = 1
    const val META_ALT_ON = 2
    const val META_CTRL_ON = 4_096
    const val META_META_ON = 65_536

    private data class KeyDef(
        val key: String,
        val code: String,
        val keyCode: Int,
        val charCode: Int,
    )

    private val named = mapOf(
        "arrowup" to KeyDef("ArrowUp", "ArrowUp", 19, 0),
        "arrowdown" to KeyDef("ArrowDown", "ArrowDown", 20, 0),
        "arrowleft" to KeyDef("ArrowLeft", "ArrowLeft", 21, 0),
        "arrowright" to KeyDef("ArrowRight", "ArrowRight", 22, 0),
        "enter" to KeyDef("Enter", "Enter", 66, 13),
        "escape" to KeyDef("Escape", "Escape", 111, 27),
        "tab" to KeyDef("Tab", "Tab", 61, 9),
        "backspace" to KeyDef("Backspace", "Backspace", 67, 8),
        "delete" to KeyDef("Delete", "Delete", 112, 46),
        "insert" to KeyDef("Insert", "Insert", 124, 45),
        "home" to KeyDef("Home", "Home", 3, 36),
        "end" to KeyDef("End", "End", 123, 35),
        "pageup" to KeyDef("PageUp", "PageUp", 92, 33),
        "pagedown" to KeyDef("PageDown", "PageDown", 93, 34),
        "space" to KeyDef(" ", "Space", 62, 32),
    ).toMutableMap().apply {
        for (n in 1..12) put("f$n", KeyDef("F$n", "F$n", 130 + n, 0))
    }

    private val punctuation = mapOf(
        ',' to KeyDef(",", "Comma", 55, ','.code),
        '.' to KeyDef(".", "Period", 56, '.'.code),
        '/' to KeyDef("/", "Slash", 76, '/'.code),
        ';' to KeyDef(";", "Semicolon", 74, ';'.code),
        '\'' to KeyDef("'", "Quote", 75, '\''.code),
        '-' to KeyDef("-", "Minus", 69, '-'.code),
        '=' to KeyDef("=", "Equal", 70, '='.code),
        '[' to KeyDef("[", "BracketLeft", 71, '['.code),
        ']' to KeyDef("]", "BracketRight", 72, ']'.code),
        '\\' to KeyDef("\\", "Backslash", 73, '\\'.code),
        '`' to KeyDef("`", "Backquote", 68, '`'.code),
    )

    fun parse(key: String, modifiers: List<String> = emptyList()): BrowserKeySpec {
        val raw = if (key.isNotEmpty() && key.all { it.isWhitespace() }) "Space"
        else key.trim()
        require(raw.isNotEmpty()) {
            invalid("")
        }
        val parts = raw.split('+')
        val keyPart = parts.last().trim()
        val modifierTokens = parts.dropLast(1).map { it.trim() } + modifiers
        val parsedModifiers = modifierTokens.filter { it.isNotEmpty() }
            .map(::modifier)
            .toSet()
        val def = definition(keyPart)
        return BrowserKeySpec(
            key = def.key,
            code = def.code,
            keyCode = def.keyCode,
            charCode = def.charCode,
            modifiers = parsedModifiers,
            metaState = metaFor(parsedModifiers),
        )
    }

    private fun definition(raw: String): KeyDef {
        val lower = raw.lowercase(Locale.ROOT)
        named[lower]?.let { return it }
        if (lower == " ") return named["space"]!!
        if (raw.length == 1) {
            val ch = raw[0]
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                val lowerChar = ch.lowercaseChar()
                val index = lowerChar - 'a'
                return KeyDef(raw, "Key${ch.uppercaseChar()}", 29 + index, ch.code)
            }
            if (ch in '0'..'9') {
                return KeyDef(raw, "Digit$ch", 7 + (ch - '0'), ch.code)
            }
            punctuation[ch]?.let { return it }
        }
        throw IllegalArgumentException(invalid(raw))
    }

    private fun modifier(raw: String): BrowserKeyModifier = when (raw.lowercase(Locale.ROOT)) {
        "ctrl", "control" -> BrowserKeyModifier.Ctrl
        "shift" -> BrowserKeyModifier.Shift
        "alt", "option" -> BrowserKeyModifier.Alt
        "meta", "cmd", "command", "win", "super" -> BrowserKeyModifier.Meta
        else -> throw IllegalArgumentException(
            "Unknown modifier '$raw'. Use ctrl, shift, alt, or meta."
        )
    }

    private fun metaFor(modifiers: Set<BrowserKeyModifier>): Int =
        (if (BrowserKeyModifier.Ctrl in modifiers) META_CTRL_ON else 0) or
            (if (BrowserKeyModifier.Shift in modifiers) META_SHIFT_ON else 0) or
            (if (BrowserKeyModifier.Alt in modifiers) META_ALT_ON else 0) or
            (if (BrowserKeyModifier.Meta in modifiers) META_META_ON else 0)

    private fun invalid(raw: String): String =
        "Unknown key '$raw'. Use W3C names like ArrowUp, Enter, Space, Escape, Tab, " +
            "Backspace, a-z, or Ctrl+s."
}
