package com.kaiser.rivet.browser

import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolEffect
import com.kaiser.rivet.agent.AgentToolError
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

/** Typed browser tools. browser_eval is same-origin, synchronous, and bounded. */
class BrowserTools(private val session: BrowserSession) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun prepare(
        call: AgentToolCall,
        owner: String,
        lease: BrowserDrivingLease?,
    ): PreparedAgentTool? {
        if (call.name !in NAMES) return null
        if (lease != null && !lease.tryAcquire(owner, call)) {
            return PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) {
                AgentToolResult(
                    call.id,
                    call.name,
                    AgentToolError.content("browser_busy"),
                    error = true,
                    summary = "Failed  ${call.name}",
                )
            }
        }
        return prepare(call)
    }

    suspend fun prepare(call: AgentToolCall): PreparedAgentTool? {
        if (call.name !in NAMES) return null
        return try {
            val args = runCatching { json.parseToJsonElement(call.arguments).jsonObject }
                .getOrElse { throw BrowserFailure("invalid_arguments", "The browser tool arguments are not JSON.") }
            when (call.name) {
                "browser_open" -> prepareOpen(call, args)
                "browser_eval" -> readOnly(call) { eval(call, args) }
                "browser_console" -> readOnly(call) { console(call, args) }
                "browser_key" -> prepareKey(call, args)
                "browser_read" -> readOnly(call) { read(call, args) }
                "browser_find" -> readOnly(call) { find(call, args) }
                "browser_click" -> prepareClick(call, args)
                "browser_type" -> readOnly(call) { type(call, args) }
                "browser_scroll" -> readOnly(call) { scroll(call, args) }
                "browser_back" -> readOnly(call) { back(call) }
                "browser_tap" -> prepareTap(call, args)
                "browser_swipe" -> readOnly(call) { swipe(call, args) }
                "browser_shot" -> readOnly(call) { shot(call) }
                "browser_close" -> readOnly(call) { close(call) }
                else -> null
            }
        } catch (e: BrowserFailure) {
            if (e.code == "browser_user_driving") userDrivingTool(call, e.message)
            else failed(call, e.code, e.message)
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
                    "Project preview needs preview_root and preview_entry from an open project plus the local preview server.")
        } else args.string("url")
        if (url.isBlank()) throw BrowserFailure("browser_invalid_url", "browser_open needs a URL or a preview entry.")
        return readOnly(call) {
            val preview = previewEntry.isNotBlank() || BrowserAuthGuard.isLoopback(url)
            val result = if (preview) {
                session.openVisual(url, userInitiated = false, projectPreview = true, agentAction = true)
            } else {
                session.openText(url, args.bool("new_window"), projectPreview = false)
            }
            value(call, buildJsonObject {
                put("url", result.url)
                put("title", result.title)
                put("chunks", result.chunks)
                put("needs_renderer", result.needsRenderer)
                put("tier", result.tier.name.lowercase())
                put("cap_mb", result.capMb)
                if (preview) put("visual", true)
                if (result.handoff) put("handoff", true)
            }, "Opened  ${result.title.ifBlank { result.url }}")
        }
    }

    private suspend fun prepareKey(call: AgentToolCall, args: JsonObject): PreparedAgentTool {
        val spec = try {
            BrowserKeyMapping.parse(args.string("key"), args.stringList("modifiers"))
        } catch (e: IllegalArgumentException) {
            throw BrowserFailure("invalid_arguments", e.message ?: "Unknown key.")
        }
        val selector = args.string("selector").takeIf { it.isNotBlank() }
        val action = when (args.string("action").ifBlank { "press" }) {
            "press" -> BrowserKeyAction.Press
            "down" -> BrowserKeyAction.Down
            "up" -> BrowserKeyAction.Up
            else -> throw BrowserFailure("invalid_arguments", "action must be press, down, or up.")
        }
        val hold = args.int("hold_ms") ?: 50
        if (hold !in 0..2_000) throw BrowserFailure("invalid_arguments", "hold_ms must be 0..2000.")
        val target = session.classifyKeyTarget(spec, selector)
        if (target.sensitive) {
            return failed(call, "browser_sensitive_field",
                "Rivet will not send keys to a password, one-time-code, or payment field. Use the page in your browser or take over and type it yourself.")
        }
        if (!target.found) {
            return readOnly(call) { key(call, spec, selector, action, hold) }
        }
        val riskyEnter = spec.key in setOf("Enter", " ") && target.risky
        if (!riskyEnter) return readOnly(call) { key(call, spec, selector, action, hold) }
        return PreparedAgentTool(
            call,
            AgentApprovalRequest(call, "Approve page action",
                "Press ${spec.key} on \"${target.description.take(120)}\". It looks like a submit, send, publish, pay, or delete control."),
            effect = AgentToolEffect.BrowserAction,
            basicAutonomyRisk = BasicAutonomyRisk.Elevated,
        ) {
            try { key(call, spec, selector, action, hold) }
            catch (e: BrowserFailure) {
                if (e.code == "browser_user_driving") userDriving(call, e.message)
                else failure(call, e.code, e.message)
            }
        }
    }

    private suspend fun key(
        call: AgentToolCall,
        spec: BrowserKeySpec,
        selector: String?,
        action: BrowserKeyAction,
        hold: Int,
    ): AgentToolResult {
        val result = session.pressKey(spec, selector, action, hold)
        return value(call, buildJsonObject {
            put("key", result.key)
            put("modifiers", JsonArray(result.modifiers.map(::JsonPrimitive)))
            put("action", result.action)
            put("dispatched", result.dispatched)
            put("url", result.url)
            put("changed", result.changed)
        }, "Pressed ${result.key}")
    }

    private suspend fun prepareClick(call: AgentToolCall, args: JsonObject): PreparedAgentTool {
        val selector = args.string("selector").takeIf { it.isNotBlank() }
        val text = args.string("text").takeIf { it.isNotBlank() }
        if (selector == null && text == null) {
            throw BrowserFailure("invalid_arguments", "browser_click needs a selector or visible text.")
        }
        val target = session.classifyClick(selector, text)
        if (!target.found) return readOnly(call) { targetFailure(call, "Rivet couldn't find that control.") }
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
            catch (e: BrowserFailure) {
                if (e.code == "browser_user_driving") userDriving(call, e.message)
                else failure(call, e.code, e.message)
            }
        }
    }

    private suspend fun prepareTap(call: AgentToolCall, args: JsonObject): PreparedAgentTool {
        val x = args.int("x") ?: throw BrowserFailure("invalid_arguments", "browser_tap needs x.")
        val y = args.int("y") ?: throw BrowserFailure("invalid_arguments", "browser_tap needs y.")
        if (x !in 0..1000 || y !in 0..1000) {
            throw BrowserFailure("invalid_arguments", "browser_tap coordinates are 0..1000.")
        }
        val target = session.classifyTap(x, y)
        if (!target.found) return readOnly(call) { targetFailure(call, "Nothing is at those coordinates.") }
        if (!target.risky) return readOnly(call) { tap(call, x, y) }
        return PreparedAgentTool(
            call,
            AgentApprovalRequest(call, "Approve page action",
                "Tap \"${target.description.take(120)}\". It looks like a submit, send, publish, pay, or delete control."),
            effect = AgentToolEffect.BrowserAction,
            basicAutonomyRisk = BasicAutonomyRisk.Elevated,
        ) {
            try { tap(call, x, y) }
            catch (e: BrowserFailure) {
                if (e.code == "browser_user_driving") userDriving(call, e.message)
                else failure(call, e.code, e.message)
            }
        }
    }

    private suspend fun tap(call: AgentToolCall, x: Int, y: Int): AgentToolResult {
        val before = session.currentUrl() to session.state.value.title
        try {
            val result = session.tap(x, y)
            return visualValue(call, before, result, "Tapped page")
        } finally { session.afterInteraction() }
    }

    private suspend fun swipe(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val x1 = args.int("x1") ?: 500
        val y1 = args.int("y1") ?: 800
        val x2 = args.int("x2") ?: 500
        val y2 = args.int("y2") ?: 200
        if (listOf(x1, y1, x2, y2).any { it !in 0..1000 }) {
            throw BrowserFailure("invalid_arguments", "browser_swipe coordinates are 0..1000.")
        }
        val before = session.currentUrl() to session.state.value.title
        try {
            val result = session.swipe(x1, y1, x2, y2)
            return visualValue(call, before, result, "Swiped page")
        } finally { session.afterInteraction() }
    }

    private suspend fun targetFailure(call: AgentToolCall, message: String): AgentToolResult {
        val shot = runCatching { session.screenshot() }.getOrNull()
        return AgentToolResult(call.id, call.name, buildJsonObject {
            put("error", "browser_target")
            put("detail", message)
            if (shot != null) {
                put("screenshot", shot.path)
                put("markdown", "![browser](${shot.path})")
            }
        }.toString(), true, "Failed  ${call.name}")
    }

    private fun visualValue(call: AgentToolCall, before: Pair<String, String>,
                            result: String, summary: String): AgentToolResult =
        value(call, buildJsonObject {
            put("result", result)
            put("url", session.currentUrl())
            put("changed", session.changeLine(before.first, before.second))
        }, summary)

    private suspend fun eval(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val expression = args.string("expression")
        if (expression.isBlank()) {
            throw BrowserFailure("invalid_arguments", "browser_eval needs a non-empty expression.")
        }
        if (expression.length > BrowserEvalPolicy.MAX_EXPRESSION_CHARS) {
            throw BrowserFailure("invalid_arguments",
                "expression is ${expression.length} chars; the limit is ${BrowserEvalPolicy.MAX_EXPRESSION_CHARS}.")
        }
        val timeout = args.int("timeout_ms") ?: 2_000
        if (timeout !in 100..5_000) {
            throw BrowserFailure("invalid_arguments", "timeout_ms must be 100..5000.")
        }
        val expected = args.string("expected_origin").takeIf { it.isNotBlank() }
        val result = session.eval(expression, expected, timeout.toLong())
        val payload = runCatching { org.json.JSONObject(result.content) }.getOrElse {
            throw BrowserFailure("browser_eval_exception",
                "The page returned an unreadable JS result. Retry with a simpler expression.")
        }
        if (!payload.optBoolean("ok", false)) {
            return AgentToolResult(
                call.id, call.name,
                buildJsonObject {
                    put("error", "browser_eval_exception")
                    put("origin", payload.optString("origin", result.origin))
                    put("name", payload.optString("name", "Error"))
                    put("detail", payload.optString("message", "The JS expression failed."))
                    put("stack", payload.optString("stack", "").take(2_000))
                    put("required_action",
                        "Fix the JS expression or inspect page errors with browser_console.")
                }.toString(),
                error = true,
                summary = "Failed  ${call.name}",
            )
        }
        return value(call, buildJsonObject {
            put("ok", true)
            put("origin", result.origin)
            put("type", payload.optString("type", "undefined"))
            put("value", payload.opt("value") ?: org.json.JSONObject.NULL)
            put("truncated", payload.optBoolean("truncated", false))
        }, "Evaluated browser JS")
    }

    private fun console(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val level = args.string("level").ifBlank { "all" }
        if (level !in setOf("all", "error", "warn", "log")) {
            throw BrowserFailure("invalid_arguments", "level must be all, error, warn, or log.")
        }
        val sinceId = (args.int("since_id") ?: 0).coerceAtLeast(0).toLong()
        val limit = args.int("limit") ?: 50
        if (limit !in 1..100) throw BrowserFailure("invalid_arguments", "limit must be 1..100.")
        val snapshot = session.console(level, sinceId, limit, args.bool("clear"))
        val result = snapshot.result
        return value(call, buildJsonObject {
            put("url", snapshot.url)
            put("renderer_alive", snapshot.rendererAlive)
            put("total_buffered", result.totalBuffered)
            put("error_count", result.errorCount)
            put("next_since_id", result.nextSinceId)
            put("truncated", result.truncated)
            put("entries", JsonArray(result.entries.map { entry ->
                buildJsonObject {
                    put("id", entry.id)
                    put("level", entry.level.name.lowercase())
                    put("message", entry.message)
                    put("source", entry.source)
                    put("line", entry.line)
                }
            }))
        }, "Read browser console")
    }

    private suspend fun read(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val chunk = args.int("chunk") ?: 0
        val visibleOnly = args["visible_only"]?.jsonPrimitive?.booleanOrNull ?: true
        val result = try {
            session.read(chunk, visibleOnly)
        } catch (e: BrowserFailure) {
            if (e.code != "browser_needs_renderer") throw e
            session.escalateForRead()
            session.read(chunk, visibleOnly)
        }
        return value(call, buildJsonObject {
            put("url", result.url)
            put("title", result.title)
            put("chunk", result.chunk)
            put("total", result.total)
            put("anchor", result.anchor)
            put("text", result.text)
            put("is_last", result.isLast)
            put("refs", JsonArray(result.refs.map { ref -> buildJsonObject {
                put("ref", ref.token()); put("label", ref.label); put("tag", ref.tag)
            } }))
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
        val before = session.currentUrl() to session.state.value.title
        try {
            val result = session.click(selector, text)
            return visualValue(call, before, result, "Clicked page control")
        } finally { session.afterInteraction() }
    }

    private suspend fun type(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val field = args.string("field")
        val text = args.string("text")
        if (field.isBlank()) throw BrowserFailure("invalid_arguments", "browser_type needs a field selector or name.")
        val before = session.currentUrl() to session.state.value.title
        try {
            val result = session.type(field, text)
            return visualValue(call, before, result, "Typed into page field")
        } finally { session.afterInteraction() }
    }

    private suspend fun scroll(call: AgentToolCall, args: JsonObject): AgentToolResult {
        val direction = args.string("direction").ifBlank { "down" }
        val amount = args.int("amount") ?: 1
        val before = session.currentUrl() to session.state.value.title
        try {
            val result = session.scroll(direction, amount)
            return visualValue(call, before, result, "Scrolled page")
        } finally { session.afterInteraction() }
    }

    private suspend fun back(call: AgentToolCall): AgentToolResult {
        val before = session.currentUrl() to session.state.value.title
        try {
            val url = session.back()
            return value(call, buildJsonObject {
                put("url", url); put("changed", session.changeLine(before.first, before.second))
            }, "Went back")
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
            try { action() } catch (e: BrowserFailure) {
                if (e.code == "browser_user_driving") userDriving(call, e.message)
                else failure(call, e.code, e.message)
            }
        }

    private fun failed(call: AgentToolCall, code: String, message: String) =
        PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) { failure(call, code, message) }

    private fun userDrivingTool(call: AgentToolCall, message: String) =
        PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) {
            userDriving(call, message)
        }

    private fun userDriving(call: AgentToolCall, message: String) = AgentToolResult(
        call.id, call.name,
        buildJsonObject {
            put("state", "browser_user_driving")
            put("detail", message)
        }.toString(),
        error = false, summary = "Browser paused: user driving",
    )

    private fun failure(call: AgentToolCall, code: String, message: String) = AgentToolResult(
        call.id, call.name,
        buildJsonObject { put("error", code); put("detail", message) }.toString(),
        true, "Failed  ${call.name}",
    )

    private fun value(call: AgentToolCall, value: JsonObject, summary: String) =
        AgentToolResult(call.id, call.name, value.toString(), summary = summary)

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.bool(key: String): Boolean = this[key]?.jsonPrimitive?.content?.toBoolean() == true
    private val JsonPrimitive.booleanOrNull: Boolean? get() = content.toBooleanStrictOrNull()
    private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()

    companion object {
        val NAMES = setOf("browser_open", "browser_eval", "browser_console", "browser_key", "browser_read", "browser_find", "browser_click",
            "browser_type", "browser_scroll", "browser_back", "browser_shot", "browser_close",
            "browser_tap", "browser_swipe")

        private val string = buildJsonObject { put("type", "string") }
        private val integer = buildJsonObject { put("type", "integer"); put("minimum", 0) }
        private val coordinate = buildJsonObject {
            put("type", "integer"); put("minimum", 0); put("maximum", 1000)
        }
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
            AgentToolDefinition("browser_eval",
                "Evaluate one synchronous same-origin JS expression or function body on the current page. Returns bounded JSON. Promises are not awaited; use browser_wait_for for async conditions. Refused on auth/handoff surfaces and when expected_origin differs. file:// is never allowed.",
                schema(listOf("expression"), "expression" to string,
                    "expected_origin" to string,
                    "timeout_ms" to buildJsonObject {
                        put("type", "integer"); put("minimum", 100); put("maximum", 5000)
                    })),
            AgentToolDefinition("browser_console",
                "Read the bounded browser console and failed-network buffer. Use since_id/next_since_id to page without repeating entries. Works after a renderer crash; renderer_alive=false is not an error. clear=true removes returned history after this query.",
                schema(emptyList(), "level" to string, "since_id" to integer,
                    "limit" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 100) },
                    "clear" to boolean)),
            AgentToolDefinition("browser_key",
                "Press a W3C key or combo (ArrowUp, Enter, Space, Escape, Tab, a, Ctrl+s). Focus a selector or #ref first. Password/OTP/card fields are refused; risky Enter/Space submits require approval. Uses native key events then a synthetic KeyboardEvent fallback.",
                schema(listOf("key"), "key" to string,
                    "modifiers" to buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                    },
                    "selector" to string, "action" to string,
                    "hold_ms" to buildJsonObject {
                        put("type", "integer"); put("minimum", 0); put("maximum", 2000)
                    })),
            AgentToolDefinition("browser_read",
                "Read the current page as clean chunked text in the invisible text browser. Pass chunk=0 first, then increment. Never returns the whole page at once. Hidden DOM text is excluded by default; pass visible_only=false only when the user explicitly needs it. If the page needs a DOM, Rivet starts a quiet visual session; use interaction tools to watch it.",
                schema(emptyList(), "chunk" to integer, "visible_only" to boolean)),
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
            AgentToolDefinition("browser_tap",
                "Tap a page at x,y in 0..1000 coordinates. Rivet resolves elementFromPoint first; submit/send/publish/pay/delete controls require approval. Returns URL and a short change line.",
                schema(listOf("x", "y"), "x" to coordinate, "y" to coordinate)),
            AgentToolDefinition("browser_swipe",
                "Swipe from x1,y1 to x2,y2 in 0..1000 coordinates with real touch events. Returns URL and a short change line.",
                schema(listOf("x1", "y1", "x2", "y2"), "x1" to coordinate, "y1" to coordinate,
                    "x2" to coordinate, "y2" to coordinate)),
        )
    }
}

