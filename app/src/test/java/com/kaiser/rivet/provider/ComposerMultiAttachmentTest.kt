package com.kaiser.rivet.provider

import com.kaiser.rivet.chat.ComposerAttachment
import com.kaiser.rivet.chat.composerMessage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerMultiAttachmentTest {
    @Test fun bothSystemPickersReturnBatchesToTheComposer() {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val picker = root.resolve("java/com/kaiser/rivet/ui/chat/ComposerPickers.kt").readText()
        assertTrue("PickMultipleVisualMedia()" in picker)
        assertTrue("OpenMultipleDocuments()" in picker)
    }

    @Test fun sendKeepsBothImagesAndAllFileContextInOneUserMessage() {
        val first = "/inbox/first.png"
        val second = "/inbox/second.jpg"
        val accepted = composerMessage("Compare these", listOf(
            ComposerAttachment("first.png", image = true),
            ComposerAttachment("notes.txt", image = false, fileContext = "First note"),
            ComposerAttachment("second.jpg", image = true),
            ComposerAttachment("details.txt", image = false, fileContext = "Second note"),
        ), sharedImageName = null) { "/inbox/$it" }
        assertEquals(listOf(first, second), accepted.imagePaths)
        assertEquals("Compare these\n\nFirst note\n\nSecond note", accepted.text)

        val (messages, images) = ProviderVisionAttachments.prepare(listOf(accepted), VisionSupport.Supported) {
            EncodedImage("image/jpeg", if (it == first) "QUJD" else "REVG")
        }
        val sent = messages.single()
        for (wire in listOf(openAiMessages(sent, images).single(), anthropicMessage(sent, images),
            geminiMessage(sent, images), responsesInputItems(sent, images).single())) {
            val json = wire.toString()
            assertTrue(json.indexOf("QUJD") in 0 until json.indexOf("REVG"))
            assertTrue("Second note" in json)
        }
    }
}
