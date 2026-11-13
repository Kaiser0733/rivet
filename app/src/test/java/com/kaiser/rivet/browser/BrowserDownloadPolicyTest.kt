package com.kaiser.rivet.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDownloadPolicyTest {
    @Test fun largeFileOnMeteredNetworkAsksFirst() {
        assertTrue(BrowserDownloadPolicy.asksFirst(10L * 1024 * 1024 + 1, metered = true))
        assertFalse(BrowserDownloadPolicy.asksFirst(10L * 1024 * 1024, metered = true))
        assertFalse(BrowserDownloadPolicy.asksFirst(10L * 1024 * 1024 + 1, metered = false))
    }

    @Test fun onlyHttpAndHttpsDownloadsAreAcceptedAndFilenamesAreSanitized() {
        assertTrue(BrowserDownloadPolicy.isSupportedDownloadUrl("https://example.com/file.zip"))
        assertTrue(BrowserDownloadPolicy.isSupportedDownloadUrl("HTTP://example.com/a"))
        assertFalse(BrowserDownloadPolicy.isSupportedDownloadUrl("blob:https://example.com/123"))
        assertFalse(BrowserDownloadPolicy.isSupportedDownloadUrl("data:text/plain;base64,SGVsbG8="))
        assertFalse(BrowserDownloadPolicy.isSupportedDownloadUrl("javascript:alert(1)"))
        assertFalse(BrowserDownloadPolicy.isSupportedDownloadUrl(""))

        assertEquals("report.pdf", BrowserDownloadPolicy.sanitizeFileName("../../evil/report.pdf?token=1"))
        assertEquals("download", BrowserDownloadPolicy.sanitizeFileName(".."))
        assertEquals("secret", BrowserDownloadPolicy.sanitizeFileName(".secret"))
        assertEquals("secret.txt", BrowserDownloadPolicy.sanitizeFileName("..%2F..%2Fsecret.txt"))
        assertEquals("report.pdf", BrowserDownloadPolicy.sanitizeFileName("report.pdf... "))
        assertEquals(
            "evil.pdf",
            BrowserDownloadPolicy.extractFileName(
                "attachment; filename=\"../../evil.pdf\"; modification-date=\"Wed\"",
                "https://example.com/fallback",
            ),
        )
        assertEquals(
            "report.pdf",
            BrowserDownloadPolicy.extractFileName(
                "attachment; filename*=UTF-8''..%2F..%2Freport.pdf",
                "https://example.com/fallback",
            ),
        )
    }
}
