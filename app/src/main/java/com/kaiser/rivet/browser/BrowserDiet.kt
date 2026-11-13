package com.kaiser.rivet.browser

enum class BrowserResourceKind { Image, Font, Media, Tracker, Other }

data class BrowserDietRules(
    val blockImages: Boolean = false,
    val blockFonts: Boolean = false,
    val blockMedia: Boolean = false,
    val blockTrackers: Boolean = true,
    val maxImageBytes: Long = 500L * 1024,
    val fullPage: Boolean = false,
)

object BrowserDiet {
    private val imageExtRegex = Regex("(?i).*\\.(png|jpe?g|gif|webp|svg|avif)(\\?.*)?$")
    private val fontExtRegex = Regex("(?i).*\\.(woff2?|ttf|otf)(\\?.*)?$")
    private val mediaExtRegex = Regex("(?i).*\\.(mp4|webm|m4s|mp3)(\\?.*)?$")

    private val trackers = listOf(
        "google-analytics.com", "googletagmanager.com", "doubleclick.net",
        "facebook.net", "connect.facebook.net", "segment.io", "mixpanel.com",
        "hotjar.com", "fullstory.com", "amplitude.com", "sentry.io",
        "adservice.google.com", "adsystem.com", "scorecardresearch.com",
    )

    fun rulesFor(tier: BrowserTier, fullPage: Boolean): BrowserDietRules = when {
        fullPage -> BrowserDietRules(blockTrackers = false, fullPage = true)
        tier == BrowserTier.Normal -> BrowserDietRules(blockImages = false, blockFonts = false,
            blockMedia = true, blockTrackers = true)
        tier == BrowserTier.StrictDiet -> BrowserDietRules(blockImages = true, blockFonts = false,
            blockMedia = true, blockTrackers = true, maxImageBytes = 0L)
        else -> BrowserDietRules(blockImages = true, blockFonts = true, blockMedia = true,
            blockTrackers = true, maxImageBytes = 0L)
    }

    fun isTrackerHost(host: String): Boolean {
        val lower = host.lowercase()
        return trackers.any { lower == it || lower.endsWith(".$it") }
    }

    /** A layout-preserving 1x1 PNG for strict-tier images, never a broken-image glyph. */
    fun placeholderPng(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")

    fun classifyResource(
        url: String,
        isForMainFrame: Boolean,
        acceptHeader: String? = null,
        secFetchDest: String? = null,
    ): BrowserResourceKind {
        if (isForMainFrame) return BrowserResourceKind.Other
        val dest = secFetchDest?.lowercase()
        if (dest == "document" || dest == "iframe") return BrowserResourceKind.Other
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        val tracker = isTrackerHost(host)
        val isDocumentAccept = acceptHeader?.contains("text/html", ignoreCase = true) == true ||
            acceptHeader?.contains("application/xhtml+xml", ignoreCase = true) == true
        return when {
            dest == "image" -> BrowserResourceKind.Image
            !isDocumentAccept && (acceptHeader?.contains("image", ignoreCase = true) == true ||
                imageExtRegex.matches(url)) -> BrowserResourceKind.Image
            fontExtRegex.matches(url) -> BrowserResourceKind.Font
            mediaExtRegex.matches(url) -> BrowserResourceKind.Media
            tracker -> BrowserResourceKind.Tracker
            else -> BrowserResourceKind.Other
        }
    }

    fun shouldBlock(url: String, kind: BrowserResourceKind, declaredBytes: Long,
                    rules: BrowserDietRules, isForMainFrame: Boolean = false): Boolean {
        if (isForMainFrame || rules.fullPage) return false
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        if (isTrackerHost(host)) return true
        return when (kind) {
            BrowserResourceKind.Tracker -> true
            BrowserResourceKind.Image -> rules.blockImages ||
                (rules.maxImageBytes > 0 && declaredBytes > rules.maxImageBytes)
            BrowserResourceKind.Font -> rules.blockFonts
            BrowserResourceKind.Media -> rules.blockMedia
            BrowserResourceKind.Other -> false
        }
    }
}
