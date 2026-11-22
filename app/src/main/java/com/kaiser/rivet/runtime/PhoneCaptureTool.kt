package com.kaiser.rivet.runtime

import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolError
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.PreparedAgentTool
import com.kaiser.rivet.provider.VisionCapability
import com.kaiser.rivet.provider.VisionSupport
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object PhoneCaptureTool {
    fun prepare(
        call: AgentToolCall,
        vision: () -> VisionSupport,
        saveToProject: (suspend (String) -> String)? = null,
        capture: suspend () -> PhoneCaptureResult,
    ) = PreparedAgentTool(call, null) {
        val args = runCatching { Json.parseToJsonElement(call.arguments) }.getOrNull()
        if (args != buildJsonObject {}) return@PreparedAgentTool failure(call, "invalid_arguments")
        val result = capture()
        val path = result.path ?: return@PreparedAgentTool failure(call, result.error ?: "phone_shot_unavailable")
        val savedPath = try { saveToProject?.invoke(path) ?: path }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return@PreparedAgentTool failure(call, "phone_shot_save_failed") }
        val attachment = VisionCapability.shotAttachment(vision(), path)
        val label = if (result.rivetVisible) "Captured Rivet's own screen." else "Captured the phone screen."
        val content = buildJsonObject {
            put("path", savedPath)
            put("mime", "image/png")
            put("width", result.width)
            put("height", result.height)
            put("description", label)
            put("vision_attached", attachment.attached)
            if (!attachment.attached) put("vision_note", "This model has no confirmed image support. The screenshot is saved but the model can't view it.")
        }
        AgentToolResult(call.id, call.name, content.toString(), summary = label,
            imagePath = attachment.imagePath, imageMime = if (attachment.attached) "image/png" else null)
    }

    private fun failure(call: AgentToolCall, code: String) =
        AgentToolResult(call.id, call.name, AgentToolError.content(code), error = true,
            summary = AgentToolError.action(code) ?: "Screen capture failed.")

    val definition = AgentToolDefinition(
        "phone_shot",
        "Request one phone screenshot. Android asks for consent every time. When armed, wait for the user to tap Capture now in Rivet's notification or cancel. Only a model with vision sees the image.",
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("additionalProperties", false)
        },
    )
}
