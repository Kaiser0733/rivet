package com.kaiser.rivet.browser

/** Pages never ask; a large file on a metered network asks once. */
object BrowserDownloadPolicy {
    const val ASK_ABOVE_BYTES = 10L * 1024 * 1024

    fun asksFirst(sizeBytes: Long, metered: Boolean): Boolean =
        metered && sizeBytes > ASK_ABOVE_BYTES
}
