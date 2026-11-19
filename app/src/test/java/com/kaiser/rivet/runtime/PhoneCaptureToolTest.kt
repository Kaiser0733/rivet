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
        val supported = PhoneCaptureTool.prepare(call, { VisionSupport.Supported }) { capture }.execute()
        assertEquals(capture.path, supported.imagePath)
        assertEquals("image/png", supported.imageMime)
        assertTrue(supported.content.contains("Rivet's own screen"))

        val textOnly = PhoneCaptureTool.prepare(call, { VisionSupport.Unsupported("Text only") }) { capture }.execute()
        assertNull(textOnly.imagePath)
        assertTrue(textOnly.content.contains("\"vision_attached\":false"))
        assertTrue(textOnly.content.contains("the model can't view it"))
    }

    @Test fun denialAndDismissalReturnTheCorrelatedOutcomeAndPauseTheLoop() = runBlocking {
        for (code in listOf("phone_shot_denied", "phone_shot_cancelled")) {
            val result = PhoneCaptureTool.prepare(call, { VisionSupport.Supported }) {
                PhoneCaptureResult(error = code)
            }.execute()
            assertTrue(result.error)
            assertEquals(call.id, result.callId)
            assertNull(result.imagePath)
            assertEquals(code, AgentToolError.runtimeStopCode(result))
        }
    }
}
