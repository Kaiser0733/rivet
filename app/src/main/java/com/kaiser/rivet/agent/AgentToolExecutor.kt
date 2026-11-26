package com.kaiser.rivet.agent

import com.kaiser.rivet.runtime.MirrorFailure
import com.kaiser.rivet.runtime.RuntimeCommandResult
import com.kaiser.rivet.runtime.RepositoryDiff
import com.kaiser.rivet.runtime.RepositoryStatus
import com.kaiser.rivet.runtime.ManagedProcessStatus
import com.kaiser.rivet.runtime.ManagedProcessKind
import com.kaiser.rivet.runtime.ManagedProcesses
import com.kaiser.rivet.runtime.CommandOutputCache
import com.kaiser.rivet.runtime.CommandOutputSlicer
import com.kaiser.rivet.runtime.BackgroundLaunch
import com.kaiser.rivet.runtime.ProcessOutputSnapshot
import com.kaiser.rivet.runtime.PreviewPaths
import com.kaiser.rivet.runtime.PreviewLaunch
import com.kaiser.rivet.runtime.ProjectDownloader
import com.kaiser.rivet.runtime.DownloadFailure
import com.kaiser.rivet.workspace.GrantPath
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspaceStructuralStamp
import java.io.InputStream
import java.io.FileInputStream
import kotlinx.coroutines.CancellationException
import com.kaiser.rivet.runtime.PhoneCaptureTool
import com.kaiser.rivet.runtime.PhoneIntents
import com.kaiser.rivet.runtime.TermuxBridge
import com.kaiser.rivet.subagent.FileScopeRegistry
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class AgentWorkspaceEntry(val path: String, val directory: Boolean, val size: Long?)
data class AgentFileSnapshot(val path: String, val text: String, val sha256: String, val size: Long)
// `start`/`end` are absolute byte offsets in the document, so a caller can resume
// from `end` and can tell a mid-character offset from a real one.
data class AgentFileRegion(val path: String, val text: String, val sha256: String, val size: Long,
                           val start: Long, val end: Long)
data class AgentCreatedFile(val path: String, val sha256: String? = null, val size: Long? = null,
    val inspectionError: String? = null)
data class AgentSearchHit(val path: String, val line: Int?, val context: String)
data class AgentSearchReport(
    val hits: List<AgentSearchHit>,
    val limited: Boolean,
    val filesScanned: Int,
    val entriesVisited: Int,
    val bytesScanned: Long,
    val skipped: Int,
)
data class AgentTextEdit(val oldText: String, val newText: String)

class AgentWorkspaceFailure(val code: String) : Exception(code)

interface AgentWorkspace {
    suspend fun stat(path: String): AgentWorkspaceEntry
    suspend fun observeStructural(path: String, recursive: Boolean): WorkspaceStructuralStamp
    suspend fun list(path: String): List<AgentWorkspaceEntry>
    suspend fun read(path: String): AgentFileSnapshot
    // Bounded region read so a document larger than Rivet's in-memory edit bound can
    // still be paged by offset. Implementations that can stream override this; the
    // default keeps the whole-file behaviour for everyone else.
    suspend fun readRegion(path: String, offset: Long, maxBytes: Int): AgentFileRegion {
        val file = read(path)
        val bytes = file.text.toByteArray(Charsets.UTF_8)
        val size = bytes.size.toLong()
        // Advance past a partial character instead of silently serving from the middle
        // of one. The caller sees start != offset and reports invalid_offset.
        var start = offset.coerceAtMost(size).toInt()
        while (start < bytes.size && bytes[start].toInt() and 0xC0 == 0x80) start++
        var end = minOf(start + maxBytes, bytes.size)
        while (end > start && end < bytes.size && bytes[end].toInt() and 0xC0 == 0x80) end--
        if (end < start) end = start
        return AgentFileRegion(path, bytes.copyOfRange(start, end).toString(Charsets.UTF_8),
            file.sha256, size, start.toLong(), end.toLong())
    }
    suspend fun search(path: String, query: String): AgentSearchReport
    suspend fun write(path: String, content: String, expectedHash: String): AgentFileSnapshot
    suspend fun writeDownloaded(path: String, input: InputStream, expectedExistingHash: String?): AgentFileSnapshot =
        throw AgentWorkspaceFailure("unsupported")
    suspend fun patch(path: String, expectedHash: String, edits: List<AgentTextEdit>): AgentFileSnapshot
    suspend fun createFile(path: String): AgentCreatedFile
    // Creates missing parent directories first. The default keeps the old
    // single-file behaviour for implementations that do not model parents.
    suspend fun createFileWithParents(path: String): AgentCreatedFile = createFile(path)
    suspend fun createDirectory(path: String): AgentWorkspaceEntry
    suspend fun rename(path: String, newName: String, approved: WorkspaceStructuralStamp): AgentWorkspaceEntry
    suspend fun move(path: String, destination: String, approvedSource: WorkspaceStructuralStamp,
                     approvedDestination: WorkspaceStructuralStamp): AgentWorkspaceEntry
    suspend fun delete(path: String, approved: WorkspaceStructuralStamp)
}

