package com.kaiser.rivet.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RivetDestinationTest {

    @Test
    fun ordinarySurfacesAreChatAndSettings() {
        assertEquals(
            listOf("Chat", "Settings"),
            RivetDestination.entries.map { it.name },
        )
    }
}
