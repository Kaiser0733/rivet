package com.kaiser.rivet.replay

internal enum class ReplayFormat(val extension: String, val mime: String) {
    Video("mp4", "video/mp4"),
    Gif("gif", "image/gif"),
}

internal enum class ReplayDestination { Gallery, Share }

internal enum class ReplayChoice(val label: String) {
    Video("Video"),
    Gif("GIF"),
    ShareSheet("Share sheet");

    fun destination(sdk: Int): ReplayDestination =
        if (sdk >= 29 && this != ShareSheet) ReplayDestination.Gallery else ReplayDestination.Share

    fun format(prepared: ReplayFormat?): ReplayFormat = when (this) {
        Video -> ReplayFormat.Video
        Gif -> ReplayFormat.Gif
        ShareSheet -> prepared ?: ReplayFormat.Video
    }
}

internal fun replayWatermarkEnabled(value: Any?): Boolean = value as? Boolean ?: true
