package com.kaiser.rivet.ui.chat

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLayoutTest {
    @Test fun historyUsesOrientationOnBothPhonesAndTablets() {
        assertFalse(showsHistoryPane(Configuration.ORIENTATION_PORTRAIT))
        assertFalse(showsHistoryPane(Configuration.ORIENTATION_UNDEFINED))
        assertTrue(showsHistoryPane(Configuration.ORIENTATION_LANDSCAPE))
    }

    @Test fun sidebarFitsCompactLandscapeAndStaysBoundedOnTablets() {
        for (width in listOf(480f, 600f, 740f, 840f, 1000f, 1280f, 1600f)) {
            val sidebar = landscapeSidebarWidth(width)
            assertTrue(sidebar <= 320f)
            assertTrue(sidebar >= minOf(220f, width * 0.4f))
            assertTrue(width - sidebar >= width * 0.6f)
        }
        assertEquals(220f, landscapeSidebarWidth(600f), 0.01f)
        assertEquals(266.4f, landscapeSidebarWidth(740f), 0.01f)
        assertEquals(320f, landscapeSidebarWidth(1280f), 0.01f)
        assertEquals(0f, landscapeSidebarWidth(0f), 0f)
    }
}
