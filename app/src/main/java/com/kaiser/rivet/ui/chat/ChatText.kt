package com.kaiser.rivet.ui.chat

import java.util.Locale

internal const val COMMAND_APPROVAL_WARNING =
    "Project commands run code with Rivet's app permissions. They can read Rivet's saved data and change your project."

internal fun displaySafeText(value: String): String = buildString(value.length) {
    value.forEach { char ->
        if (char.code in BIDI_FORMATTING_CONTROLS) append("\\u%04X".format(Locale.ROOT, char.code))
        else append(char)
    }
}

private val BIDI_FORMATTING_CONTROLS = setOf(
    0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
    0x2066, 0x2067, 0x2068, 0x2069,
)
