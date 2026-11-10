package com.kaiser.rivet.browser

/** A stable short handle for a page control, so a read does not need a second round trip. */
data class BrowserElementRef(val ref: Int, val label: String, val tag: String = "") {
    fun token(): String = "#$ref"
}

object BrowserElementRefs {
    private val objectText = Regex("\\{[^{}]*}")
    private val labelText = Regex("\"label\"\\s*:\\s*\"(.*?)\"")
    private val tagText = Regex("\"tag\"\\s*:\\s*\"(.*?)\"")

    fun fromJson(raw: String): List<BrowserElementRef> =
        objectText.findAll(raw).mapIndexedNotNull { index, match ->
            val body = match.value
            val label = labelText.find(body)?.groupValues?.get(1)?.take(80) ?: return@mapIndexedNotNull null
            val tag = tagText.find(body)?.groupValues?.get(1).orEmpty()
            BrowserElementRef(index + 1, label, tag)
        }.toList()
}
