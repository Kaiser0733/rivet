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

    @Test fun landscapeConversationUsesTheSameLaneAsItsComposer() {
        assertEquals(390f, conversationWidth(390f), 0.001f)
        assertEquals(884f, conversationWidth(884f), 0.001f)
        assertEquals(1040f, conversationWidth(1600f), 0.001f)
    }

    @Test fun wideSplitRetainsRoomForBothPanes() {
        assertEquals(0.58f, chatPaneFraction(390f, 0.9f), 0.001f)
        val space = 690f - 14f
        assertEquals(270f, (1f - chatPaneFraction(690f, 0.9f)) * space, 0.001f)
        assertEquals(270f, chatPaneFraction(690f, 0.1f) * space, 0.001f)
    }
}
