package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.AgentActivityOperation
import com.kaiser.rivet.agent.ActivityOutcome


enum class ProcessActivityKind(val key: String) {
    Thinking("thinking"),
    Read("read"),
    ReadImage("readImage"),
    Search("search"),
    Write("write"),
    Edit("edit"),
    Commands("commands"),
    Code("code"),
    WebSearch("webSearch"),
    WebFetch("webFetch"),
    Subagents("subagents"),
    Plan("plan"),
    Questions("questions"),
    Tools("tools");

    fun pastTense(): String = when (this) {
        Thinking -> "Analysis completed"
        Read -> "Read files"
        ReadImage -> "Read images"
        Search -> "Searched code"
        Write -> "Wrote files"
        Edit -> "Edited files"
        Commands -> "Ran commands"
        Code -> "Ran code"
        WebSearch -> "Searched the web"
        WebFetch -> "Visited web pages"
        Subagents -> "Coordinated subagents"
        Plan -> "Updated the plan"
        Questions -> "Asked questions"
        Tools -> "Called tools"
    }

    fun presentTense(): String = when (this) {
        Thinking -> "Analyzing the request"
        Read -> "Reading files"
        ReadImage -> "Reading images"
        Search -> "Searching code"
        Write -> "Writing files"
        Edit -> "Editing files"
        Commands -> "Running commands"
        Code -> "Running code"
        WebSearch -> "Searching the web"
        WebFetch -> "Visiting web pages"
        Subagents -> "Coordinating subagents"
        Plan -> "Updating the plan"
        Questions -> "Waiting for your action"
        Tools -> "Calling tools"
    }

    fun preparingTense(): String = when (this) {
        Thinking -> "Preparing analysis"
        Read -> "Preparing to read files"
        ReadImage -> "Preparing to read images"
        Search -> "Preparing to search code"
        Write -> "Preparing to write files"
        Edit -> "Preparing to edit files"
        Commands -> "Preparing to run commands"
        Code -> "Preparing to run code"
        WebSearch -> "Preparing to search the web"
        WebFetch -> "Preparing to visit web pages"
        Subagents -> "Preparing to coordinate subagents"
        Plan -> "Preparing to update the plan"
        Questions -> "Preparing questions"
        Tools -> "Preparing to call tools"
    }

    companion object {
        fun fromToolName(name: String): ProcessActivityKind {
            val n = name.trim().lowercase()
            return when {
                n == "read" || n == "read_file" -> Read
                n == "read_image" -> ReadImage
                n == "grep" || n == "glob" || n.endsWith("_inspect") || n == "search_files" ||
                    n == "list_directory" || n == "git_status" || n == "git_diff" -> Search
                n == "write" || n == "create_file" || n == "create_directory" ||
                    n == "download_file" || n == "write_file" -> Write
                n == "edit" || n == "apply_patch" || n == "rename_path" ||
                    n == "move_path" || n == "delete_path" -> Edit
                n in listOf("bash", "pwsh", "exec_command", "write_stdin", "run_command", "list_processes", "stop_process", "start_preview") ||
                    n.startsWith("terminal_") -> Commands
                n == "run_code" -> Code
                n == "web_search" || n == "browser_find" || n == "browser_search" -> WebSearch
                n == "web_fetch" || n in listOf(
                    "browser_open", "browser_read", "browser_click", "browser_type",
                    "browser_scroll", "browser_back", "browser_tap", "browser_swipe",
                    "browser_shot", "browser_close"
                ) -> WebFetch
                n == "subagent" || n.startsWith("subagent_") || n.startsWith("subagent") ||
                    n.startsWith("spawn") || n in listOf("invoke_subagent", "define_subagent", "manage_subagents", "send_message", "swarm") -> Subagents
                n in listOf("todo_write", "create_goal", "update_goal", "get_goal", "remember") ||
                    n.startsWith("goal") -> Plan
                n == "ask_user_question" || n == "request_user_input" || n == "ask_user" -> Questions
                else -> Tools
            }
        }
    }
}

val LIVE_TOOL_DETAIL_KEYS = listOf(
    "title",
    "description",
    "objective",
    "task",
    "task_name",
    "name",
    "question",
    "questions",
    "prompt",
    "message",
    "command",
    "cmd",
    "queries",
    "query",
    "pattern",
    "url",
    "uri",
    "file_path",
    "path",
    "target",
    "action",
    "status"
)

const val LIVE_TOOL_DETAIL_MAX_CHARS = 160

fun normalizeLiveDetail(text: String): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= LIVE_TOOL_DETAIL_MAX_CHARS) return normalized
    return normalized.take(LIVE_TOOL_DETAIL_MAX_CHARS - 1).trimEnd() + "…"
}

