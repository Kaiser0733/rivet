package com.kaiser.rivet.ui.local

import com.kaiser.rivet.local.DownloadPolicy
import com.kaiser.rivet.local.NetworkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkAllowLabelTest {
    @Test fun aWaitingCellularDownloadOffersMobileData() {
        assertEquals("Use mobile data", networkAllowLabel(DownloadPolicy.networkWait(NetworkKind.Cellular)))
    }

    @Test fun aMeteredWifiDownloadOffersThatWifi() {
        assertEquals("Use this Wi-Fi", networkAllowLabel(DownloadPolicy.networkWait(NetworkKind.Wifi)))
    }

    @Test fun anotherMeteredNetworkStillHasAButton() {
        assertEquals("Use this network", networkAllowLabel(DownloadPolicy.networkWait(NetworkKind.Other)))
    }

    @Test fun aMissingNetworkHasNoOverrideButton() {
        assertNull(networkAllowLabel(null))
        assertNull(networkAllowLabel("No network right now. Connect and resume."))
    }
}
