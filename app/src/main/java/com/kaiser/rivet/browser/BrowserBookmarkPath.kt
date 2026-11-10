package com.kaiser.rivet.browser

/** A project bookmark is a .url file with no path traversal and no reserved goal files. */
object BrowserBookmarkPath {
    fun file(title: String, timestamp: Long = System.currentTimeMillis()): String {
        val slug = title.ifBlank { "link" }.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-").trim('-').take(48).ifBlank { "link" }
        return ".rivet/browser-links/$slug-$timestamp.url"
    }

    fun contents(title: String, url: String): String =
        "[InternetShortcut]\nURL=$url\nTitle=${title.replace('\n', ' ')}\n"
}
