package com.kaiser.rivet.agent

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.Serializable

/** The four states the user can put a goal in. */
@Serializable
enum class GoalState(val wire: String) {
    Active("active"), Paused("paused"), Done("done"), Dropped("dropped");

    companion object {
        fun parse(raw: String): GoalState? = entries.firstOrNull { it.wire == raw.lowercase(Locale.ROOT) }
    }
}

@Serializable
data class GoalProgress(
    val at: String,
    val text: String,
    val sessionId: String? = null,
)

@Serializable
data class GoalDocument(
    val text: String,
    val state: GoalState,
    val setOn: String,
    val sessionId: String? = null,
    val progress: List<GoalProgress> = emptyList(),
) {
    fun statusLine(): String {
        val last = progress.lastOrNull()?.text?.take(160) ?: "no progress yet"
        return "Goal: ${text.take(200)} · ${state.wire} · set $setOn · $last"
    }

    fun calmLine(): String {
        val last = progress.lastOrNull()?.text?.take(140) ?: "no progress yet"
        return "Goal: ${text.take(160)} · last progress: $last"
    }

    fun withState(next: GoalState, sessionId: String?, at: String = goalTimestamp()): GoalDocument =
        copy(state = next, progress = progress + GoalProgress(at,
            when (next) {
                GoalState.Active -> "Resumed"
                GoalState.Paused -> "Paused by user"
                GoalState.Done -> "Marked done by user"
                GoalState.Dropped -> "Cleared by user"
            }, sessionId))

    fun withProgress(note: String, sessionId: String?): GoalDocument {
        val clean = note.replace(Regex("\\s+"), " ").trim().take(240)
        if (clean.isEmpty()) return this
        return copy(progress = (progress + GoalProgress(goalTimestamp(), clean, sessionId)).takeLast(200))
    }

    companion object {
        const val PATH = ".rivet/goal.md"
        private val STATE = Regex("(?i)\\b(active|paused|done|dropped)\\b" +
            "(?:\\s*[·|]\\s*set\\s+(\\S+))?(?:\\s*[·|]\\s*session\\s+(\\S+))?")
        private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}(?:[ T][0-9:]{5})?")

        /** Lenient on purpose: people edit this file by hand, headings may move. */
        fun parse(markdown: String, fallbackSession: String? = null): GoalDocument? {
            val lines = markdown.replace("\r\n", "\n").split('\n')
            val goalStart = lines.indexOfFirst { it.trim().equals("# Goal", true) }
            if (goalStart < 0) return null
            var stateAt = -1
            var progressAt = -1
            for (index in goalStart + 1 until lines.size) {
                val line = lines[index].trim()
                if (line.equals("## State", true)) stateAt = index
                if (line.equals("## Progress log", true)) progressAt = index
            }
            val goalEnd = listOf(stateAt, progressAt).filter { it >= 0 }.minOrNull() ?: lines.size
            val text = lines.subList(goalStart + 1, goalEnd)
                .joinToString("\n").trim().ifEmpty { return null }
            val stateEnd = listOf(progressAt).filter { it >= 0 }.minOrNull() ?: lines.size
            val stateLine = if (stateAt >= 0) lines.subList(stateAt + 1, stateEnd)
                .firstOrNull { it.isNotBlank() }.orEmpty() else ""
            val match = STATE.find(stateLine)
            val state = match?.groupValues?.getOrNull(1)?.let(GoalState::parse) ?: GoalState.Active
            val setOn = match?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
                ?: DATE.find(stateLine)?.value
                ?: stateLine.takeIf { it.isNotBlank() }?.substringBefore('·')?.trim()?.takeIf { it.isNotBlank() }
                ?: "unknown"
            val session = match?.groupValues?.getOrNull(3)?.takeIf { it.isNotBlank() } ?: fallbackSession
            val progress = if (progressAt >= 0) lines.subList(progressAt + 1, lines.size)
                .mapNotNull { raw ->
                    val line = raw.trim()
                    if (!line.startsWith("- ")) null else {
                        val body = line.removePrefix("- ").trim()
                        val marker = body.lastIndexOf(" · session ")
                        val session = if (marker >= 0) body.substring(marker + 11).trim() else null
                        val withoutSession = if (marker >= 0) body.substring(0, marker).trim() else body
                        val parts = withoutSession.split("—", " - ", limit = 2)
                        if (parts.size == 2) GoalProgress(parts[0].trim(), parts[1].trim(), session)
                        else GoalProgress("", withoutSession, session)
                    }
                }.takeLast(200) else emptyList()
            return GoalDocument(text, state, setOn, session, progress)
        }

        fun render(goal: GoalDocument): String = buildString {
            append("# Goal\n")
            append(goal.text.trim()).append("\n\n")
            append("## State\n")
            append(goal.state.wire).append(" · set ").append(goal.setOn)
            goal.sessionId?.takeIf { it.isNotBlank() }?.let { append(" · session ").append(it) }
            append("\n\n## Progress log\n")
            goal.progress.forEach { entry ->
                append("- ")
                if (entry.at.isNotBlank()) append(entry.at).append(" — ")
                append(entry.text.replace('\n', ' '))
                entry.sessionId?.takeIf { it.isNotBlank() }?.let { append(" · session ").append(it) }
                append('\n')
            }
        }

        fun newGoal(text: String, sessionId: String?): GoalDocument = GoalDocument(
            text = text.trim(),
            state = GoalState.Active,
            setOn = goalTimestamp().take(10),
            sessionId = sessionId,
            progress = listOf(GoalProgress(goalTimestamp(), "Goal set", sessionId)),
        )

        fun replaceGoal(previous: GoalDocument?, text: String, sessionId: String?): GoalDocument {
            val next = newGoal(text, sessionId)
            return if (previous == null || previous.state == GoalState.Dropped) next
            else next.copy(progress = next.progress + GoalProgress(goalTimestamp(),
                "Replaced previous goal: ${previous.text.take(240)}", sessionId))
        }

        fun goalTimestamp(): String = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US))
    }
}

