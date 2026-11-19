package com.kaiser.rivet.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class GrantLabelTest {
    @Test fun theTreeUriBecomesTheFolderTheUserPicked() {
        assertEquals("Download/rivet-test", grantDisplayPath(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload%2Frivet-test",
            "rivet-test"))
        assertEquals("Download/rivet-test", grantDisplayPath(
            "content://com.android.externalstorage.documents/tree/primary%3ADownload%2Frivet-test/document/primary%3ADownload%2Frivet-test",
            "rivet-test"))
    }

    @Test fun aVolumeRootFallsBackToTheStoredLabel() {
        assertEquals("Download", grantDisplayPath(
            "content://com.android.externalstorage.documents/tree/primary%3A", "Download"))
        assertEquals("Folder", grantDisplayPath("not-a-tree-uri", "Folder"))
    }
}
