package com.kaiser.rivet.ui.local

import com.kaiser.rivet.local.LocalModelEntry
import com.kaiser.rivet.local.LocalToolCalling
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelRowsTest {
    @Test fun recommendationsAppearOnceEvenWhileInstalledOrDownloading() {
        val recommended = entry("primary")
        val lighter = entry("lighter")
        val custom = entry("custom")
        val download = entry("download")
        assertEquals(listOf(recommended, lighter, custom, download),
            uniqueModelEntries(listOf(recommended, lighter), listOf(lighter, recommended),
                listOf(custom, recommended), listOf(recommended, download, custom)))
    }

    private fun entry(id: String) = LocalModelEntry(id, "repo/$id", "model.gguf",
        size = 1L, params = "1B", ramFloor = 4096, contextMax = 4096,
        categories = emptyList(), toolCalling = LocalToolCalling.None, blurb = "Runs offline")
}
