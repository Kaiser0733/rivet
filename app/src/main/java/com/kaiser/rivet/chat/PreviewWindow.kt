package com.kaiser.rivet.chat

// Streaming deltas arrive far faster than a phone can lay out text, and each publication
// copies the whole preview and re-renders it. Publish at most once per window instead;
// the settled message always carries the complete text, so a dropped interim preview
// loses nothing.
internal class PreviewWindow(private val intervalNanos: Long = 80_000_000L) {
    private var published = false
    private var lastPublished = 0L

    fun shouldPublish(nowNanos: Long): Boolean {
        if (published && nowNanos >= lastPublished && nowNanos - lastPublished < intervalNanos) {
            return false
        }
        published = true
        lastPublished = nowNanos
        return true
    }

    fun reset() {
        published = false
        lastPublished = 0L
    }
}
