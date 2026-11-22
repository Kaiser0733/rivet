package com.kaiser.rivet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDrivingPolicyTest {
    @Test fun aPageInReaderModeShowsInItsOwningChatUntilClosed() {
        val opening = BrowserUiState(
            watchSessionId = "chat-a",
            chatPage = BrowserChatPage("https://example.org"),
            chatBrowserWorking = true,
        )
        assertFalse(opening.rendererVisible)
        assertTrue(opening.chatPreviewVisible("chat-a"))
        assertFalse(opening.chatPreviewVisible("chat-b"))
        assertTrue(opening.copy(chatBrowserWorking = false).chatPreviewVisible("chat-a"))
        assertFalse(opening.copy(visible = true).chatPreviewVisible("chat-a"))
        assertFalse(opening.copy(handoff = BrowserHandoffState("https://example.org", "identity"))
            .chatPreviewVisible("chat-a"))
        assertFalse(opening.copy(chatPage = null).chatPreviewVisible("chat-a"))
    }

    @Test fun aManualOpenHasNoDrivingBannerOrChatCard() {
        assertFalse(BrowserDrivingPolicy.bannerVisible(false, false, BrowserDriving.User))
        assertFalse(BrowserDrivingPolicy.cardVisible(false, false, true, false))
    }

    @Test fun aRunThatNeverWentVisualHasNoBannerOrCard() {
        assertFalse(BrowserDrivingPolicy.bannerVisible(true, false, BrowserDriving.User))
        assertFalse(BrowserDrivingPolicy.cardVisible(true, false, true, false))
    }

    @Test fun onlyAnActiveAgentVisualRunShowsTheBannerAndCard() {
        assertTrue(BrowserDrivingPolicy.bannerVisible(true, true, BrowserDriving.User))
        assertTrue(BrowserDrivingPolicy.cardVisible(true, true, true, false))
        assertFalse(BrowserDrivingPolicy.cardVisible(true, true, true, true))
    }

    @Test fun theAgentCannotActWhileTheUserDrives() {
        assertFalse(BrowserDrivingPolicy.mayAgentAct(true, true, BrowserDriving.User))
        assertTrue(BrowserDrivingPolicy.mayAgentAct(true, true, BrowserDriving.Agent))
        assertTrue(BrowserDrivingPolicy.mayAgentAct(false, false, BrowserDriving.User))
    }
}
