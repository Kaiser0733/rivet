package com.kaiser.rivet.ui.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneSettingsTest {
    @Test fun phoneAccessHasItsOwnSettingsSection() {
        val section = SettingsSection.entries.firstOrNull { it.name == "Phone" }
        assertEquals("Phone access", section?.title())
    }
}
