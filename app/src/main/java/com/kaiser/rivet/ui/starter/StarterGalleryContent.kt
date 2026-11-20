package com.kaiser.rivet.ui.starter

import com.kaiser.rivet.starter.StarterOption
import java.util.Locale

internal const val STARTER_CHIP_LABEL = "Start from a starter"

internal fun starterGalleryEntries(
    bundled: List<StarterOption>, loaded: List<StarterOption>,
): List<StarterOption> {
    val baseline = bundled.associateBy { it.starter.id }
    return loaded.mapNotNull { entry ->
        if (entry.cachedZip?.isFile != false) entry else baseline[entry.starter.id]
    }
}

internal fun starterSize(bytes: Int): String =
    if (bytes < 1000) "$bytes B" else String.format(Locale.ROOT, "%.1f KB", bytes / 1000.0)
