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
    val capped = if (collapsed.length <= MAX_PROVIDER_DETAIL) collapsed
        else collapsed.take(MAX_PROVIDER_DETAIL).trimEnd() + "…"
    return "The provider said: $capped"
}

private fun decodeEntities(value: String): String = value
    .replace("&nbsp;", " ")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&apos;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&amp;", "&")

private val TAG = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")
