package com.kaiser.rivet.ui.local

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsVerificationTextTest {
    @Test fun aVerifiedCatalogueDownloadSaysSo() {
        assertEquals("Downloaded and checksum verified", downloadVerificationText(true))
    }

    @Test fun noPublishedChecksumIsStatedPlainly() {
        assertEquals("Downloaded; this repo did not publish a checksum",
            downloadVerificationText(false))
    }
}
