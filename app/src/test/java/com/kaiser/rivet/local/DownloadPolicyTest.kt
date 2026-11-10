package com.kaiser.rivet.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPolicyTest {
    @Test
    fun storageGuardKeepsOneGigabyteFree() {
        val remaining = 2_500_000_000L
        assertTrue(DownloadPolicy.refusal(3_000_000_000L, remaining) != null)
        assertNull(DownloadPolicy.refusal(4_000_000_000L, remaining))
    }

    @Test
    fun storageWarningStartsBelowTwoAndAHalfGigabytes() {
        assertTrue(DownloadPolicy.warning(3_000_000_000L, 1_000_000_000L) != null)
        assertNull(DownloadPolicy.warning(8_000_000_000L, 1_000_000_000L))
    }

    @Test
    fun wifiIsTheDefaultOnlyForLargeFiles() {
        assertTrue(DownloadPolicy.wifiOnlyByDefault(2_500_000_000L))
        assertTrue(!DownloadPolicy.wifiOnlyByDefault(400_000_000L))
    }

    @Test
    fun meteredWifiIsNeverCalledMobileData() {
        val wifi = DownloadPolicy.meteredWarning(NetworkKind.Wifi, 1_100_000_000L)
        assertTrue(wifi.contains("Wi-Fi is set as metered"))
        assertTrue(!wifi.contains("mobile data", ignoreCase = true))
        assertTrue(DownloadPolicy.meteredWarning(NetworkKind.Cellular, 1_100_000_000L)
            .contains("mobile data"))
        assertTrue(DownloadPolicy.networkWait(NetworkKind.Wifi)
            .contains("Wi-Fi is set as metered"))
    }

    @Test
    fun etagChecksumsAreOnlyAcceptedWhenTheyLookLikeSha256() {
        val hash = "a".repeat(64)
        assertEquals(hash, DownloadPolicy.checksumFromEtag("\"$hash\""))
        assertNull(DownloadPolicy.checksumFromEtag("W/\"abc\""))
        assertNull(DownloadPolicy.checksumFromEtag("\"$hash-and-more\""))
    }
}
