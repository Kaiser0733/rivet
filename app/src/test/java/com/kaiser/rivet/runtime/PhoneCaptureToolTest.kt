package com.kaiser.rivet.runtime

import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolError
import com.kaiser.rivet.provider.VisionSupport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhoneCaptureToolTest {
    private val call = AgentToolCall("id", "phone_shot", "{}")

    @Test fun attachesPngOnlyForVisionAndLabelsRivetsOwnScreen() = runBlocking {
        val capture = PhoneCaptureResult(path = "/cache/shot.png", width = 100, height = 200, rivetVisible = true)
        val supported = PhoneCaptureTool.prepare(call, { VisionSupport.Supported }, { "shot.png" }) { capture }.execute()
        assertEquals(capture.path, supported.imagePath)
        assertEquals("image/png", supported.imageMime)
        assertTrue(supported.content.contains("Rivet's own screen"))

        val textOnly = PhoneCaptureTool.prepare(call, { VisionSupport.Unsupported("Text only") }, { "shot.png" }) { capture }.execute()
        assertNull(textOnly.imagePath)
        assertTrue(textOnly.content.contains("\"vision_attached\":false"))
        assertTrue(textOnly.content.contains("the model can't view it"))
    }

    @Test fun anUnboundCaptureCannotReturnAnUnshareableCachePath() = runBlocking {
        var captured = false
        val result = PhoneCaptureTool.prepare(call, { VisionSupport.Supported }) {
            captured = true
            PhoneCaptureResult(path = "/cache/shot.png")
        }.execute()
        assertTrue(result.error)
        assertTrue(result.content.contains("phone_shot_needs_project"))
        assertFalse(captured)
    }

    @Test fun returnsTheProjectFileAndUsesTheCaptureForVision() = runBlocking {
        val capture = PhoneCaptureResult(path = "/cache/shot.png", width = 100, height = 200)
        val result = PhoneCaptureTool.prepare(call, { VisionSupport.Supported }, saveToProject = { path ->
            assertEquals(capture.path, path)
            "screenshot-shot.png"
        }) { capture }.execute()
        assertTrue(result.content.contains("\"path\":\"screenshot-shot.png\""))
        assertEquals(capture.path, result.imagePath)
    }

    @Test fun denialAndDismissalReturnTheCorrelatedOutcomeAndPauseTheLoop() = runBlocking {
        for (code in listOf("phone_shot_denied", "phone_shot_cancelled")) {
            val result = PhoneCaptureTool.prepare(call, { VisionSupport.Supported }, { error("No image to save") }) {
                PhoneCaptureResult(error = code)
            }.execute()
            assertTrue(result.error)
            assertEquals(call.id, result.callId)
            assertNull(result.imagePath)
            assertEquals(code, AgentToolError.runtimeStopCode(result))
        }
    }
}
