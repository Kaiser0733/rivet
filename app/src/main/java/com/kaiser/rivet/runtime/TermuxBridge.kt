package com.kaiser.rivet.runtime

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import com.kaiser.rivet.agent.AgentApprovalRequest
import com.kaiser.rivet.agent.AgentLoop
import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolDefinition
import com.kaiser.rivet.agent.AgentToolError
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.agent.AgentWorkspaceFailure
import com.kaiser.rivet.agent.PreparedAgentTool
import com.kaiser.rivet.workspace.GrantPath
import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class TermuxBridge(context: Context) {
    private val app = context.applicationContext

    fun installed(): Boolean = try {
        @Suppress("DEPRECATION")
        app.packageManager.getApplicationInfo(TermuxCommand.PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun prepare(
        call: AgentToolCall,
        project: SafWorkspace?,
        resolveGrant: suspend (String) -> SafWorkspace,
    ): PreparedAgentTool {
        val args = runCatching {
            val json = Json.parseToJsonElement(call.arguments).jsonObject
            require(json.keys.all { it in setOf("command", "cwd", "timeout_ms") })
            val command = string(json, "command") ?: error("Command is required")
            val cwd = string(json, "cwd").orEmpty()
            val millis = (json["timeout_ms"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            require("timeout_ms" !in json || millis != null)
            buildRunCommandIntent(command, cwd)
            Arguments(command, cwd, TermuxCommand.timeout(millis))
        }.getOrNull() ?: return PreparedAgentTool(call, null, effect = com.kaiser.rivet.agent.AgentToolEffect.ReadOnly) {
            failure(call, "invalid_arguments")
        }
        return PreparedAgentTool(call, AgentApprovalRequest(call, "Run in Termux?",
            args.command + (if (args.cwd.isEmpty()) "" else "\nIn folder: ${args.cwd}") +
                "\nThis command runs with full Termux-user powers, outside Rivet's sandbox.",
            dangerous = true)) {
            val result = try {
                if (!installed()) Result(error = "termux_missing")
                else execute(args, location(args.cwd, project, resolveGrant))
            } catch (e: CancellationException) { throw e }
            catch (e: AgentWorkspaceFailure) { Result(error = e.code) }
            catch (_: WorkspaceFailure) { Result(error = "termux_cwd_unavailable") }
            catch (_: Exception) { Result(error = "termux_unavailable") }
            toolResult(call, result)
        }
    }

    suspend fun test(): String {
        if (!installed()) return requireNotNull(AgentToolError.action("termux_missing"))
        val result = execute(Arguments("echo rivet-ok", "", 10_000), Location(""))
        return result.error?.let { AgentToolError.action(it) } ?: listOf(result.stdout, result.stderr)
            .filter { it.isNotBlank() }.joinToString("\n").trim()
            .ifBlank { "Termux returned no text (exit ${result.exitCode ?: "unknown"})." }
    }

    private suspend fun location(
        cwd: String,
        project: SafWorkspace?,
        resolveGrant: suspend (String) -> SafWorkspace,
    ): Location {
        val absolute = cwd.startsWith('/')
        val route = GrantPath.parse(if (absolute) "" else cwd)
        if (route.readOnly) throw AgentWorkspaceFailure("termux_cwd_unavailable")
        val workspace = route.grantId?.let { resolveGrant(it) } ?: project
        if (workspace == null) {
            if (cwd.isEmpty() || absolute) return Location(cwd)
            throw AgentWorkspaceFailure("termux_cwd_unavailable")
        }
        val root = workspace.stat(if (absolute) WorkspacePath.ROOT else route.path)
        val localPath = if (root.directory) sharedStoragePath(workspace, root.documentId) else null
        if (!absolute && localPath == null) throw AgentWorkspaceFailure("termux_cwd_unavailable")
        val providerCanWrite = root.capabilities.create && app.contentResolver.persistedUriPermissions.any {
            it.uri == workspace.tree && it.isWritePermission
        }
        val fallback = if (localPath != null && providerCanWrite) {
            val relative = (if (absolute) WorkspacePath.ROOT else route.path)
                .child(".rivet-termux").child(UUID.randomUUID().toString())
            val toolPrefix = route.qualify(relative.value)
            OutputFiles(workspace, relative, toolPrefix, File(localPath, relative.parent().name + "/" + relative.name).path)
        } else null
        return Location(if (absolute) cwd else localPath.orEmpty(), fallback)
    }

    @Suppress("DEPRECATION")
    private fun sharedStoragePath(workspace: SafWorkspace, documentId: String): String? {
        if (workspace.tree.authority != "com.android.externalstorage.documents" ||
            !DocumentsContract.isTreeUri(workspace.tree) || !documentId.startsWith("primary:")) return null
        val path = runCatching { WorkspacePath.parse(documentId.removePrefix("primary:")) }.getOrNull() ?: return null
        return File(Environment.getExternalStorageDirectory(), path.value).path
    }

    private suspend fun execute(args: Arguments, location: Location): Result = withContext(Dispatchers.Main.immediate) {
        val fallback = location.files
        val returned = CompletableDeferred<Result>()
        val action = app.packageName + ".TERMUX_RESULT." + UUID.randomUUID()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != action) return
                val data = intent.getBundleExtra("result") ?: return
                val error = if (data.getInt("err") != 0) {
                    if (data.getString("errmsg").orEmpty().contains("allow-external-apps", ignoreCase = true))
                        "termux_external_apps_off" else "termux_command_failed"
                } else null
                val stdout = data.getString("stdout").orEmpty()
                val stderr = data.getString("stderr").orEmpty()
                returned.complete(Result(
                    stdout = stdout, stderr = stderr,
                    exitCode = data.getInt("exitCode").takeIf { data.containsKey("exitCode") },
                    truncated = data.getInt("stdout_original_length") > stdout.length ||
                        data.getInt("stderr_original_length") > stderr.length,
                    error = error,
                ))
            }
        }
        val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        val callback = PendingIntent.getBroadcast(app, 0, Intent(action).setPackage(app.packageName),
            PendingIntent.FLAG_ONE_SHOT or mutable)
        var registered = false
        try {
            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else {
                @Suppress("DEPRECATION")
                app.registerReceiver(receiver, filter)
            }
            registered = true
            val command = fallback?.let { TermuxCommand.withFileOutput(args.command, it.absolute) } ?: args.command
            val spec = buildRunCommandIntent(args.command, location.cwd)
            val intent = Intent(spec.action).setClassName(TermuxCommand.PACKAGE, spec.service)
                .putExtra("com.termux.RUN_COMMAND_PATH", spec.path)
                .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(spec.arguments.first(), command))
                .putExtra("com.termux.RUN_COMMAND_WORKDIR", spec.cwd)
                .putExtra("com.termux.RUN_COMMAND_BACKGROUND", spec.background)
                .putExtra("com.termux.RUN_COMMAND_RUNNER", "app-shell")
                .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", callback)
            if (app.startForegroundService(intent) == null) return@withContext Result(error = "termux_missing")

            val result = withTimeoutOrNull(args.timeout) {
                if (fallback == null) returned.await()
                else coroutineScope {
                    val files = async { fallback.awaitResult() }
                    try {
                        select {
                            returned.onAwait { fromTermux ->
                                if (fromTermux.error == null) fallback.result(fromTermux.exitCode) ?: fromTermux
                                else fromTermux
                            }
                            files.onAwait { it }
                        }
                    } finally { files.cancel() }
                }
            }
            result ?: (fallback?.result(null)?.copy(error = "termux_timeout")
                ?: Result(error = "termux_external_apps_off"))
        } catch (_: SecurityException) {
            Result(error = "termux_permission_required")
        } catch (_: IllegalStateException) {
            Result(error = "termux_unavailable")
        } finally {
            callback.cancel()
            if (registered) app.unregisterReceiver(receiver)
        }
    }

    private class OutputFiles(
        val workspace: SafWorkspace,
        val path: WorkspacePath,
        val toolPath: String,
        val absolute: String,
    ) {
        suspend fun awaitResult(): Result {
            while (true) {
                val code = read { workspace.readTextFile(path.child("code")).text.trim().toIntOrNull() }
                if (code != null) return result(code) ?: Result(exitCode = code)
                delay(500)
            }
        }

        suspend fun result(exitCode: Int?): Result? {
            val output = read { workspace.readTextRegion(path.child("out.txt"), 0, OUTPUT_BYTES) } ?: return null
            return Result(stdout = output.text, exitCode = exitCode, outputPath = "$toolPath/out.txt",
                truncated = output.end < output.size)
        }

        private suspend fun <T> read(block: suspend () -> T): T? =
            try { block() } catch (_: WorkspaceFailure) { null }
    }

    private data class Location(val cwd: String, val files: OutputFiles? = null)
    private data class Arguments(val command: String, val cwd: String, val timeout: Long)
    private data class Result(
        val stdout: String = "",
        val stderr: String = "",
        val exitCode: Int? = null,
        val error: String? = null,
        val outputPath: String? = null,
        val truncated: Boolean = false,
    )

    private fun failure(call: AgentToolCall, code: String) = toolResult(call, Result(error = code))

    private fun toolResult(call: AgentToolCall, result: Result): AgentToolResult {
        var limit = OUTPUT_BYTES
        var content: String
        do {
            content = buildJsonObject {
                result.error?.let { code ->
                    put("error", code)
                    AgentToolError.action(code)?.let { put("required_action", it) }
                }
                result.exitCode?.let { put("exit_code", it) }
                put("stdout", result.stdout.take(limit).dropLastWhile { it.isHighSurrogate() })
                put("stderr", result.stderr.take(limit).dropLastWhile { it.isHighSurrogate() })
                val truncated = result.truncated || result.stdout.length > limit || result.stderr.length > limit
                put("truncated", truncated)
                result.outputPath?.let {
                    put("output_path", it)
                    if (truncated) put("paging", "Use read_file with output_path and its next_offset.")
                }
            }.toString()
            limit /= 2
        } while (content.toByteArray(Charsets.UTF_8).size > AgentLoop.MAX_TOOL_RESULT_BYTES && limit > 0)
        return AgentToolResult(call.id, call.name, content, error = result.error != null,
            summary = result.error?.let(AgentToolError::action) ?: "Termux command finished")
    }

    private fun string(json: JsonObject, key: String): String? {
        val value = json[key] ?: return null
        require(value is JsonPrimitive && value.isString)
        return value.contentOrNull
    }

    companion object {
        private const val OUTPUT_BYTES = AgentLoop.MAX_TOOL_RESULT_BYTES / 4
        val definition = AgentToolDefinition(
            "termux_command",
            "Run a shell command with the Termux user's permissions outside Rivet's sandbox. " +
                "Use cwd for a project-relative, granted-folder, or absolute Termux directory. " +
                "A timeout doesn't stop the process. This command has no Rivet Undo.",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("command", buildJsonObject { put("type", "string") })
                    put("cwd", buildJsonObject { put("type", "string") })
                    put("timeout_ms", buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 600_000) })
                })
                put("required", buildJsonArray { add(JsonPrimitive("command")) })
                put("additionalProperties", false)
            },
        )
    }
}