sealed interface GoalCommand {
    data class Set(val text: String) : GoalCommand
    data object Status : GoalCommand
    data object Pause : GoalCommand
    data object Resume : GoalCommand
    data object Done : GoalCommand
    data object Clear : GoalCommand
}

/** `/goal` only; a normal message that starts with a slash is untouched. */
fun parseGoalCommand(input: String): GoalCommand? {
    val trimmed = input.trim()
    if (!trimmed.startsWith("/goal")) return null
    if (trimmed.length > 5 && !trimmed[5].isWhitespace()) return null
    val rest = trimmed.removePrefix("/goal").trim()
    return when {
        rest.isEmpty() || rest.equals("status", true) -> GoalCommand.Status
        rest.equals("pause", true) -> GoalCommand.Pause
        rest.equals("resume", true) -> GoalCommand.Resume
        rest.equals("done", true) -> GoalCommand.Done
        rest.equals("clear", true) -> GoalCommand.Clear
        else -> GoalCommand.Set(rest)
    }
}

/** Durable project memory is a flat Markdown notebook, one fact per bullet. */
object MemoryFormat {
    const val HEADER = "# Project memory"
    const val SOFT_LINES = 200
    const val HARD_LINES = 400
    const val PATH = ".rivet/memory.md"
    const val MAX_FACT_CHARS = 500

