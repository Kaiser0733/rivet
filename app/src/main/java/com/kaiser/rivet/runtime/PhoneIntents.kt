package com.kaiser.rivet.runtime

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolEffect
import com.kaiser.rivet.agent.AgentToolError
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.AgentWorkspaceFailure
import com.kaiser.rivet.agent.PreparedAgentTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add

internal class PhoneIntents(
    private val app: Context,
    private val resolveFile: suspend (String) -> ContentFile?,
    private val customTabOpener: (String) -> Boolean,
) {
    data class ContentFile(val uri: Uri, val mime: String, val projectFile: Boolean = false)

    private data class Launch(
        val title: String,
        val detail: String,
        val success: String,
        val failure: String,
        val execute: suspend () -> Unit,
    )

    fun prepare(call: AgentToolCall): PreparedAgentTool? {
        if (call.name !in names) return null
        val launch = try {
            if (call.arguments.length > 24 * 1024) return invalid(call)
            val args = Json.parseToJsonElement(call.arguments).jsonObject
            fun string(key: String) = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            when (call.name) {
                "open_app" -> {
                    val packageName = string("package")
                    val alias = string("alias")
                    if ((packageName == null) == (alias == null)) return invalid(call)
                    val raw = packageName ?: requireNotNull(alias)
                    val packageId = PhoneIntentSpec.packageId(raw) ?: return invalid(call)
                    Launch("Open an app?", raw, "app_open_requested", "app_not_found") {
                        start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageId))
                    }
                }
                "open_url" -> {
                    val url = string("url")?.let(PhoneIntentSpec::https) ?: return invalid(call, "invalid_url")
                    Launch("Open your browser?", url, "browser_open_requested", "app_not_found") {
                        withContext(Dispatchers.Main.immediate) {
                            if (!customTabOpener(url)) start(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                }
                "open_file" -> {
                    val path = string("path")?.takeIf { it.isNotBlank() } ?: return invalid(call)
                    Launch("Open a file in another app?", path, "file_open_requested", "no_app_for_file") {
                        openFile(resolveFile(path) ?: error("File unavailable"))
                    }
                }
                "share_out" -> {
                    val path = string("path")?.takeIf { it.isNotBlank() }
                    val text = string("text")?.takeIf { it.isNotBlank() }
                    if ((path == null) == (text == null)) return invalid(call)
                    Launch("Share", "", "share_sheet_shown", "share_unavailable") {
                        val send = Intent(Intent.ACTION_SEND)
                        if (path != null) {
                            val file = resolveFile(path) ?: error("File unavailable")
                            send.type = file.mime
                            send.putExtra(Intent.EXTRA_STREAM, file.uri)
                            send.clipData = ClipData.newRawUri("Shared file", file.uri)
                            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } else {
                            send.type = "text/plain"
                            send.putExtra(Intent.EXTRA_TEXT, text)
                        }
                        if (app.packageManager.resolveActivity(send, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                            error("No share target")
                        }
                        start(Intent.createChooser(send, null))
                    }
                }
                else -> {
                    val spec = PhoneIntentSpec.compose(
                        kind = string("kind") ?: return invalid(call),
                        to = string("to").orEmpty(), subject = string("subject"), body = string("body"),
                        title = string("title"), location = string("location"),
                        start = (args["start_ms"] as? JsonPrimitive)?.longOrNull,
                        end = (args["end_ms"] as? JsonPrimitive)?.longOrNull,
                        allDay = (args["all_day"] as? JsonPrimitive)?.booleanOrNull,
                    ) ?: return invalid(call)
                    val destination = spec.recipient?.takeIf { it.isNotBlank() }
                        ?: string("title")?.takeIf { it.isNotBlank() }
                    Launch("Prepare ${spec.label}?", destination.orEmpty(), "compose_open_requested",
                        "compose_unavailable") { start(composeIntent(spec)) }
                }
            }
        } catch (_: IllegalArgumentException) {
            return invalid(call)
        }
        val approval = if (call.name == "share_out") null else AgentApprovalRequest(call, launch.title, launch.detail)
        return PreparedAgentTool(call, approval) {
            try {
                launch.execute()
                AgentToolResult(call.id, call.name, buildJsonObject { put("status", launch.success) }.toString(),
                    summary = launch.title.removeSuffix("?"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AgentWorkspaceFailure) {
                failed(call, e.code)
            } catch (_: Exception) {
                failed(call, launch.failure)
            }
        }
    }

    private suspend fun openFile(file: ContentFile) = withContext(Dispatchers.Main.immediate) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val handler = app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
        val packageName = handler?.packageName?.takeIf { file.projectFile && it != "android" }
        packageName?.let { app.grantUriPermission(it, file.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        try {
            start(intent)
        } catch (e: Exception) {
            packageName?.let { app.revokeUriPermission(it, file.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            throw e
        }
    }

    private suspend fun start(intent: Intent) = withContext(Dispatchers.Main.immediate) {
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun composeIntent(spec: ComposePhoneIntent): Intent {
        val uri = spec.scheme?.let {
            if (spec.recipient.isNullOrEmpty()) Uri.parse("$it:") else Uri.fromParts(it, spec.recipient, null)
        } ?: spec.data?.let(Uri::parse)
        return Intent(spec.action, uri).apply {
            for ((key, value) in spec.extras) {
                when (value) {
                    is String -> putExtra(key, value)
                    is Long -> putExtra(key, value)
                    is Boolean -> putExtra(key, value)
                }
            }
        }
    }

    private fun invalid(call: AgentToolCall, code: String = "invalid_arguments") =
        PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) { failed(call, code) }

    private fun failed(call: AgentToolCall, code: String) =
        AgentToolResult(call.id, call.name, AgentToolError.content(code), error = true, summary = "Could not open phone action")

    companion object {
        private val string = buildJsonObject { put("type", "string") }
        private val integer = buildJsonObject { put("type", "integer") }
        private val boolean = buildJsonObject { put("type", "boolean") }

        private fun schema(required: List<String>, vararg properties: Pair<String, JsonObject>) = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { properties.forEach { (key, value) -> put(key, value) } })
            put("required", buildJsonArray { required.forEach { add(it) } })
        }

        val definitions = listOf(
            AgentToolDefinition("open_app", "Open a phone app by package ID or common name.",
                schema(emptyList(), "package" to string, "alias" to string)),
            AgentToolDefinition("open_url", "Open an HTTPS URL in the user's browser.",
                schema(listOf("url"), "url" to string)),
            AgentToolDefinition("open_file", "Open a project or inbox file in an Android app.",
                schema(listOf("path"), "path" to string)),
            AgentToolDefinition("share_out", "Show Android's share sheet for one text or project/inbox file.",
                schema(emptyList(), "text" to string, "path" to string)),
            AgentToolDefinition("compose_intent", "Prepare a draft in the phone app. Kind must be email, message, or event. Use to, subject, and body for drafts, or title, location, body, start_ms, end_ms, and all_day for calendar. Times are milliseconds since the Unix epoch. The user sends or saves it.",
                schema(listOf("kind"), "kind" to string, "to" to string, "subject" to string, "body" to string,
                    "title" to string, "location" to string, "start_ms" to integer, "end_ms" to integer,
                    "all_day" to boolean)),
        )
        val names = definitions.map { it.name }.toSet()
    }
}