fun extractLiveToolDetail(toolName: String, argsJson: String): String {
    if (argsJson.isBlank()) return ""
    for (key in LIVE_TOOL_DETAIL_KEYS) {
        val detail = extractKeyFromSimpleJson(argsJson, key)
        if (!detail.isNullOrBlank()) {
            return normalizeLiveDetail(detail)
        }
    }
    return ""
}

internal fun extractKeyFromSimpleJson(json: String, key: String): String? {
    // Matches "key"\s*:\s*"value" (with basic escape handling) or "key"\s*:\s*\[...\]
    val pattern = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(\"[^\"]*\"|\\[[^\\]]*\\])")
    val match = pattern.find(json) ?: return null
    val rawValue = match.groupValues[1].trim()
    return when {
        rawValue.startsWith("\"") && rawValue.endsWith("\"") -> {
            val unquoted = rawValue.substring(1, rawValue.length - 1)
            unquoted.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", " ").replace("\\t", " ")
        }
        rawValue.startsWith("[") && rawValue.endsWith("]") -> {
            val inner = rawValue.substring(1, rawValue.length - 1).trim()
            val stringItems = Regex("\"([^\"]*)\"").findAll(inner).map { it.groupValues[1] }.toList()
            if (stringItems.isNotEmpty()) stringItems.joinToString(", ") else null
        }
        else -> null
    }
}

fun extractLiveReasoningDetail(reasoning: String): String {
    if (reasoning.isBlank()) return ""
    val paragraphs = reasoning.split(Regex("\\r?\\n[\\t ]*\\r?\\n"))
    for (i in paragraphs.indices.reversed()) {
        val cleaned = paragraphs[i].replace("**", "").trim()
        if (cleaned.isNotBlank()) {
            return normalizeLiveDetail(cleaned)
        }
    }
    return ""
}

data class ActivityCount(
    val kind: ProcessActivityKind,
    val count: Int,
    val firstAppearance: Int
)

data class ProcessGroupSummary(
    val counts: List<ActivityCount>,
    val running: ProcessActivityKind? = null,
    val runningDetail: String = "",
    val preparing: Boolean = false,
    val closed: Boolean = false
) {
    fun title(): String = formatProcessTitle(counts)

    fun liveLabel(): String {
        val base = when {
            preparing -> running?.preparingTense() ?: "Preparing"
            running != null -> running.presentTense()
            else -> "Analyzing the request"
        }
        return if (runningDetail.isNotBlank()) "$base · $runningDetail" else base
    }
}

fun formatProcessTitle(counts: List<ActivityCount>): String {
    if (counts.isEmpty()) return "Analysis completed"
    val top = counts.take(3)
    val first = top[0].kind.pastTense()
    return when (top.size) {
        1 -> first
        2 -> {
            val second = top[1].kind.pastTense().decapitalize()
            "$first and $second"
        }
        3 -> {
            val second = top[1].kind.pastTense().decapitalize()
            val third = top[2].kind.pastTense().decapitalize()
            if (counts.size > 3) {
                "$first, $second, $third, etc."
            } else {
                "$first, $second, $third"
            }
        }
        else -> first
    }
}

private fun String.decapitalize(): String =
    if (isNotEmpty()) this[0].lowercaseChar() + substring(1) else this

fun processCallItems(operations: List<AgentActivityOperation>): List<ProcessCallItem> =
    operations.map { op ->
        ProcessCallItem(
            callId = op.id,
            name = op.toolName,
            argsJson = op.detail,
            isRunning = op.outcome == ActivityOutcome.Running,
            isPreparing = op.outcome == ActivityOutcome.Queued ||
                op.outcome == ActivityOutcome.WaitingApproval,
        )
    }


/**
 * Builds the live header from the same projected operations the rows display.
 * The running/preparing item is the latest lifecycle state, not a caller-supplied summary.
 */
fun processGroupSummary(
    operations: List<AgentActivityOperation>,
    closed: Boolean,
): ProcessGroupSummary {
    val items = processCallItems(operations)
    val running = items.lastOrNull { it.isRunning }
    val preparing = items.lastOrNull { it.isPreparing }
    val active = running ?: preparing
    return ProcessGroupSummary(
        counts = calculateActivityCounts(items),
        running = active?.kind,
        runningDetail = normalizeLiveDetail(active?.argsJson.orEmpty()),
        preparing = running == null && preparing != null,
        closed = closed,
    )
}
data class ProcessCallItem(
    val callId: String,
    val name: String,
    val argsJson: String = "",
    val kind: ProcessActivityKind = ProcessActivityKind.fromToolName(name),
    val isRunning: Boolean = false,
    val isPreparing: Boolean = false
)