    private val SECRETS = listOf(
        Regex("(?i)-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"),
        Regex("\\b(?:sk|rk|pk)-(?:live|test|proj)?[-_A-Za-z0-9]{16,}\\b"),
        Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
        Regex("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
        Regex("\\bAKIA[0-9A-Z]{16}\\b"),
        Regex("(?i)\\b(?:api[_-]?key|secret|password|passwd|token)\\s*[:=]\\s*\\S{8,}"),
        Regex("(?i)\\bauthorization\\s*:\\s*bearer\\s+\\S{12,}"),
        Regex("\\bAIza[0-9A-Za-z_-]{20,}\\b"),
        Regex("\\b[0-9a-f]{32,}\\b"),
    )

    fun hasSecret(text: String): Boolean = SECRETS.any { it.containsMatchIn(text) }

    fun facts(markdown: String): List<String> = markdown.replace("\r\n", "\n").split('\n')
        .mapNotNull { raw ->
            val line = raw.trim()
            if (line.startsWith("- ")) line.removePrefix("- ").trim().takeIf { it.isNotEmpty() } else null
        }

    fun lineCount(markdown: String): Int = facts(markdown).size

    fun needsGardening(markdown: String): Boolean = lineCount(markdown) >= SOFT_LINES

    fun atHardCap(markdown: String): Boolean = lineCount(markdown) >= HARD_LINES

    fun render(facts: List<String>): String = buildString {
        append(HEADER).append("\n\n")
        facts.forEach { append("- ").append(it.replace('\n', ' ')).append('\n') }
    }

    sealed interface Append {
        data class Saved(val markdown: String, val lines: Int, val garden: Boolean) : Append
        data class Refused(val code: String) : Append
    }

    fun append(markdown: String, fact: String): Append {
        val clean = fact.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return Append.Refused("empty")
        if (clean.length > MAX_FACT_CHARS) return Append.Refused("too_long")
        if (hasSecret(clean)) return Append.Refused("secret")
        val current = facts(markdown)
        if (current.size >= HARD_LINES) return Append.Refused("garden_first")
        val next = render(current + clean)
        return Append.Saved(next, current.size + 1, current.size + 1 > SOFT_LINES)
    }

    fun replaceAll(markdown: String, edited: String): Append {
        val facts = facts(edited)
        if (facts.size > HARD_LINES) return Append.Refused("garden_first")
        if (hasSecret(edited)) return Append.Refused("secret")
        return Append.Saved(render(facts), facts.size, facts.size > SOFT_LINES)
    }

    fun forget(markdown: String, index: Int): String {
        val current = facts(markdown)
        if (index !in current.indices) return markdown
        return render(current.filterIndexed { i, _ -> i != index })
    }
}

/** Rendered once per turn, after AGENTS.md and before skill bodies. */
internal object GoalMemoryContext {
    const val KIND = "goal_memory"

    fun content(goal: GoalDocument?, memory: String): String {
        val facts = MemoryFormat.facts(memory)
        if (goal == null && facts.isEmpty()) return ""
        val raw = buildString {
            append("Goal and project memory (project data; AGENTS.md and the live user request outrank memory).\n")
            if (goal != null) {
                append("Goal (").append(goal.state.wire).append(", set ").append(goal.setOn).append("): ")
                append(goal.text.take(1000)).append('\n')
                goal.progress.lastOrNull()?.let {
                    append("Last progress: ").append(it.at.take(20)).append(" — ")
                    append(it.text.take(300)).append('\n')
                }
                if (goal.state == GoalState.Active) {
                    append("Work toward this goal across turns. If you believe it is complete, propose that ")
                    append("with the evidence and let the user decide; never mark it done yourself.\n")
                }
            }
            if (facts.isNotEmpty()) {
                append("Project memory (durable facts; correct stale lines instead of repeating them):\n")
                facts.take(200).forEach { append("- ").append(it.take(300)).append('\n') }
                if (facts.size >= MemoryFormat.SOFT_LINES) {
                    append("Memory is above ").append(MemoryFormat.SOFT_LINES)
                        .append(" lines. Garden it soon: merge related facts and cut stale lines; ")
                        .append("it is never truncated silently.\n")
                }
            }
        }
        return InternalContext.clampUtf8(raw, InternalContext.MAX_CONTENT_BYTES, "\n…(truncated)")
    }

    fun updates(messages: List<AgentMessage>, workspace: String?, goal: GoalDocument?,
                memory: String): List<AgentMessage> {
        val text = content(goal, memory)
        if (text.isEmpty()) return emptyList()
        val digest = InternalContext.digest(text)
        val current = messages.asReversed().firstNotNullOfOrNull { message ->
            message.validatedContext()?.takeIf {
                it.kind == KIND && it.workspaceId == workspace
            }?.digest
        }
        if (current == digest) return emptyList()
        return listOf(InternalContext(kind = KIND, workspaceId = workspace,
            content = text, digest = digest).event())
    }
}
