package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareIntakeTest {
    private val send = "android.intent.action.SEND"
    private val view = "android.intent.action.VIEW"
    private val uri = "content://sender/attachment"

    @Test fun imageUsesItsStreamEvenWithACaption() {
        assertEquals(SharedContent.Image(uri), SharedContent.classify(send, "image/png", "Caption", uri))
    }

    @Test fun githubLinkKeepsExactOwnerAndRepoFromSharedText() {
        val url = "https://github.com/Owner-Name/Repo.Name"
        assertEquals(SharedContent.GitHubLink("Owner-Name", "Repo.Name", url),
            SharedContent.classify(send, "text/plain", "Take a look at $url", null))
        assertNull(SharedContent.classify(view, null, null, url))
        val spoof = "https://github.com.example.org/owner/repo"
        assertEquals(SharedContent.Text(spoof), SharedContent.classify(send, "text/plain", spoof, null))
    }

    @Test fun zipKeepsExistingSendAndViewRoutes() {
        assertEquals(SharedContent.Zip(uri), SharedContent.classify(send, "application/zip", null, uri))
        assertEquals(SharedContent.Zip(uri), SharedContent.classify(view, "application/x-zip-compressed", null, uri))
    }

    @Test fun textStaysVerbatim() {
        assertEquals(SharedContent.Text("  hello\n"), SharedContent.classify(send, "text/plain", "  hello\n", null))
    }

    @Test fun unknownMimeStreamBecomesAFile() {
        assertEquals(SharedContent.File(uri, "application/octet-stream"),
            SharedContent.classify(send, null, null, uri))
    }
}
