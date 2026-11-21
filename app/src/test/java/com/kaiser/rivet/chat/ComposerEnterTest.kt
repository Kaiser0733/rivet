package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerEnterTest {
    @Test fun onlyUnmodifiedHardwareEnterSends() {
        assertEquals(ComposerEnter.Send, composerEnter(hardware = true, shift = false, shortcutModifier = false))
        assertEquals(ComposerEnter.Newline, composerEnter(hardware = true, shift = true, shortcutModifier = false))
        assertEquals(ComposerEnter.Edit, composerEnter(hardware = false, shift = false, shortcutModifier = false))
        assertEquals(ComposerEnter.Edit, composerEnter(hardware = true, shift = false, shortcutModifier = true))
    }
}
