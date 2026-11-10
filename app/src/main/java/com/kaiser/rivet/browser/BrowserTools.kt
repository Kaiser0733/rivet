package com.kaiser.rivet.browser

import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolEffect
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.BasicAutonomyRisk
import com.kaiser.rivet.agent.PreparedAgentTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Typed browser tools. There is deliberately no browser_eval. */
class BrowserTools(private val session: BrowserSession) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(call: AgentToolCall): PreparedAgentTool? {
        if (call.name !in NAMES) return null
        return try {
            val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }
                .getOrElse { throw BrowserFailure("invalid_arguments", "The browser tool arguments are not JSON.") }
            when (call.name) {
                "browser_open" -> prepareOpen(call, args)
                "browser_read" -> readOnly(call) { read(call, args) }
                "browser_find" -> readOnly(call) { find(call, args) }
                "browser_click" -> prepareClick(call, args)
                "browser_type" -> readOnly(call) { type(call, args) }
                "browser_scroll" -> readOnly(call) { scroll(call, args) }
                "browser_back" -> readOnly(call) { back(call) }
                "browser_shot" -> readOnly(call) { shot(call) }
                "browser_close" -> readOnly(call) { close(call) }
                else -> null
            }
        } catch (e: BrowserFailure) {
            failed(call, e.code, e.message)
        } catch (_: Exception) {
            failed(call, "browser_error", "The browser action could not be prepared.")
        }
    }

    private suspend fun prepareOpen(call: AgentToolCall, args: JsonObject): PreparedAgentTool {
        val previewEntry = args.string("preview_entry")
        val url = if (previewEntry.isNotBlank()) {
            val root = args.string("preview_root")
            session.previewStarter?.invoke(root, previewEntry)
                ?: throw BrowserFailure("browser_preview_unavailable",
                    "Project preview needs an open project and the local preview server.")
        } else args.string("url")
        if (url.isBlank()) throw BrowserFailure("browser_invalid_url", "browser_open needs a URL or a preview entry.")
        return readOnly(call) {
            val result = session.openText(url, args.bool("new_window"),
                projectPreview = previewEntry.isNotBlank())
            value(call, buildJsonObject {
                put("url", result.url)
                put("title", result.title)
                put("chunks", result.chunks)
                put("needs_renderer", result.needsRenderer)
                put("tier", result.tier.name.lowercase())
                put("cap_mb", result.capMb)
                if (result.handoff) put("handoff", true)
            }, "Opened  ${result.title.ifBlank { result.url }}")
        }
    }

    private suspend fun prepareClick(call: AgentToolCall, args: JsonObject): PreparedAgentTool {
        val selector = args.string("selector").takeIf { it.isNotBlank() }
        val text = args.string("text").takeIf { it.isNotBlank() }
        if (selector == null && text == null) {
            throw BrowserFailure("invalid_arguments", "browser_click needs a selector or visible text.")
        }
        val target = session.classifyClick(selector, text)
        if (!target.found) {
            return readOnly(call) {
                AgentToolResult(call.id, call.name,
                    """{"error":"browser_target","detail":"Rivet couldn't find that control."}""",
                    true, "Failed  ${call.name}")
            }
        }
        val risky = target.risky
        if (!risky) return readOnly(call) { click(call, selector, text) }
        return PreparedAgentTool(
            call,
            AgentApprovalRequest(call, "Approve page action",
                "Click \"${target.description.take(120)}\" on this page. It looks like a submit, send, publish, or pay control."),
            effect = AgentToolEffect.BrowserAction,
            basicAutonomyRisk = BasicAutonomyRisk.Elevated,
        ) {
            try { click(call, selector, text) }
            catch (e: BrowserFailure) { failure(call, e.code, e.message) }
        }
    }

    private suspend fun read(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val chunk = args.int("chunk") ?: 0
        val result = try {
            session.read(chunk)
        } catch (e: BrowserFailure) {
            if (e.code != "browser_needs_renderer") throw e
            session.escalateForRead()
            session.read(chunk)
        }
        return value(call, buildJsonObject {
            put("url", result.url)
            put("title", result.title)
            put("chunk", result.chunk)
            put("total", result.total)
            put("anchor", result.anchor)
            put("text", result.text)
            put("is_last", result.isLast)
        }, "Read browser chunk ${result.chunk + 1}/${result.total}")
    }

    private suspend fun find(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val query = args.string("text")
        if (query.isBlank()) throw BrowserFailure("invalid_arguments", "browser_find needs text.")
        val hits = try {
            session.find(query)
        } catch (e: BrowserFailure) {
            if (e.code != "browser_needs_renderer") throw e
            session.escalateForRead()
            session.find(query)
        }
        return value(call, buildJsonObject {
            put("query", query)
            put("hits", JsonArray(hits.map { hit -> buildJsonObject {
                put("snippet", hit.snippet); put("offset", hit.offset)
            } }))
        }, "Found ${hits.size}  in page")
    }

    private suspend fun click(call: AgentToolCall, selector: String?, text: String?): AgentToolResult {
        try {
            val result = session.click(selector, text)
            return value(call, buildJsonObject { put("result", result) }, "Clicked page control")
        } finally { session.afterInteraction() }
    }

    private suspend fun type(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val field = args.string("field")
        val text = args.string("text")
        if (field.isBlank()) throw BrowserFailure("invalid_arguments", "browser_type needs a field selector or name.")
        try {
            val result = session.type(field, text)
            return value(call, buildJsonObject { put("result", result) }, "Typed into page field")
        } finally { session.afterInteraction() }
    }

    private suspend fun scroll(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val direction = args.string("direction").ifBlank { "down" }
        val amount = args.int("amount") ?: 1
        try {
            val result = session.scroll(direction, amount)
            return value(call, buildJsonObject { put("result", result) }, "Scrolled page")
        } finally { session.afterInteraction() }
    }

    private suspend fun back(call: AgentToolCall): AgentToolResult {
        try {
            val url = session.back()
            return value(call, buildJsonObject { put("url", url) }, "Went back")
        } finally { session.afterInteraction() }
    }

    private fun close(call: AgentToolCall): AgentToolResult {
        session.endSession()
        return value(call, buildJsonObject { put("closed", true) }, "Closed visual browser")
    }

    private suspend fun shot(call: AgentToolCall): AgentToolResult {
        try {
            val shot = session.screenshot()
            return value(call, buildJsonObject {
                put("path", shot.path)
                put("mime", "image/webp")
                put("width", shot.width)
                put("height", shot.height)
            }, "Captured browser viewport")
        } finally { session.afterInteraction() }
    }

    private fun readOnly(call: AgentToolCall, action: suspend () -> AgentToolResult) =
        PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) {
            try { action() } catch (e: BrowserFailure) { failure(call, e.code, e.message) }
        }

    private fun failed(call: AgentToolCall, code: String, message: String) =
        PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) { failure(call, code, message) }

    private fun failure(call: AgentToolCall, code: String, message: String) = AgentToolResult(
        call.id, call.name,
        buildJsonObject { put("error", code); put("detail", message) }.toString(),
        true, "Failed  ${call.name}",
    )

    private fun value(call: AgentToolCall, value: JsonObject, summary: String) =
        AgentToolResult(call.id, call.name, value.toString(), summary = summary)

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.content?.toBoolean() == true
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    companion object {
        val NAMES = setOf("browser_open", "browser_read", "browser_find", "browser_click",
            "browser_type", "browser_scroll", "browser_back", "browser_shot", "browser_close")

        private val string = buildJsonObject { put("type", "string") }
        private val integer = buildJsonObject { put("type", "integer"); put("minimum", 0) }
        private val boolean = buildJsonObject { put("type", "boolean") }

        private fun schema(required: List<String>, vararg fields: Pair<String, JsonObject>) = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { fields.forEach { (name, value) -> put(name, value) } })
            put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }

        val definitions = listOf(
            AgentToolDefinition("browser_open",
                "Open a public http/https page in the invisible text browser. This never shows browser UI and never starts a renderer. For an agent-built page, pass preview_root/preview_entry instead of url to start the existing local preview server. file:// is not accepted. Sign-in and checkout surfaces stop for handoff.",
                schema(emptyList(), "url" to string, "new_window" to boolean,
                    "preview_root" to string, "preview_entry" to string)),
            AgentToolDefinition("browser_read",
                "Read the current page as clean chunked text in the invisible text browser. Pass chunk=0 first, then increment. Never returns the whole page at once. If the page needs a DOM, Rivet starts a quiet visual session; use interaction tools to watch it.",
                schema(emptyList(), "chunk" to integer)),
            AgentToolDefinition("browser_find",
                "Find text in the current page's reader text and return bounded snippets with offsets.",
                schema(listOf("text"), "text" to string)),
            AgentToolDefinition("browser_click",
                "Click a page control by CSS selector or visible text. Submit, send, publish, pay, post, delete, and form-button clicks require approval before they fire; links do not.",
                schema(emptyList(), "selector" to string, "text" to string)),
            AgentToolDefinition("browser_type",
                "Type into an ordinary page field by CSS selector or field name. Password, OTP, card, and other sensitive fields are refused; the user must type those in takeover or their own browser.",
                schema(listOf("field", "text"), "field" to string, "text" to string)),
            AgentToolDefinition("browser_scroll",
                "Scroll the rendered page up or down by one to five viewport heights.",
                schema(emptyList(), "direction" to string, "amount" to integer)),
            AgentToolDefinition("browser_back",
                "Go back in the current browser window's history.",
                schema(emptyList())),
            AgentToolDefinition("browser_shot",
                "Capture the current viewport with PixelCopy, encode it to WebP on disk, release the bitmap, and return the image path.",
                schema(emptyList())),
            AgentToolDefinition("browser_close",
                "Close the visual browser session now: save the window snapshot, destroy the renderer, and free its memory.",
                schema(emptyList())),
        )
    }
}