fun calculateActivityCounts(items: List<ProcessCallItem>): List<ActivityCount> {
    val countsMap = LinkedHashMap<ProcessActivityKind, Int>()
    val appearanceMap = LinkedHashMap<ProcessActivityKind, Int>()
    val seenCallIds = HashSet<String>()

    items.forEachIndexed { index, item ->
        if (seenCallIds.add(item.callId)) {
            val kind = item.kind
            countsMap[kind] = (countsMap[kind] ?: 0) + 1
            if (!appearanceMap.containsKey(kind)) {
                appearanceMap[kind] = index
            }
        }
    }

    return countsMap.map { (kind, count) ->
        ActivityCount(kind, count, appearanceMap[kind] ?: 0)
    }.sortedWith(
        compareByDescending<ActivityCount> { it.count }
            .thenBy { it.firstAppearance }
    )
}

fun formatRunDuration(durationMs: Long): String {
    val clamped = maxOf(1000L, durationMs)
    val totalSeconds = clamped / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L

    val sb = StringBuilder()
    if (hours > 0) sb.append("${hours}h")
    if (totalSeconds >= 60) sb.append("${minutes}m")
    sb.append("${seconds}s")
    return sb.toString()
}

data class ChatTurnProcessSpec(
    val turnIndex: Int,
    val isLive: Boolean = false,
    val isStopped: Boolean = false,
    val isError: Boolean = false,
    val durationMs: Long? = null,
    val hasInterleavedUserInput: Boolean = false
) {
    val canFold: Boolean
        get() = !isLive && !isStopped && !isError && !hasInterleavedUserInput

    fun foldLabel(): String = when {
        isStopped -> "Stopped"
        isError -> "Failed"
        durationMs != null -> "Completed in ${formatRunDuration(durationMs)}"
        else -> "Completed"
    }
}

data class TurnItem<T>(
    val data: T,
    val isUser: Boolean,
    val isAnswer: Boolean,
    val isProcess: Boolean
)

sealed interface TurnPart<out T> {
    data class Process<T>(val items: List<T>) : TurnPart<T>
    data class Answer<T>(val item: T) : TurnPart<T>
}

data class SegmentedTurn<T>(
    val turnIndex: Int,
    val userItem: T?,
    val parts: List<TurnPart<T>>,
    val spec: ChatTurnProcessSpec
) {
    val processItems: List<T> get() = parts.filterIsInstance<TurnPart.Process<T>>()
        .flatMap { it.items }
    val answerItems: List<T> get() = parts.filterIsInstance<TurnPart.Answer<T>>()
        .map { it.item }
    val answerItem: T? get() = answerItems.lastOrNull()
}

fun <T> segmentTurnItems(
    items: List<TurnItem<T>>,
    isStreaming: Boolean,
    turnDurationsMs: Map<Int, Long>,
    isInterruptedTurn: Boolean,
    hasError: Boolean
): List<SegmentedTurn<T>> {
    val result = mutableListOf<SegmentedTurn<T>>()
    var currentTurnIndex = 0
    var currentUser: T? = null
    val currentParts = mutableListOf<TurnPart<T>>()
    val pendingProcess = mutableListOf<T>()

    fun flushProcess() {
        if (pendingProcess.isNotEmpty()) {
            currentParts += TurnPart.Process(pendingProcess.toList())
            pendingProcess.clear()
        }
    }

    fun flushTurn(isLast: Boolean) {
        flushProcess()
        if (currentUser == null && currentParts.isEmpty()) return
        val isLive = isLast && isStreaming
        val isStopped = isLast && isInterruptedTurn
        val isError = isLast && hasError
        val duration = turnDurationsMs[currentTurnIndex]
        val spec = ChatTurnProcessSpec(
            turnIndex = currentTurnIndex,
            isLive = isLive,
            isStopped = isStopped,
            isError = isError,
            durationMs = duration,
            hasInterleavedUserInput = false
        )
        result.add(
            SegmentedTurn(
                turnIndex = currentTurnIndex,
                userItem = currentUser,
                parts = currentParts.toList(),
                spec = spec
            )
        )
        currentTurnIndex++
        currentUser = null
        currentParts.clear()
    }

    items.forEach { item ->
        if (item.isUser) {
            flushProcess()
            if (currentUser != null || currentParts.isNotEmpty()) {
                flushTurn(isLast = false)
            }
            currentUser = item.data
        } else if (item.isAnswer) {
            flushProcess()
            currentParts += TurnPart.Answer(item.data)
        } else if (item.isProcess) {
            pendingProcess += item.data
        }
    }
    flushTurn(isLast = true)
    return result
}