class AgentToolExecutor(
    private val workspace: AgentWorkspace,
    private val runCommand: (suspend (String, String, Long) -> RuntimeCommandResult)? = null,
    private val gitStatus: (suspend () -> RepositoryStatus)? = null,
    private val gitDiff: (suspend (String) -> RepositoryDiff)? = null,
    private val managedProcesses: ManagedProcesses? = null,
    private val startPreview: (suspend (String, String) -> PreviewLaunch)? = null,
    private val downloader: ProjectDownloader? = null,
    private val currentWorkspaceIdentity: suspend () -> String? = { null },
    // The turn's skill catalog snapshot; null when no project session is open.
    private val skills: SkillCatalog? = null,
    // Skills a `/name` gesture already loaded for this turn; the tool must not repeat them.
    private val loadedSkills: Set<String> = emptySet(),
    // Durable project memory writes; null when no project is open. Refusals are tool errors.
    private val rememberMemory: (suspend (String) -> MemoryFormat.Append)? = null,
    private val readCommandOutput: (suspend (String, String?) -> CommandOutputCache.CachedOutput?)? = null,
    private val readProcessOutput: (suspend (String, String?, Int?, Int?, String?, Int?) -> ProcessOutputSnapshot?)? = null,
    private val spawnProcess: (suspend (String, String, Long, Long, String?) -> BackgroundLaunch)? = null,
    private val worker: Boolean = false,
    private val preparePhoneShot: ((AgentToolCall) -> PreparedAgentTool)? = null,
    private val prepareTermuxCommand: ((AgentToolCall) -> PreparedAgentTool)? = null,
    private val preparePhoneIntent: ((AgentToolCall) -> PreparedAgentTool?)? = null,
    private val readProjectImage: (suspend (String) -> ProjectImageRead)? = null,
) {
    suspend fun prepare(call: AgentToolCall): PreparedAgentTool {
        if (call.arguments.toByteArray().size > MAX_ARGUMENT_BYTES) return invalid(call, "arguments_too_large")
        if (reservedGoalMutation(call)) return invalid(call, "reserved_goal")
        if (reservedMemoryMutation(call)) return invalid(call, "reserved_memory")
        return try {
            when (call.name) {
                "git_status" -> {
                    val args = json.decodeFromString<GitStatusArgs>(call.arguments)
                    val path = path(args.path, root = true)
                    readOnly(call) {
                        val status = if (workspace is RoutedAgentWorkspace && GrantPath.isExternal(path)) {
                            GrantPath.parse(path)
                            RepositoryStatus(present = false)
                        } else gitStatus?.invoke() ?: throw AgentWorkspaceFailure("runtime_unavailable")
                        success(call, boundedGitStatus(status), "Git status")
                    }
                }
                "git_diff" -> {
                    val args = json.decodeFromString<GitDiffArgs>(call.arguments)
                    val path = path(args.path, root = true)
                    readOnly(call) {
                        val diff = if (workspace is RoutedAgentWorkspace && GrantPath.isExternal(path)) {
                            GrantPath.parse(path)
                            RepositoryDiff(present = false, text = "", limited = false, files = 0)
                        } else gitDiff?.invoke(path) ?: throw AgentWorkspaceFailure("runtime_unavailable")
                        var text = diff.text
                        var value = gitDiffValue(diff, text)
                        while (value.toString().toByteArray(Charsets.UTF_8).size > AgentLoop.MAX_TOOL_RESULT_BYTES) {
                            text = text.take(text.length / 2).dropLastWhile { it.isHighSurrogate() }
                            value = gitDiffValue(diff, text, extraLimit = true)
                        }
                        success(call, value, "Git diff")
                    }
                }
                "list_directory" -> {
                    val args = json.decodeFromString<ListArgs>(call.arguments)
                    val path = path(args.path, root = true)
                    readOnly(call) {
                        val all = workspace.list(path)
                        val entries = all.take(MAX_LIST_ENTRIES)
                        success(call, buildJsonObject {
                            put("path", path)
                            put("entries", buildJsonArray {
                                entries.forEach { entry -> add(buildJsonObject {
                                    put("name", entry.path.substringAfterLast('/'))
                                    put("path", entry.path)
                                    put("type", if (entry.directory) "directory" else "file")
                                    if (!entry.directory) entry.size?.let { put("size", it) }
                                }) }
                            })
                            put("limited", all.size > entries.size)
                        }, "Listed  ${path.ifEmpty { "." }}")
                    }
                }
                "read_file" -> {
                    val args = readFileArguments(call.arguments)
                    val path = path(args.path)
                    readOnly(call) {
                        var length = MAX_READ_CHUNK_BYTES
                        var region = workspace.readRegion(path, args.offset, length)
                        if (args.offset > region.size || region.start != args.offset) {
                            throw AgentWorkspaceFailure("invalid_offset")
                        }
                        var value = readRegionValue(path, region)
                        while (value.toString().toByteArray(Charsets.UTF_8).size > AgentLoop.MAX_TOOL_RESULT_BYTES) {
                            if (length <= 1) throw AgentWorkspaceFailure("output_limit")
                            length /= 2
                            region = workspace.readRegion(path, args.offset, length)
                            if (region.start != args.offset) throw AgentWorkspaceFailure("invalid_offset")
                            value = readRegionValue(path, region)
                        }
                        success(call, value, "Read  $path")
                    }
                }
                "read_image" -> {
                    val read = readProjectImage ?: return invalid(call, "unknown_tool")
                    val path = presentablePath(readFileArguments(call.arguments).path).value
                    readOnly(call) {
                        val result = read(path)
                        success(call, buildJsonObject {
                            put("path", path)
                            put("image_input", result.note)
                        }, "Read image $path").copy(projectImages = listOfNotNull(result.input))
                    }
                }
                "present" -> {
                    if (worker) return invalid(call, "unknown_tool")
                    val args = json.decodeFromString<PresentArguments>(call.arguments)
                    readOnly(call) {
                        val files = try {
                            verifyPresentedFiles(args.files, workspace::stat)
                        } catch (error: PresentationFailure) {
                            return@readOnly failure(call, error.code, subject = error.path)
                        } catch (_: IllegalArgumentException) {
                            return@readOnly failure(call, "invalid_arguments")
                        }
                        val images = readProjectImage?.let { read ->
                            files.mapNotNull { file -> read(file.path).takeIf { it.isImage }?.let { file.path to it } }
                        }.orEmpty()
                        success(call, buildJsonObject {
                            put("presented", files.size)
                            if (images.isNotEmpty()) put("images", buildJsonArray {
                                images.forEach { (path, result) -> add(buildJsonObject {
                                    put("path", path)
                                    put("image_input", result.note)
                                }) }
                            })
                        },
                            "Presented ${files.size} file${if (files.size == 1) "" else "s"}")
                            .copy(presentedFiles = files, projectImages = images.mapNotNull { it.second.input })
                    }
                }
                "search_files" -> {
                    val args = json.decodeFromString<SearchArgs>(call.arguments)
                    val path = path(args.path, root = true)
                    require(args.query.isNotEmpty() && args.query.length <= 256)
                    readOnly(call) {
                        val report = workspace.search(path, args.query)
                        success(call, boundedSearchValue(report), "Searched  \"${args.query.take(48)}\"")
                    }
                }
                // Returns catalog text only, so no workspace read or approval is needed.
                "skill" -> {
                    val args = json.decodeFromString<SkillArgs>(call.arguments)
                    require(args.name.isNotBlank() && args.name.length <= 64)
                    PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) {
                        execute(call) { skillToolResult(call, skills, args.name, loadedSkills) }
                    }
                }
                "remember" -> {
                    val args = json.decodeFromString<RememberArgs>(call.arguments)
                    val fact = args.fact.trim()
                    require(fact.isNotEmpty() && fact.length <= MemoryFormat.MAX_FACT_CHARS)
                    val writer = rememberMemory ?: throw AgentWorkspaceFailure("unsupported")
                    val before = try { workspace.read(MemoryFormat.PATH).text }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { "" }
                    val detail = when (val preview = MemoryFormat.append(before, fact)) {
                        is MemoryFormat.Append.Saved ->
                            "Update project memory\n\n" + MemoryFormat.diff(before, preview.markdown)
                        is MemoryFormat.Append.Refused -> "Update project memory\n\n$fact"
                    }
                    mutation(call, "Update project memory", detail, fact.length * 2 + 512,
                        memoryFact = fact) {
                        when (val result = writer(fact)) {
                            is MemoryFormat.Append.Saved -> success(call, buildJsonObject {
                                put("lines", result.lines)
                                put("garden", result.garden)
                            }, "Memory updated")
                            is MemoryFormat.Append.Refused -> failure(call, result.code)
                        }
                    }
                }
                "write_file" -> {
                    val args = json.decodeFromString<WriteArgs>(call.arguments)
                    val path = path(args.path); hash(args.expectedSha256)
                    mutation(call, "Edit", path, path.length, modelRequestsApproval = args.askUser) {
                        snapshot(call, workspace.write(path, args.content, args.expectedSha256), "Edited  $path")
                    }
                }
                "apply_patch" -> {
                    val args = json.decodeFromString<PatchArgs>(call.arguments)
                    val path = path(args.path); hash(args.expectedSha256)
                    require(args.edits.isNotEmpty() && args.edits.size <= 100)
                    val edits = args.edits.map { require(it.oldText.isNotEmpty()); AgentTextEdit(it.oldText, it.newText) }
                    mutation(call, "Edit", "$path\n${edits.size} change${if (edits.size == 1) "" else "s"}",
                        path.length, modelRequestsApproval = args.askUser) {
                        snapshot(call, workspace.patch(path, args.expectedSha256, edits), "Edited  $path")
                    }
                }
                "create_file" -> {
                    val args = json.decodeFromString<CreateArgs>(call.arguments); val path = path(args.path)
                    mutation(call, "Create file", path, path.length * 2 + 255,
                        modelRequestsApproval = args.askUser) {
                        val created = workspace.createFileWithParents(path)
                        val content = args.content
                        val written = if (content != null) {
                            // create_file can be called with content by a model that does not
                            // want a two-step create/write. If the provider omitted the hash,
                            // read the new file once rather than silently dropping content.
                            val hash = created.sha256 ?: workspace.read(created.path).sha256
                            workspace.write(created.path, content, hash)
                        } else null
                        success(call, buildJsonObject {
                            put("path", written?.path ?: created.path)
                            if (created.path != path) put("requested_path", path)
                            (written?.sha256 ?: created.sha256)?.let { put("sha256", it) }
                            (written?.size ?: created.size)?.let { put("size", it) }
                            created.inspectionError?.let { put("inspection_error", it) }
                        }, "Created  ${written?.path ?: created.path}")
                    }
                }
                "create_directory" -> {
                    val args = json.decodeFromString<PathArgs>(call.arguments); val path = path(args.path)
                    mutation(call, "Create folder", path, path.length * 2 + 255,
                        modelRequestsApproval = args.askUser) {
                        val created = workspace.createDirectory(path)
                        success(call, entryValue(created, requestedPath = path), "Created  ${created.path}")
                    }
                }
                "rename_path" -> {
                    val args = json.decodeFromString<RenameArgs>(call.arguments)
                    val path = path(args.path); name(args.newName)
                    val approved = workspace.observeStructural(path, recursive = true)
                    mutation(call, "Rename", "$path\n→ ${args.newName}", path.length * 2 + 765,
                        path, modelRequestsApproval = args.askUser) {
                        val renamed = workspace.rename(path, args.newName, approved)
                        val actualName = renamed.path.substringAfterLast('/')
                        success(call, buildJsonObject {
                            put("source_path", path)
                            put("path", renamed.path)
                            put("new_name", actualName)
                            if (actualName != args.newName) put("requested_name", args.newName)
                            put("type", if (renamed.directory) "directory" else "file")
                        }, "Renamed  ${renamed.path}")
                    }
                }
                "move_path" -> {
                    val args = json.decodeFromString<MoveArgs>(call.arguments)
                    val path = path(args.path); val destination = path(args.destination, root = true)
                    externalTargets(call)
                    val approvedSource = workspace.observeStructural(path, recursive = true)
                    val approvedDestination = workspace.observeStructural(destination, recursive = false)
                    mutation(call, "Move", "$path\n→ ${destination.ifEmpty { "." }}",
                        path.length + destination.length * 2 + 256, path,
                        modelRequestsApproval = args.askUser) {
                        val moved = workspace.move(path, destination, approvedSource, approvedDestination)
                        success(call, buildJsonObject {
                            put("source_path", path)
                            put("destination", destination)
                            put("path", moved.path)
                            put("type", if (moved.directory) "directory" else "file")
                        }, "Moved  ${moved.path}")
                    }
                }
                "delete_path" -> {
                    val args = json.decodeFromString<PathArgs>(call.arguments); val path = path(args.path)
                    val approved = workspace.observeStructural(path, recursive = true)
                    mutation(call, "Delete", path, path.length, path,
                        modelRequestsApproval = args.askUser) {
                        workspace.delete(path, approved)
                        success(call, buildJsonObject { put("path", path); put("deleted", true) }, "Deleted  $path")
                    }
                }
                "run_command" -> {
                    val args = json.decodeFromString<CommandArgs>(call.arguments)
                    val hasCommand = args.command.isNotBlank()
                    val outputId = args.outputId?.takeIf(String::isNotBlank)
                    require(hasCommand != (outputId != null)) {
                        "Pass either command or output_id, not both."
                    }
                    require(args.offset == null || args.offset >= 0)
                    require(args.tail == null || args.tail in 1..CommandOutputSlicer.MAX_LINES)
                    require(!(args.offset != null && args.offset > 0 && args.tail != null)) {
                        "Pass either offset or tail, not both."
                    }
                    require(args.maxLines == null || args.maxLines in 1..CommandOutputSlicer.MAX_LINES)
                    require(args.grep == null || args.grep.length <= CommandOutputSlicer.MAX_GREP_CHARS)
                    if (outputId != null) {
                        return readOnly(call) {
                            val cached = readCommandOutput?.invoke(outputId, currentWorkspaceIdentity())
                                ?: throw AgentWorkspaceFailure("output_unavailable")
                            val slice = CommandOutputSlicer.slice(
                                cached.stdout, cached.stderr,
                                offset = args.offset, tail = args.tail, grep = args.grep,
                                maxLines = args.maxLines,
                                maxBytes = 18 * 1024,
                            )
                            commandPage(call, outputId, cached.stdoutTruncated, cached.stderrTruncated, slice)
                        }
                    }
                    val cwd = path(args.cwd, root = true)
                    val matchedScripts = matchSkillScripts(args.command, skills, cwd = cwd)
                        .map { hit -> hit.copy(contentHash = SkillScriptContent.hash(hit, workspace)) }
                    require(args.timeoutMs in 1000..MAX_COMMAND_TIMEOUT_MS)
                    PreparedAgentTool(
                        call,
                        AgentApprovalRequest(call, "Run command",
                            "${args.command}${if (cwd.isEmpty()) "" else "\nIn folder: $cwd"}",
                            dangerous = true),
                        effect = AgentToolEffect.ForegroundCommand,
                        basicAutonomyRisk = ApprovalPolicy.basicCommandRisk(args.command),
                        modelRequestsApproval = args.askUser,
                        blockedReason = ApprovalPolicy.blockedCommandReason(args.command),
                        skillScript = matchedScripts.firstOrNull(),
                        skillScripts = matchedScripts,
                    ) {
                        execute(call) {
                            val result = runCommand?.invoke(args.command, cwd, args.timeoutMs.toLong())
                                ?: throw AgentWorkspaceFailure("runtime_unavailable")
                            commandResult(call, result, args)
                        }
                    }
                }
                "spawn_process" -> {
                    val args = json.decodeFromString<SpawnProcessArgs>(call.arguments)
                    require(args.command.isNotBlank() && '\u0000' !in args.command &&
                        args.command.toByteArray(Charsets.UTF_8).size <= 8192)
                    require(args.ttlMs in 5_000..1_800_000)
                    require(args.readyWaitMs in 0..10_000)
                    require(args.readyPattern == null || args.readyPattern.length <= 256)
                    val cwd = path(args.cwd, root = true)
                    PreparedAgentTool(
                        call,
                        AgentApprovalRequest(call, "Run background command",
                            "${args.command}${if (cwd.isEmpty()) "" else "\nIn folder: $cwd"}",
                            dangerous = true),
                        effect = AgentToolEffect.PersistentProcess,
                        basicAutonomyRisk = BasicAutonomyRisk.Elevated,
                        modelRequestsApproval = args.askUser,
                        blockedReason = ApprovalPolicy.blockedCommandReason(args.command),
                    ) {
                        execute(call) {
                            val result = spawnProcess?.invoke(
                                args.command, cwd, args.ttlMs.toLong(),
                                args.readyWaitMs.toLong(), args.readyPattern,
                            ) ?: throw AgentWorkspaceFailure("runtime_unavailable")
                            success(call, buildJsonObject {
                                put("process_id", result.processId)
                                put("status", result.status)
                                put("ttl_ms", result.ttlMs)
                                result.exitCode?.let { put("exit_code", it) }
                                put("stdout", result.stdout)
                                put("stderr", result.stderr)
                                put("stderr_present", result.stderrPresent)
                                result.readyMatched?.let { put("ready_matched", it) }
                            }, "Started background process")
                        }
                    }
                }
                "process_output" -> {
                    val args = json.decodeFromString<ProcessOutputArgs>(call.arguments)
                    val processId = args.processId
                    require(PROCESS_ID.matches(processId))
                    require(args.offset == null || args.offset >= 0)
                    require(args.tail == null || args.tail in 1..CommandOutputSlicer.MAX_LINES)
                    require(!(args.offset != null && args.offset > 0 && args.tail != null)) {
                        "Pass either offset or tail, not both."
                    }
                    require(args.maxLines == null || args.maxLines in 1..CommandOutputSlicer.MAX_LINES)
                    require(args.grep == null || args.grep.length <= CommandOutputSlicer.MAX_GREP_CHARS)
                    readOnly(call) {
                        val identity = currentWorkspaceIdentity()
                            ?: throw AgentWorkspaceFailure("process_unavailable")
                        val output = readProcessOutput?.invoke(
                            processId, identity, args.offset, args.tail, args.grep, args.maxLines,
                        ) ?: throw AgentWorkspaceFailure("process_unavailable")
                        val slice = CommandOutputSlicer.slice(
                            output.stdout, output.stderr,
                            offset = args.offset, tail = args.tail,
                            grep = args.grep, maxLines = args.maxLines,
                            maxBytes = 18 * 1024,
                        )
                        success(call, buildJsonObject {
                            put("process_id", processId)
                            put("status", output.status)
                            put("exit_code", output.exitCode)
                            put("stdout", slice.stdout)
                            put("stderr", slice.stderr)
                            put("stdout_truncated", slice.stdoutTruncated)
                            put("stderr_truncated", slice.stderrTruncated)
                            put("stderr_present", output.stderrPresent || slice.stderrPresent)
                            put("total_lines", slice.totalLines)
                            put("returned_lines", slice.returnedLines)
                            slice.nextOffset?.let { put("next_offset", it) }
                            slice.matchedLines?.let { put("matched_lines", it) }
                        }, "Read background process output")
                    }
                }
                "list_processes" -> {
                    json.decodeFromString<NoArgs>(call.arguments)
                    PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) {
                        execute(call) {
                        val identity = currentWorkspaceIdentity()
                        val active = managedProcesses?.activeForWorkspace(identity).orEmpty()
                        success(call, buildJsonObject {
                            put("processes", buildJsonArray {
                                active.forEach { process -> add(buildJsonObject {
                                    put("process_id", process.id)
                                    put("type", when (process.kind) {
                                        ManagedProcessKind.Preview -> "preview"
                                        ManagedProcessKind.Background -> "background"
                                        ManagedProcessKind.Command -> "command"
                                    })
                                    put("project", process.projectName.take(100))
                                    put("command", process.label)
                                    put("status", process.status.name.lowercase())
                                    process.url?.let { put("url", it) }
                                }) }
                            })
                        }, "Listed active project operations")
                        }
                    }
                }
                "stop_process" -> {
                    val args = json.decodeFromString<StopProcessArgs>(call.arguments)
                    require(PROCESS_ID.matches(args.processId))
                    val identity = currentWorkspaceIdentity()
                        ?: throw AgentWorkspaceFailure("process_unavailable")
                    val description = managedProcesses?.stopDescription(args.processId, identity)
                        ?: throw AgentWorkspaceFailure("process_unavailable")
                    PreparedAgentTool(call,
                        AgentApprovalRequest(call, "Stop project operation", description),
                        effect = AgentToolEffect.PersistentProcess,
                        basicAutonomyRisk = BasicAutonomyRisk.Routine,
                        modelRequestsApproval = args.askUser,
                    ) {
                        execute(call) {
                            val selected = currentWorkspaceIdentity()
                            val stopped = selected == identity && managedProcesses?.stop(args.processId, identity) == true
                            if (!stopped) throw AgentWorkspaceFailure("process_unavailable")
                            success(call, buildJsonObject {
                                put("process_id", args.processId)
                                put("stopping", true)
                            }, "Stopped project operation")
                        }
                    }
                }
                "start_preview" -> {
                    val args = json.decodeFromString<PreviewArgs>(call.arguments)
                    val paths = try { PreviewPaths.parse(args.root, args.entry) }
                        catch (_: com.kaiser.rivet.workspace.WorkspaceFailure) { throw InvalidPath() }
                        catch (_: IllegalArgumentException) { throw InvalidPath() }
                    val root = paths.root.value
                    val entry = paths.entry.value
                    PreparedAgentTool(call,
                        AgentApprovalRequest(call, "Start local preview",
                            "$entry\nOnly this device can access it at 127.0.0.1."),
                        effect = AgentToolEffect.Preview,
                        basicAutonomyRisk = BasicAutonomyRisk.Routine,
                        modelRequestsApproval = args.askUser,
                    ) {
                        execute(call) {
                            val started = startPreview?.invoke(root, entry)
                                ?: throw AgentWorkspaceFailure("runtime_unavailable")
                            success(call, buildJsonObject {
                                put("process_id", started.processId)
                                put("url", started.url)
                                put("entry", started.entry)
                                put("reused", started.reused)
                                put("state", "running")
                            }, "Started preview  ${started.entry}")
                        }
                    }
                }
                "download_file" -> {
                    val args = json.decodeFromString<DownloadArgs>(call.arguments)
                    val target = path(args.path)
                    val overwriteHash = args.expectedSha256?.also(::hash)
                    val contentHash = args.contentSha256?.also {
                        if (!CONTENT_SHA.matches(it)) throw AgentWorkspaceFailure("invalid_checksum")
                    }
                    val external = externalTargets(call)
                    val client = downloader ?: throw AgentWorkspaceFailure("runtime_unavailable")
                    val host = client.hostForDisplay(args.url)
                    PreparedAgentTool(call,
                        AgentApprovalRequest(call, if (external) "Download file" else "Download project file",
                            "$target\nFrom $host over HTTPS" + if (external) NO_EXTERNAL_UNDO else ""),
                        resultContentLimitBytes = DOWNLOAD_RESULT_BYTES,
                        effect = if (external) AgentToolEffect.WorkspaceMutation else AgentToolEffect.Download,
                        modelRequestsApproval = args.askUser,
                    ) {
                        execute(call) {
                            client.download(args.url, contentHash).use { staged ->
                                val saved = FileInputStream(staged.file).use { input ->
                                    workspace.writeDownloaded(target, input, overwriteHash)
                                }
                                if (saved.sha256 != staged.sha256 || saved.size != staged.size) {
                                    throw AgentWorkspaceFailure("conflict")
                                }
                                success(call, buildJsonObject {
                                    put("path", saved.path)
                                    put("sha256", saved.sha256)
                                    put("size", saved.size)
                                    put("created", overwriteHash == null)
                                }, "Downloaded  ${saved.path}")
                            }
                        }
                    }
                }
                "phone_shot" -> preparePhoneShot?.invoke(call) ?: invalid(call, "unknown_tool")
                "termux_command" -> prepareTermuxCommand?.invoke(call) ?: invalid(call, "unknown_tool")
                "open_app", "open_url", "open_file", "share_out", "compose_intent" ->
                    preparePhoneIntent?.invoke(call) ?: invalid(call, "unknown_tool")
                else -> invalid(call, "unknown_tool")
            }
        } catch (_: SerializationException) {
            invalid(call, "invalid_arguments")
        } catch (_: IllegalArgumentException) {
            invalid(call, "invalid_arguments")
        } catch (_: InvalidPath) {
            invalid(call, "invalid_path")
        } catch (e: WorkspaceFailure) {
            invalid(call, e.reason.name.lowercase())
        } catch (e: AgentWorkspaceFailure) {
            invalid(call, e.code)
        } catch (e: DownloadFailure) {
            invalid(call, e.code)
        }
    }

    private fun readOnly(call: AgentToolCall, action: suspend () -> AgentToolResult) =
        PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) {
            execute(call) { action() }
        }

    private suspend fun mutation(
        call: AgentToolCall,
        title: String,
        detail: String,
        resultPathChars: Int,
        destructivePath: String? = null,
        modelRequestsApproval: Boolean = false,
        memoryFact: String? = null,
        action: suspend () -> AgentToolResult,
    ): PreparedAgentTool {
        val external = externalTargets(call)
        return PreparedAgentTool(
            call, AgentApprovalRequest(call, title, detail + if (external) NO_EXTERNAL_UNDO else "",
                destructivePath, undoable = !external),
            // Paths contain no controls: three UTF-8 bytes per UTF-16 unit covers
            // their JSON representation. Fixed fields, hashes and numbers fit in 512.
            resultContentLimitBytes = minOf(AgentLoop.MAX_TOOL_RESULT_BYTES, 512 + resultPathChars * 3),
            effect = if (external || destructivePath == null) AgentToolEffect.WorkspaceMutation
                else AgentToolEffect.DestructiveWorkspaceMutation,
            basicAutonomyRisk = if (destructivePath == null && !external) BasicAutonomyRisk.Routine
                else BasicAutonomyRisk.Elevated,
            modelRequestsApproval = modelRequestsApproval,
            memoryFact = memoryFact,
        ) { execute(call) { action() } }
    }

    private suspend fun externalTargets(call: AgentToolCall): Boolean {
        val router = workspace as? RoutedAgentWorkspace ?: return false
        val targets = FileScopeRegistry.mutationPaths(call)
        val external = targets.filter { GrantPath.isExternal(it.value) }
        if (external.isEmpty()) return false
        if (call.name == "move_path" && targets.map { GrantPath.parse(it.value).prefix }.distinct().size > 1) {
            throw AgentWorkspaceFailure("cross_folder_move")
        }
        external.forEach { router.checkWrite(it.value) }
        return true
    }

    suspend fun describeDestructive(request: AgentApprovalRequest): AgentApprovalRequest {
        val target = request.destructivePath ?: return request
        val entry = try { workspace.stat(target) } catch (e: CancellationException) { throw e
        } catch (_: AgentWorkspaceFailure) { null }
        val kind = when (entry?.directory) { true -> "folder"; false -> "file"; null -> "path" }
        val verb = when (request.call.name) {
            "delete_path" -> "Delete"
            "rename_path" -> "Rename"
            else -> "Move"
        }
        val size = entry?.takeIf { !it.directory }?.size?.let { "\n%,d bytes".format(java.util.Locale.US, it) } ?: ""
        val warning = if (request.call.name == "delete_path") {
            if (request.undoable)
                "This may be one of your existing files. Undo is available only for the most recent 3 turns."
            else "This may be one of your existing files. Deletion is permanent."
        } else "This may be one of your existing files."
        return request.copy(title = "$verb ${if (entry == null) "unverified" else "existing"} $kind?",
            detail = "${request.detail}$size\n$warning", dangerous = true)
    }

    private suspend fun execute(call: AgentToolCall, action: suspend () -> AgentToolResult): AgentToolResult = try {
        action()
    } catch (e: CancellationException) {
        throw e
    } catch (e: WorkspaceFailure) {
        failure(call, e.reason.name.lowercase())
    } catch (e: AgentWorkspaceFailure) {
        failure(call, e.code)
    } catch (e: MirrorFailure) {
        failure(call, e.code, subject = e.path)
    } catch (e: DownloadFailure) {
        failure(call, e.code, e.statusCode)
    } catch (_: Exception) {
        failure(call, "workspace_error")
    }

    private fun invalid(call: AgentToolCall, code: String) =
        PreparedAgentTool(call, null, effect = AgentToolEffect.ReadOnly) { failure(call, code) }

    private fun failure(call: AgentToolCall, code: String, statusCode: Int? = null,
                        subject: String? = null) = AgentToolResult(
        call.id, call.name, if (code == "output_limit") AgentLoop.OUTPUT_LIMIT_CONTENT
        else AgentToolError.content(code, statusCode, subject), true, "Failed  ${call.name}",
    )

    private fun success(call: AgentToolCall, value: JsonObject, summary: String) =
        AgentToolResult(call.id, call.name, value.toString(), summary = summary)

    private fun snapshot(call: AgentToolCall, file: AgentFileSnapshot, summary: String) = success(call, buildJsonObject {
        put("path", file.path); put("sha256", file.sha256); put("size", file.size)
    }, summary)

    private fun commandPage(
        call: AgentToolCall,
        outputId: String,
        sourceStdoutTruncated: Boolean,
        sourceStderrTruncated: Boolean,
        slice: CommandOutputSlicer.Slice,
    ) = success(call, buildJsonObject {
        put("stdout", slice.stdout)
        put("stderr", slice.stderr)
        put("stdout_truncated", sourceStdoutTruncated || slice.stdoutTruncated)
        put("stderr_truncated", sourceStderrTruncated || slice.stderrTruncated)
        put("stderr_present", slice.stderrPresent)
        put("total_lines", slice.totalLines)
        put("returned_lines", slice.returnedLines)
        slice.nextOffset?.let { put("next_offset", it) }
        slice.matchedLines?.let { put("matched_lines", it) }
        put("output_id", outputId)
        put("sync", "not_started")
    }, "Paged cached command output")

    private fun commandResult(
        call: AgentToolCall,
        result: RuntimeCommandResult,
        args: CommandArgs,
    ): AgentToolResult {
        val slice = CommandOutputSlicer.slice(
            result.stdout, result.stderr,
            offset = args.offset, tail = args.tail, grep = args.grep, maxLines = args.maxLines,
        )
        var stdout = slice.stdout
        var stderr = slice.stderr
        var stdoutCut = result.stdoutTruncated || slice.stdoutTruncated
        var stderrCut = result.stderrTruncated || slice.stderrTruncated
        fun value() = buildJsonObject {
            result.error?.let {
                put("error", it)
                AgentToolError.action(it)?.let { action ->
                    put("retryable", false)
                    put("required_action", action)
                }
            }
            result.exitCode?.let { put("exit_code", it) }
            put("stdout", stdout)
            put("stderr", stderr)
            put("stdout_truncated", stdoutCut)
            put("stderr_truncated", stderrCut)
            put("stderr_present", result.stderrPresent || slice.stderrPresent)
            put("total_lines", slice.totalLines)
            put("returned_lines", slice.returnedLines)
            slice.nextOffset?.let { put("next_offset", it) }
            slice.matchedLines?.let { put("matched_lines", it) }
            result.outputId?.let { put("output_id", it) }
            put("timed_out", result.timedOut)
            if (result.tooManyChildren) put("too_many_child_processes", true)
            if (result.memoryLimit) put("memory_limit", true)
            put("cwd", result.cwd)
            put("sync", result.sync)
            result.vcsNote?.let { put("worktree_vcs", it) }
            result.syncPath?.let { put("sync_path", it) }
            if (result.syncSkipped.isNotEmpty()) put("sync_skipped", buildJsonArray {
                result.syncSkipped.take(20).forEach { add(JsonPrimitive(it)) }
            })
            if (result.syncConflicts.isNotEmpty()) put("sync_conflict_paths", buildJsonArray {
                result.syncConflicts.take(20).forEach { add(JsonPrimitive(it)) }
            })
        }
        var encoded = value()
        while (encoded.toString().toByteArray(Charsets.UTF_8).size > AgentLoop.MAX_TOOL_RESULT_BYTES) {
            if (stdout.length >= stderr.length && stdout.isNotEmpty()) {
                stdout = shorten(stdout)
                stdoutCut = true
            } else if (stderr.isNotEmpty()) {
                stderr = shorten(stderr)
                stderrCut = true
            } else throw AgentWorkspaceFailure("output_limit")
            encoded = value()
        }
        return AgentToolResult(call.id, call.name, encoded.toString(), result.error != null,
            if (result.error == null) "Command exited  ${result.exitCode}" else "Failed  run_command")
    }

    private fun boundedGitStatus(status: RepositoryStatus): JsonObject {
        val fields = listOf(
            "staged" to status.staged,
            "modified" to status.modified,
            "deleted" to status.deleted,
            "untracked" to status.untracked,
            "conflicts" to status.conflicts,
        )
        var maxPaths = 100
        while (true) {
            var remaining = maxPaths
            var shown = 0
            val value = buildJsonObject {
                put("repository", status.present)
                status.branch?.let { put("branch", it) }
                status.head?.let { put("head", it) }
                fields.forEach { (name, paths) ->
                    val selected = paths.take(remaining)
                    remaining -= selected.size
                    shown += selected.size
                    put(name, JsonArray(selected.map(::JsonPrimitive)))
                }
                put("limited", fields.sumOf { it.second.size } > shown)
            }
            if (value.toString().toByteArray(Charsets.UTF_8).size <= AgentLoop.MAX_TOOL_RESULT_BYTES || maxPaths == 0) {
                return value
            }
            maxPaths /= 2
        }
    }

    private fun gitDiffValue(diff: RepositoryDiff, text: String, extraLimit: Boolean = false) = buildJsonObject {
        put("repository", diff.present)
        put("diff", text)
        put("files", diff.files)
        put("limited", diff.limited || extraLimit)
    }

    private fun shorten(value: String): String {
        if (value.length <= 64) return value.take(value.length / 2).dropLastWhile { it.isHighSurrogate() }
        val half = value.length / 4
        return value.take(half).dropLastWhile { it.isHighSurrogate() } +
            "\n… [output truncated] …\n" + value.takeLast(half).dropWhile { it.isLowSurrogate() }
    }

    private fun entryValue(entry: AgentWorkspaceEntry, requestedPath: String? = null) = buildJsonObject {
        put("path", entry.path)
        if (requestedPath != null && entry.path != requestedPath) put("requested_path", requestedPath)
        put("type", if (entry.directory) "directory" else "file")
        if (!entry.directory) entry.size?.let { put("size", it) }
    }

    private fun path(value: String, root: Boolean = false): String {
        if (value.isEmpty()) {
            if (root) return value
            throw InvalidPath()
        }
        if (worker && GrantPath.isExternal(value)) throw AgentWorkspaceFailure("outside_file_scope")
        if (value.length > 4096) throw InvalidPath()
        val segments = value.split('/')
        if (segments.size > 64 || segments.any { it.isBlank() || it == "." || it == ".." || it.endsWith('.') || it.length > 255 ||
                it.any { char -> char == '\\' || char.isISOControl() } }) throw InvalidPath()
        return value
    }

    private fun name(value: String) {
        if (value.isBlank() || value.endsWith('.') || value.length > 255 ||
            value.any { it == '/' || it == '\\' || it.isISOControl() }) throw InvalidPath()
    }

    private fun hash(value: String) = require(HASH.matches(value))

    private fun readRegionValue(path: String, region: AgentFileRegion) = buildJsonObject {
        put("path", path)
        put("text", region.text)
        put("sha256", region.sha256)
        put("size", region.size)
        put("offset", region.start)
        put("bytes", region.end - region.start)
        put("eof", region.end == region.size)
        if (region.end < region.size) put("next_offset", region.end)
    }

    private fun boundedSearchValue(report: AgentSearchReport): JsonObject {
        val hits = report.hits.map { hit -> buildJsonObject {
            put("path", hit.path)
            hit.line?.let { put("line", it) }
            put("context", hit.context.take(240).dropLastWhile { it.isHighSurrogate() })
        } }
        for (count in hits.size downTo 0) {
            val value = buildJsonObject {
                put("hits", JsonArray(hits.take(count)))
                put("limited", report.limited || count < hits.size)
                put("files_scanned", report.filesScanned)
                put("entries_visited", report.entriesVisited)
                put("bytes_scanned", report.bytesScanned)
                put("skipped", report.skipped)
            }
            if (value.toString().toByteArray(Charsets.UTF_8).size <= AgentLoop.MAX_TOOL_RESULT_BYTES) return value
        }
        throw AgentWorkspaceFailure("output_limit")
    }

    private class InvalidPath : Exception()

    @Serializable private data class ListArgs(val path: String = "")
    @Serializable private data class PathArgs(val path: String, @kotlinx.serialization.SerialName("ask_user") val askUser: Boolean = false)
    @Serializable private data class CreateArgs(
        val path: String,
        val content: String? = null,
        @kotlinx.serialization.SerialName("ask_user") val askUser: Boolean = false,
    )
    private fun reservedGoalMutation(call: AgentToolCall): Boolean = reservedMutation(call) { name, path, newName, destination ->
        GoalMutationGuard.isReservedGoalMutation(name, path, newName, destination)
    }

    private fun reservedMemoryMutation(call: AgentToolCall): Boolean = reservedMutation(call) { name, path, newName, destination ->
        GoalMutationGuard.isReservedMemoryMutation(name, path, newName, destination)
    }

    private fun reservedMutation(
        call: AgentToolCall,
        check: (String, String?, String?, String?) -> Boolean,
    ): Boolean = try {
        val value = json.parseToJsonElement(call.arguments).jsonObject
        check(
            call.name,
            value["path"]?.jsonPrimitive?.contentOrNull,
            value["new_name"]?.jsonPrimitive?.contentOrNull,
            value["destination"]?.jsonPrimitive?.contentOrNull,
        )
    } catch (_: Exception) { false }

    @Serializable private data class SearchArgs(val query: String, val path: String = "")
    @Serializable private data class SkillArgs(val name: String)
    @Serializable private data class RememberArgs(val fact: String = "")
    @Serializable private data class WriteArgs(
        val path: String,
        val content: String,
        @kotlinx.serialization.SerialName("expected_sha256") val expectedSha256: String,
        @kotlinx.serialization.SerialName("ask_user") val askUser: Boolean = false,
    )
    @Serializable private data class PatchEdit(
        @kotlinx.serialization.SerialName("old_text") val oldText: String,
        @kotlinx.serialization.SerialName("new_text") val newText: String,
    )
    @Serializable private data class PatchArgs(
        val path: String,
        @kotlinx.serialization.SerialName("expected_sha256") val expectedSha256: String,
        val edits: List<PatchEdit>,
        @kotlinx.serialization.SerialName("ask_user") val askUser: Boolean = false,
    )
    @Serializable private data class RenameArgs(
        val path: String,
        @kotlinx.serialization.SerialName("new_name") val newName: String,
        @kotlinx.serialization.SerialName("ask_user") val askUser: Boolean = false,
    )
    @Serializable private data class MoveArgs(val path: String, val destination: String,
        @kotlinx.serialization.SerialName("ask_user") val askUser: Boolean = false)
    @Serializable private data class GitStatusArgs(val path: String = "")
    @Serializable private class NoArgs
    @Serializable private data class StopProcessArgs(
        @kotlinx.serialization.SerialName("process_id") val processId: String,
        @kotlinx.serialization.SerialName("ask_user") val askUser: Boolean = false,
    )
    @Serializable private data class PreviewArgs(
        val entry: String,
        val root: String = "",
        @kotlinx.serialization.SerialName("ask_user") val askUser: Boolean = false,
    )
    @Serializable private data class SpawnProcessArgs(
        val command: String,
        val cwd: String = "",
        @kotlinx.serialization.SerialName("ttl_ms") val ttlMs: Int = 600_000,
        @kotlinx.serialization.SerialName("ready_wait_ms") val readyWaitMs: Int = 1_000,
        @kotlinx.serialization.SerialName("ready_pattern") val readyPattern: String? = null,
        @kotlinx.serialization.SerialName("ask_user") val askUser: Boolean = false,
    )
    @Serializable private data class ProcessOutputArgs(
        @kotlinx.serialization.SerialName("process_id") val processId: String,
        val offset: Int? = null,
        val tail: Int? = null,
        val grep: String? = null,
        @kotlinx.serialization.SerialName("max_lines") val maxLines: Int? = null,
    )
    @Serializable private data class DownloadArgs(
        val url: String,
        val path: String,
        @kotlinx.serialization.SerialName("expected_sha256") val expectedSha256: String? = null,
        @kotlinx.serialization.SerialName("content_sha256") val contentSha256: String? = null,
        @kotlinx.serialization.SerialName("ask_user") val askUser: Boolean = false,
    )
    @Serializable private data class GitDiffArgs(val path: String = "")
    @Serializable private data class CommandArgs(
        val command: String = "",
        val cwd: String = "",
        @kotlinx.serialization.SerialName("timeout_ms") val timeoutMs: Int = 300_000,
        @kotlinx.serialization.SerialName("ask_user") val askUser: Boolean = false,
        @kotlinx.serialization.SerialName("output_id") val outputId: String? = null,
        val offset: Int? = null,
        val tail: Int? = null,
        val grep: String? = null,
        @kotlinx.serialization.SerialName("max_lines") val maxLines: Int? = null,
    )


    companion object {
        private val json = Json { ignoreUnknownKeys = false; isLenient = false }
        private val HASH = Regex("[0-9a-f]{64}")
        private val CONTENT_SHA = Regex("[0-9a-fA-F]{64}")
        private val PROCESS_ID = Regex("[0-9a-fA-F-]{36}")
        private const val MAX_ARGUMENT_BYTES = 1_200_000
        private const val MAX_LIST_ENTRIES = 200
        private const val MAX_READ_CHUNK_BYTES = 8 * 1024
        private const val MAX_COMMAND_TIMEOUT_MS = 30 * 60 * 1000
        // One confirmed path (up to WorkspacePath's 4096 UTF-16 units), hash, size, and JSON framing.
        private const val DOWNLOAD_RESULT_BYTES = 16 * 1024

        private fun schema(required: List<String>, vararg fields: Pair<String, JsonObject>) = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { fields.forEach { (name, value) -> put(name, value) } })
            put("required", JsonArray(required.map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
        private const val NO_EXTERNAL_UNDO = "\nFiles outside the project have no checkpoint or Undo."
        private val string = buildJsonObject { put("type", "string") }
        private val nonNegativeInteger = buildJsonObject { put("type", "integer"); put("minimum", 0) }
        private val boolean = buildJsonObject {
            put("type", "boolean")
            put("description", "Ask for confirmation in Basic YOLO.")
        }
        private val askUser = "ask_user" to boolean
        private val processId = "process_id" to string
        private val entry = "entry" to string
        private val root = "root" to string
        private val url = "url" to string
        private val expectedSha = "expected_sha256" to string
        private val contentSha = "content_sha256" to string
        private val path = "path" to string

        internal val phoneOnlyNames = PhoneIntents.names + setOf("termux_command", "phone_shot")

        val definitions = listOf(
            AgentToolDefinition("git_status", "Inspect Git status and conflicts. External folders return repository=false.", schema(emptyList(), path)),
            AgentToolDefinition("git_diff", "Read staged and unstaged Git diffs for tracked files; empty path covers the repo.", schema(emptyList(), path)),
            AgentToolDefinition("list_directory", "List a workspace directory. Empty path means workspace root.", schema(emptyList(), path)),
            AgentToolDefinition(
                "read_file",
                "Read a UTF-8 page. Continue at next_offset (bytes). The hash covers the whole file.",
                schema(listOf("path"), path, "offset" to nonNegativeInteger),
            ),
            AgentToolDefinition("present",
                "Show 1–4 requested deliverables using project-relative paths and short descriptions.",
                schema(listOf("files"), "files" to buildJsonObject {
                    put("type", "array")
                    put("minItems", 1)
                    put("maxItems", 4)
                    put("items", schema(listOf("path", "description"), path, "description" to string))
                })),
            AgentToolDefinition("read_image", "View an existing image at a project-relative path.",
                schema(listOf("path"), path)),
            AgentToolDefinition(
                "search_files",
                "Search literally and case-sensitively. A limited result may omit matches; check limited, files_scanned, entries_visited, bytes_scanned, and skipped.",
                schema(listOf("query"), path, "query" to string),
            ),
            AgentToolDefinition("skill", "Load an installed skill before using it. Read resources relative to its returned base.",
                schema(listOf("name"), "name" to string)),
            AgentToolDefinition("remember", "Save a durable project fact. Excludes secrets, transcripts and task progress.", schema(listOf("fact"), "fact" to string)),
            AgentToolDefinition("write_file", "Replace a text file if its SHA-256 matches.",
                schema(listOf("path", "content", "expected_sha256"), path, "content" to string, "expected_sha256" to string, askUser)),
            AgentToolDefinition("apply_patch", "Apply ordered, exact unique text replacements sequentially; each later edit sees earlier edits, and all edits validate before the final write if the file hash matches.",
                schema(listOf("path", "expected_sha256", "edits"), path, "expected_sha256" to string,
                    "edits" to buildJsonObject { put("type", "array"); put("items", schema(listOf("old_text", "new_text"), "old_text" to string, "new_text" to string)) }, askUser)),
            AgentToolDefinition("create_file", "Create a file and optional content. Use the returned path and hash for later edits.", schema(listOf("path"), path, "content" to string, askUser)),
            AgentToolDefinition("create_directory", "Create a directory.", schema(listOf("path"), path, askUser)),
            AgentToolDefinition("rename_path", "Rename a file or directory. Use the returned path afterward.", schema(listOf("path", "new_name"), path, "new_name" to string, askUser)),
            AgentToolDefinition("move_path", "Move into an existing directory; empty destination means root.", schema(listOf("path", "destination"), path, "destination" to string, askUser)),
            AgentToolDefinition("delete_path", "Permanently delete a non-root path. Inspect it first.", schema(listOf("path"), path, askUser)),
            AgentToolDefinition("run_command", "Run one project command or page cached output with output_id. Pass exactly one. offset/tail/grep/max_lines select lines. Check exit_code and sync separately.",
                schema(emptyList(), "command" to string, "output_id" to string, "cwd" to string,
                    "timeout_ms" to buildJsonObject { put("type", "integer"); put("minimum", 1000); put("maximum", MAX_COMMAND_TIMEOUT_MS) },
                    "offset" to nonNegativeInteger,
                    "tail" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 500) },
                    "grep" to string,
                    "max_lines" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 500) },
                    askUser)),
            AgentToolDefinition("spawn_process", "Start a stoppable background command. Use ready_pattern and ready_wait_ms to wait for readiness.",
                schema(listOf("command"), "command" to string, "cwd" to string,
                    "ttl_ms" to buildJsonObject { put("type", "integer"); put("minimum", 5000); put("maximum", 1_800_000) },
                    "ready_wait_ms" to buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 10000) },
                    "ready_pattern" to string, askUser)),
            AgentToolDefinition("process_output", "Read background stdout/stderr. offset/tail/grep/max_lines select lines.",
                schema(listOf("process_id"), processId,
                    "offset" to nonNegativeInteger,
                    "tail" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 500) },
                    "grep" to string,
                    "max_lines" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 500) })),
            AgentToolDefinition("list_processes", "List active Rivet operations and their process IDs.", schema(emptyList())),
            AgentToolDefinition("stop_process", "Stop a Rivet operation by process_id.", schema(listOf("process_id"), processId, askUser)),
            AgentToolDefinition("start_preview", "Serve static files on this device at 127.0.0.1. root is project-relative and entry is relative to root. Use the returned URL.", schema(listOf("entry"), entry, root, askUser)),
            AgentToolDefinition("download_file", "Save a bounded HTTPS download. Replacing a file requires expected_sha256. content_sha256 verifies the download. Never executes the file.", schema(listOf("url", "path"), url, path, expectedSha, contentSha, askUser)),
        ) + PhoneIntents.definitions + TermuxBridge.definition + PhoneCaptureTool.definition
    }
}
