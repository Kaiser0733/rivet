package com.kaiser.rivet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RivetDestinationTest {

    @Test
    fun ordinarySurfacesAreChatAndSettings() {
        assertEquals(
            listOf("Chat", "Settings"),
            RivetDestination.entries.filter { it.isPrimary }.map { it.name },
        )
        assertEquals(false, RivetDestination.History.isPrimary)
        assertEquals(false, RivetDestination.Processes.isPrimary)
    }
}
