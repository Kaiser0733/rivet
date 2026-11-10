package com.kaiser.rivet.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDrivingPolicyTest {
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
