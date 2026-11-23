package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolExecutor
import com.kaiser.rivet.agent.AgentToolExecutorTest.FakeWorkspace
import com.kaiser.rivet.agent.ProjectImageInput
import com.kaiser.rivet.agent.ProjectImageRead
import com.kaiser.rivet.ui.chat.streamingAssistantText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectImageVisionTest {
    private val image = ProjectImageInput("shots/example.png", "/cache/example")

    @Test fun readAndPresentExposeTheProjectImageAsVisionInput() = runTest {
        val executor = AgentToolExecutor(FakeWorkspace(), readProjectImage = {
            assertEquals(image.path, it)
            ProjectImageRead(true, "Attached for vision.", image)
        })
        val read = AgentToolCall("read", "read_image", """{"path":"shots/example.png"}""")
        val present = AgentToolCall("deliver", "present",
            """{"files":[{"path":"shots/example.png","description":"Example"}]}""")
        for (call in listOf(read, present)) {
            val result = executor.prepare(call).execute()
            assertFalse(result.error)
            assertEquals(listOf(image), result.projectImages)
            val tools = AgentMessage.tools(listOf(result))
            val messages = listOf(AgentMessage.user("Describe the project image"),
                AgentMessage.assistant("", listOf(call)), tools)
            val (_, encoded) = ProviderVisionAttachments.prepare(messages, VisionSupport.Supported) {
                assertEquals(image.cachePath, it)
                EncodedImage("image/jpeg", "QUJD")
            }
            for (wire in listOf(openAiMessages(tools, encoded).last(), responsesInputItems(tools, encoded).last(),
                anthropicMessage(tools, encoded), geminiMessage(tools, encoded))) {
                assertTrue(wire.toString().contains(image.path))
                assertTrue(wire.toString().contains("QUJD"))
            }
        }
    }

    @Test fun textOnlyModelGetsAPlainFailureInsteadOfImageBytes() = runTest {
        val read = AgentToolCall("read", "read_image", """{"path":"shots/example.png"}""")
        val executor = AgentToolExecutor(FakeWorkspace(), readProjectImage = {
            ProjectImageRead(true, "This model cannot view images.")
        })
        val result = executor.prepare(read).execute()
        assertTrue(result.content.contains("This model cannot view images."))
        assertTrue(result.projectImages.isEmpty())
    }

    @Test fun streamingNeverShowsACompleteOrPartiallyReceivedImageReference() {
        val reference = "![Example](<shots/example file.png>)"
        for (length in 1..reference.length) {
            assertEquals("See this\n", streamingAssistantText("See this\n" + reference.take(length)))
        }
        assertEquals("See this\n\nDone.", streamingAssistantText("See this\n$reference\nDone."))
    }
}