/**
 * Run-level browser driving lease. The browser has one visual renderer, so the first
 * agent to invoke a driving action holds the lease until its loop ends; other agents
 * receive `browser_busy`. Reader-mode reads (`browser_read`, `browser_find`, and text
 * `browser_open`) share without acquiring the driving lease.
 */
class BrowserDrivingLease {
    private val lock = Any()
    private var holder: String? = null

    fun holder(): String? = synchronized(lock) { holder }

    fun isDrivingCall(call: AgentToolCall): Boolean {
        if (call.name in DRIVING_TOOLS) return true
        if (call.name == "browser_open") {
            val args = runCatching { Json.parseToJsonElement(call.arguments).jsonObject }.getOrNull()
                ?: return false
            val previewEntry = (args["preview_entry"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            val url = (args["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            return previewEntry.isNotBlank() || BrowserAuthGuard.isLoopback(url)
        }
        return false
    }

    fun tryAcquire(owner: String, call: AgentToolCall): Boolean {
        if (!isDrivingCall(call)) return true
        synchronized(lock) {
            val current = holder
            if (current == null || current == owner) {
                holder = owner
                return true
            }
            return false
        }
    }

    fun release(owner: String) {
        synchronized(lock) {
            if (holder == owner) holder = null
        }
    }

    fun clear() {
        synchronized(lock) {
            holder = null
        }
    }

    companion object {
        private val DRIVING_TOOLS = setOf(
            "browser_eval",
            "browser_key",
            "browser_click",
            "browser_type",
            "browser_scroll",
            "browser_back",
            "browser_tap",
            "browser_swipe",
            "browser_shot",
            "browser_close",
        )
    }
}
