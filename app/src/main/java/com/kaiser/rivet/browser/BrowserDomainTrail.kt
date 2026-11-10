package com.kaiser.rivet.browser

data class BrowserVisit(
    val host: String,
    val url: String,
    val projectPreview: Boolean = false,
    val at: Long = System.currentTimeMillis(),
)

class BrowserDomainTrail(private val maxEntries: Int = 200) {
    private val entries = mutableListOf<BrowserVisit>()
    val visits: List<BrowserVisit> get() = entries.toList()

    fun record(url: String, projectPreview: Boolean = false): BrowserVisit? {
        if (url.isBlank()) return null
        val host = if (projectPreview) "project preview" else runCatching {
            java.net.URI(url).host?.lowercase()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val visit = BrowserVisit(host, url, projectPreview)
        entries += visit
        while (entries.size > maxEntries) entries.removeAt(0)
        return visit
    }

    fun hosts(): List<String> = entries.map { it.host }.distinct()
    fun clear() = entries.clear()
}
