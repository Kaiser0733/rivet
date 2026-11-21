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
    fun addressLabel(url: String): String {
        if (url.isBlank() || url == "about:blank") return "Web address"
        return runCatching { java.net.URI(url).host }.getOrNull() ?: url
    }

    fun tabTitle(title: String, url: String): String = when {
        url == "about:blank" -> "New tab"
        title.isBlank() || title == url -> if (url.isBlank()) "New tab" else addressLabel(url)
        else -> title
    }

    fun title(title: String, url: String): String {
        if (title.isNotBlank()) return title
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
        return host.ifBlank { "New tab" }
    }

    fun chips(windows: List<BrowserWindowInfo>): List<BrowserTabChip> =
        windows.map { window ->
            BrowserTabChip(window.id, title(window.title, window.url), window.active)
        }

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
