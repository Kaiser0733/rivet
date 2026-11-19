package com.kaiser.rivet.chat

// Provider failures often carry their own explanation, and that sentence is usually
// the whole answer ("insufficient balance", "model overloaded"). Rivet used to throw
// it away. It is shown now, but only after markup is stripped and the length is
// bounded, so a stray HTML error page cannot flood the conversation.

private const val MAX_PROVIDER_DETAIL = 300

internal fun providerMessageText(raw: String): String? {
    val withoutTags = raw.replace(TAG, " ")
    val decoded = decodeEntities(withoutTags)
    val collapsed = decoded.replace(WHITESPACE, " ").trim()
    if (collapsed.isEmpty()) return null
    // take() counts UTF-16 units, so a cut can fall between the halves of an emoji or a
    // supplementary CJK character; dropping the orphaned high surrogate keeps the banner from
    // rendering a replacement glyph.
    val capped = if (collapsed.length <= MAX_PROVIDER_DETAIL) collapsed
        else collapsed.take(MAX_PROVIDER_DETAIL).dropLastWhile { it.isHighSurrogate() }.trimEnd() + "…"
    return "The provider said: $capped"
}

internal fun decodeEntities(value: String): String = ENTITY.replace(value) { match ->
    val named = match.groups[3]?.value
    if (named != null) {
        NAMED_ENTITIES[named] ?: match.value
    } else {
        val hex = match.groups[1]?.value
        val code = if (hex != null) hex.toIntOrNull(16)
        else match.groups[2]?.value?.toIntOrNull()
        if (code != null && code in 0..0x10FFFF && code !in 0xD800..0xDFFF)
            String(Character.toChars(code))
        else match.value
    }
}

private val ENTITY = Regex("&(?:#(?:[xX]([0-9a-fA-F]+)|([0-9]+))|([a-zA-Z]+));")
private val NAMED_ENTITIES = mapOf(
    "nbsp" to " ",
    "quot" to "\"",
    "apos" to "'",
    "lt" to "<",
    "gt" to ">",
    "amp" to "&",
    "ndash" to "–",
    "mdash" to "—",
    "hellip" to "…",
    "lsquo" to "‘",
    "rsquo" to "’",
    "ldquo" to "“",
    "rdquo" to "”",
)

private val TAG = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")
