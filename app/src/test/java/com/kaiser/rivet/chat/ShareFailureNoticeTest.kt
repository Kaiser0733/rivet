package com.kaiser.rivet.chat

import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ShareFailureNoticeTest {
    @Test fun aDeniedReadSaysItIsAboutAccess() {
        val notice = shareFailureNotice(SecurityException("Permission Denial: reading media"))
        assertFalse(notice.contains("Permission Denial"))
        assertEquals(
            "Rivet doesn't have permission to read this attachment. Share it again and allow access.",
            notice,
        )
    }

    @Test fun aVanishedAttachmentSaysItIsGone() {
        assertEquals("That attachment is no longer available. Share it again.",
            shareFailureNotice(FileNotFoundException("gone")))
    }

    @Test fun anUnknownFailureKeepsTheOldPlainMessage() {
        assertEquals("Rivet couldn't read this attachment. Try sharing it again.",
            shareFailureNotice(IllegalStateException("?")))
    }

    @Test fun anImageSharedWithoutATypeIsStillAnImage() {
        // MainActivity now asks the provider for the type when the intent has none.
        assertEquals(SharedContent.Image("content://media/external/images/media/42"),
            SharedContent.classify("android.intent.action.SEND", "image/jpeg", null,
                "content://media/external/images/media/42"))
    }
}
