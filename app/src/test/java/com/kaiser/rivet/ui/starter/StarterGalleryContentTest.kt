package com.kaiser.rivet.ui.starter

import com.kaiser.rivet.starter.Starter
import com.kaiser.rivet.starter.StarterOption
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StarterGalleryContentTest {
    @Test fun retainsCatalogOrderAndFallsBackIfACachedArchiveDisappears() {
        val world = option("world")
        val city = option("city")
        val missing = File("missing-starter-test.zip")
        val loaded = listOf(world.copy(cachedZip = missing), city, option("online").copy(cachedZip = missing))
        assertEquals(listOf(world, city), starterGalleryEntries(listOf(world, city), loaded))
        assertEquals(listOf(city, world), starterGalleryEntries(listOf(world, city), listOf(city, world)))
    }

    @Test fun usesPlainChipAndSizeCopy() {
        assertEquals("Start from a starter", STARTER_CHIP_LABEL)
        assertEquals("512 B", starterSize(512))
        assertEquals("1.5 KB", starterSize(1500))
        assertEquals("150.0 KB", starterSize(150000))
    }

    @Test fun showsTheExactFootnoteOnlyWhenNoProviderKeyIsConfigured() {
        assertEquals("Starters work with a model on this phone — no account needed.", starterFootnote(false))
        assertNull(starterFootnote(true))
        assertNull(starterFootnote(null))
    }

    private fun option(id: String) = StarterOption(Starter(id, id, "Try it", "$id.zip", 500, "Ask first"))
}
