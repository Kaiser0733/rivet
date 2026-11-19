package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedImageVisionTest {
    private val path = "/cache/shared-in/inbox/crash.png"
    private val shared = listOf(AgentMessage.user("what's wrong here?").copy(userImagePath = path))

    @Test fun sharedImageIsEncodedIntoTheOpeningUserMessageForEveryCloudWireFormat() {
        val (messages, images) = ProviderVisionAttachments.prepare(shared, VisionSupport.Supported) {
            assertEquals(path, it)
            EncodedImage("image/jpeg", "QUJD")
        }

        val message = messages.single()
        for (wire in listOf(openAiMessages(message, images).single(), anthropicMessage(message, images),
                geminiMessage(message, images), responsesInputItems(message, images).single())) {
            assertTrue(wire.toString().contains("QUJD"))
            assertTrue(wire.toString().contains("what's wrong here?"))
        }
    }

    @Test fun textOnlyModelGetsAnInboxPathAndNeverEncodesTheImage() {
        val (messages, images) = ProviderVisionAttachments.prepare(shared, VisionSupport.Unsupported("Text only")) {
            throw AssertionError("A text-only model must never encode the shared image")
        }

        val wire = openAiMessages(messages.single(), images).single().toString()
        assertTrue(wire.contains("inbox/crash.png"))
        assertTrue(wire.contains("cannot view images"))
        assertFalse(wire.contains("image_url"))
    }

    @Test fun anUnavailableImageLeavesTheOpeningTextAndAPlainNote() {
        val (messages, images) = ProviderVisionAttachments.prepare(shared, VisionSupport.Supported) { null }
        assertTrue(images.isEmpty())
        assertTrue(messages.single().text.contains("Shared image no longer available."))
        assertTrue(messages.single().text.startsWith("what's wrong here?"))
    }
}
