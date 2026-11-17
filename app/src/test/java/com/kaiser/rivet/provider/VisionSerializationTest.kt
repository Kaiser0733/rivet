package com.kaiser.rivet.provider

import com.kaiser.rivet.agent.AgentContext
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.ContextBudget
import com.kaiser.rivet.agent.modelMessages
import com.kaiser.rivet.storage.AgentSessionCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionSerializationTest {

    private val encoded = EncodedImage("image/jpeg", "QUJD")

    private fun shotResult(path: String? = "/tmp/shot.webp") = AgentToolResult(
        callId = "call-1",
        name = "browser_shot",
        content = """{"path":"/tmp/shot.webp","mime":"image/webp","width":720,"height":1480,"vision_attached":true}""",
        imagePath = path,
        imageMime = path?.let { "image/jpeg" },
    )

    private fun toolMessage(path: String? = "/tmp/shot.webp") =
        AgentMessage.tools(listOf(shotResult(path)))

    private fun jsonText(element: kotlinx.serialization.json.JsonElement): String =
        element.jsonPrimitive.content

    @Test fun openAiCompatibleEmitsOneTrailingUserImageMessage() {
        val messages = openAiMessages(toolMessage(), mapOf("call-1" to encoded))
        assertEquals(2, messages.size)
        assertEquals("tool", messages[0]["role"]?.let(::jsonText))
        assertEquals("call-1", messages[0]["tool_call_id"]?.let(::jsonText))
        assertTrue(messages[0]["content"]?.jsonPrimitive?.isString == true)

        val user = messages[1]
        assertEquals("user", user["role"]?.let(::jsonText))
        val content = user["content"]!!.jsonArray
        assertEquals("text", content[0].jsonObject["type"]?.let(::jsonText))
        assertEquals("image_url", content[1].jsonObject["type"]?.let(::jsonText))
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            content[1].jsonObject["image_url"]?.jsonObject?.get("url")?.let(::jsonText),
        )
    }

    @Test fun anthropicToolResultCarriesTextAndImageBlocks() {
        val message = anthropicMessage(toolMessage(), mapOf("call-1" to encoded))
        val block = message["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", block["type"]?.let(::jsonText))
        val content = block["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]?.let(::jsonText))
        assertEquals("image", content[1].jsonObject["type"]?.let(::jsonText))
        assertEquals(
            "image/jpeg",
            content[1].jsonObject["source"]?.jsonObject?.get("media_type")?.let(::jsonText),
        )
        assertEquals("QUJD", content[1].jsonObject["source"]?.jsonObject?.get("data")?.let(::jsonText))
    }

    @Test fun geminiEmitsInlineDataAfterTheFunctionResponse() {
        val message = geminiMessage(toolMessage(), mapOf("call-1" to encoded))
        val parts = message["parts"]!!.jsonArray
        assertEquals(3, parts.size)
        assertTrue(parts[0].jsonObject.containsKey("functionResponse"))
        assertEquals("[Screenshot for browser_shot]", parts[1].jsonObject["text"]?.let(::jsonText))
        assertEquals(
            "image/jpeg",
            parts[2].jsonObject["inlineData"]?.jsonObject?.get("mimeType")?.let(::jsonText),
        )
        assertEquals("QUJD", parts[2].jsonObject["inlineData"]?.jsonObject?.get("data")?.let(::jsonText))
    }

    @Test fun chatGptPlanEmitsFunctionOutputThenInputImage() {
        val items = responsesInputItems(toolMessage(), mapOf("call-1" to encoded))
        assertEquals(2, items.size)
        assertEquals("function_call_output", items[0].jsonObject["type"]?.let(::jsonText))
        assertEquals("user", items[1].jsonObject["role"]?.let(::jsonText))
        val content = items[1].jsonObject["content"]!!.jsonArray
        assertEquals("input_text", content[0].jsonObject["type"]?.let(::jsonText))
        assertEquals("input_image", content[1].jsonObject["type"]?.let(::jsonText))
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            content[1].jsonObject["image_url"]?.let(::jsonText),
        )
    }

    @Test fun localIsUnsupportedAndReportsNoAttachedImage() {
        val support = VisionCapability.assess(ProviderConfig(
            id = "local",
            type = ProviderType.Local,
            name = "Local",
            baseUrl = "local://",
            model = "qwen",
        ))
        assertTrue(support is VisionSupport.Unsupported)
        val attachment = VisionCapability.shotAttachment(support, "/tmp/shot.webp")
        assertFalse(attachment.attached)
        assertNull(attachment.imagePath)
        assertNull(attachment.imageMime)
        assertTrue(attachment.note!!.contains("text-only"))
    }

    @Test fun supportedAndUnknownModelsAttachTheScreenshot() {
        val supported = VisionCapability.shotAttachment(VisionSupport.Supported, "/tmp/shot.webp")
        assertTrue(supported.attached)
        assertEquals("/tmp/shot.webp", supported.imagePath)
        assertEquals("image/jpeg", supported.imageMime)
        assertNull(supported.note)

        val unknown = VisionCapability.shotAttachment(VisionSupport.Unknown, "/tmp/shot.webp")
        assertTrue(unknown.attached)
        assertTrue(unknown.note!!.contains("if this model rejects image input"))
    }

    @Test fun modelMetadataIsParsedForVisionCapability() {
        assertEquals(true, VisionCapability.openAiVisionSupport(parse(
            """{"architecture":{"input_modalities":["text","image"]}}"""
        )))
        assertEquals(false, VisionCapability.openAiVisionSupport(parse(
            """{"architecture":{"input_modalities":["text"]}}"""
        )))
        assertEquals(true, VisionCapability.openAiVisionSupport(parse(
            """{"architecture":{"modality":"text+image"}}"""
        )))
        assertEquals(true, VisionCapability.openAiVisionSupport(parse(
            """{"capabilities":{"vision":true}}"""
        )))
        assertNull(VisionCapability.openAiVisionSupport(parse("""{"id":"plain"}""")))
    }

    @Test fun selectedModelMetadataCarriesVisionIntoTheSavedConfig() {
        val config = ProviderConfig(
            id = "p", type = ProviderType.OpenAiCompatible, name = "p",
            baseUrl = "https://x", model = "",
        )
        val selected = config.selectListedModel(ModelInfo(
            id = "vision", label = "vision", inputLimitTokens = 32_000, supportsVision = true,
        ))
        assertTrue(VisionCapability.assess(selected) is VisionSupport.Supported)
        val textOnly = config.selectListedModel(ModelInfo(
            id = "text", label = "text", inputLimitTokens = 32_000, supportsVision = false,
        ))
        assertTrue(VisionCapability.assess(textOnly) is VisionSupport.Unsupported)
    }

    @Test fun chatGptListedModelsAreVisionCapable() {
        val models = parseChatGptModels(
            """{"models":[{"slug":"gpt-modern","display_name":"Modern","visibility":"list"}]}"""
        )
        assertEquals(true, models.single().supportsVision)
    }

    @Test fun explicitMetadataFalseWinsForAnthropicAndGeminiDefaults() {
        val explicitlyTextOnly = parse("""{"capabilities":{"vision":false}}""")
        assertEquals(false, VisionCapability.metadataVisionOrTrue(explicitlyTextOnly))
        assertEquals(true, VisionCapability.metadataVisionOrTrue(parse("""{"id":"modern"}""")))
    }

    @Test fun oldContextLimitsDeserializeWithoutVisionMetadata() {
        val limit = Json.decodeFromString<ModelContextLimit>(
            """{"model":"m","baseUrl":"https://x","inputLimitTokens":1000}"""
        )
        assertNull(limit.supportsVision)
    }

    @Test fun sessionCodecKeepsOnlyAPathAndMimeForScreenshots() {
        val encodedSession = AgentSessionCodec.encode(listOf(toolMessage()))
        assertTrue(encodedSession.byteSize < 2048)
        assertFalse(encodedSession.value.contains("base64"))
        assertFalse(encodedSession.value.contains("data:image"))
        val decoded = AgentSessionCodec.decode(encodedSession.value)
        val result = decoded.single().toolResults.single()
        assertEquals("/tmp/shot.webp", result.imagePath)
        assertEquals("image/jpeg", result.imageMime)
    }

    @Test fun onlyTheTrailingToolMessageHydratesImages() {
        val old = toolMessage()
        val older = modelMessages(listOf(old, AgentMessage.user("next")))
        assertTrue(ProviderVisionAttachments.trailingImageResults(older).isEmpty())
        assertEquals(1, ProviderVisionAttachments.trailingImageResults(modelMessages(listOf(old))).size)
    }

    @Test fun pruningAnOldToolResultDropsItsImageReference() {
        val result = AgentToolResult(
            callId = "call-1",
            name = "browser_shot",
            content = """{"path":"/tmp/shot.webp","padding":"${"x".repeat(4096)}"}""",
            imagePath = "/tmp/shot.webp",
            imageMime = "image/jpeg",
        )
        val assistant = AgentMessage.assistant(
            "",
            listOf(AgentToolCall("call-1", "browser_shot", "{}")),
        )
        val pruned = AgentContext.pruneOldResults(
            listOf(AgentMessage.user("first"), assistant, AgentMessage.tools(listOf(result))),
            protectedTailBytes = 0,
            minimumSavingsBytes = 0,
            protectLatestToolGroup = false,
        )!!
        val prunedResult = pruned.single { it.role.name == "Tool" }.toolResults.single()
        assertNull(prunedResult.imagePath)
        assertNull(prunedResult.imageMime)
    }

    @Test fun contextBudgetCountsOnlyTheTrailingImage() {
        val imageRequest = agentRequest(listOf(toolMessage()))
        val textRequest = agentRequest(listOf(toolMessage(path = null)))
        assertEquals(1, ContextBudget.attachedImageCount(imageRequest.messages))
        assertEquals(0, ContextBudget.attachedImageCount(textRequest.messages))
        assertTrue(
            ContextBudget.estimateRequestTokens(imageRequest) -
                ContextBudget.estimateRequestTokens(textRequest) >= 1_024L,
        )
    }

    @Test fun imageRejectionOnlyRewrites400ErrorsThatMentionImages() {
        val rejection = VisionCapability.visionRejection(
            ProviderError.ProviderMessage("invalid content: image_url is not supported", 400),
            imagesSent = true,
        ) as ProviderError.ProviderMessage
        assertEquals(VisionCapability.IMAGE_REJECTION_MESSAGE, rejection.text)
        assertNull(VisionCapability.visionRejection(
            ProviderError.ProviderMessage("image_url is not supported", 500),
            imagesSent = true,
        ))
        assertNull(VisionCapability.visionRejection(
            ProviderError.ProviderMessage("image_url is not supported", 400),
            imagesSent = false,
        ))
    }

    private fun parse(text: String): JsonObject =
        Json.parseToJsonElement(text).jsonObject

    private fun agentRequest(messages: List<AgentMessage>) = AgentRequest(
        model = "m",
        messages = messages,
        system = "",
        reasoning = ReasoningLevel.Default,
        tools = emptyList(),
    )
}
