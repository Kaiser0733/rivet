package com.kaiser.rivet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RivetDestinationTest {

    @Test
    fun tabOrderMatchesShellLayout() {
        assertEquals(
            listOf("Chat", "Files", "Changes", "Terminal"),
            RivetDestination.entries.map { it.name },
        )
    }

    @Test
    fun resourcesAreDistinctPerDestination() {
        val destinations = RivetDestination.entries
        assertEquals(destinations.size, destinations.map { it.labelRes }.toSet().size)
        assertEquals(destinations.size, destinations.map { it.emptyTextRes }.toSet().size)
        assertEquals(destinations.size, destinations.map { it.iconRes }.toSet().size)
    }
}
