package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposerAttachmentTest {
    @Test fun sendingAFilesAppendsItsContextWithoutReplacingTheDraft() {
        val file = ComposerAttachment("notes.txt", image = false, fileContext = "Shared file: inbox/notes.txt")
        assertEquals("Summarize this\n\nShared file: inbox/notes.txt", file.messageText("Summarize this"))
    }

    @Test fun anImageCanSendWithOrWithoutATypedPrompt() {
        val image = ComposerAttachment("photo.jpg", image = true)
        assertEquals("What is this?", image.messageText("What is this?"))
        assertEquals("Attached image: photo.jpg", image.messageText(""))
    }
}
