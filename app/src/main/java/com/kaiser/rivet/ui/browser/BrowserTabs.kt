package com.kaiser.rivet.ui.browser

/** Immutable inputs the chrome needs from a browser window. */
data class BrowserWindowInfo(
    val id: String,
    val title: String = "",
    val url: String = "",
    val active: Boolean = false,
)

data class BrowserTabChip(
    val id: String,
    val title: String,
    val active: Boolean,
)

/** Pure tab-strip mapping and close targeting. */
object BrowserTabs {
    fun title(title: String, url: String): String {
        if (title.isNotBlank()) return title
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
        return host.ifBlank { "New tab" }
    }

    fun chips(windows: List<BrowserWindowInfo>): List<BrowserTabChip> =
        windows.map { window ->
            BrowserTabChip(window.id, title(window.title, window.url), window.active)
        }

    /** Only the active chip owns the strip's close X. */
    fun activeCloseTarget(windows: List<BrowserWindowInfo>): String? =
        windows.firstOrNull { it.active }?.id
}
