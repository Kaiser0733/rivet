package com.kaiser.rivet.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatLayoutTest {
    @Test fun userBubbleWidthFollowsDshProportions() {
        assertEquals(246f, userBubbleMaxWidth(300f), 0.01f)
        assertEquals(410f, userBubbleMaxWidth(500f), 0.01f)
        assertEquals(525.096f, userBubbleMaxWidth(748f), 0.01f)
        assertEquals(525.096f, userBubbleMaxWidth(1200f), 0.01f)
        assertEquals(0f, userBubbleMaxWidth(0f), 0f)
        assertEquals(0f, userBubbleMaxWidth(-10f), 0f)
    }
}
