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

data class WindowCloseTarget(
    val activeId: String?,
    val url: String,
    val title: String,
    val readerMode: Boolean = false,
    val readerText: String = "",
    val pageReady: Boolean = false,
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

    /**
     * State the chrome and renderer must adopt after a close. Closing an inactive
     * window leaves the current page alone; closing the active one selects the last
     * remaining window, whose URL and title must replace the closed page's state.
     */
    fun closeTarget(
        windows: List<BrowserWindowInfo>,
        activeId: String?,
        closingId: String,
    ): WindowCloseTarget {
        if (closingId != activeId) {
            val active = windows.firstOrNull { it.id == activeId }
            return WindowCloseTarget(activeId, active?.url.orEmpty(), active?.title.orEmpty())
        }
        val next = windows.lastOrNull { it.id != closingId }
        return WindowCloseTarget(next?.id, next?.url.orEmpty(), next?.title.orEmpty())
    }
}
