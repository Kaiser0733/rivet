package com.kaiser.rivet.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatEntryPointsTest {
    @Test fun readyStateOffersProjectSwitchAndStartersWhenProjectIsBound() {
        val entry = readyEntryPoints(projectBound = true)
        assertEquals(CHOOSE_DIFFERENT_PROJECT_LABEL, entry?.chooseProjectLabel)
        assertTrue(requireNotNull(entry).showStarters)
    }

    @Test fun projectSwitchIsNotOfferedTwiceWhenNoProjectIsBound() {
        assertNull(readyEntryPoints(projectBound = false))
    }

    @Test fun projectSwitchLabelSaysWhatItDoesAndKeepsItsWords() {
        assertEquals("Choose a different project", CHOOSE_DIFFERENT_PROJECT_LABEL)
    }
}
