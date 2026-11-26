package com.kaiser.rivet.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class TaskCheckpoint(
    @SerialName("GOAL") val goal: String = "",
    @SerialName("DECISIONS + WHY") val decisions: List<String> = emptyList(),
    @SerialName("CONSTRAINTS & PREFERENCES") val constraints: List<String> = emptyList(),
    @SerialName("STATE") val state: List<String> = emptyList(),
    @SerialName("OPEN THREADS") val openThreads: List<String> = emptyList(),
    @SerialName("EXACT VALUES") val exactValues: List<String> = emptyList(),
    @SerialName("USER QUOTES") val userQuotes: List<String> = emptyList(),
    @SerialName("ARCHIVE INDEX") val archiveIndex: List<String> = emptyList(),
) {
    fun merge(update: TaskCheckpoint, pinnedGoal: String): TaskCheckpoint {
        val threads = safeItems(update.openThreads)
        val resolved = threads.filter { it.startsWith(RESOLVED) }
            .map { it.removePrefix(RESOLVED) }.toSet()
        return TaskCheckpoint(
            goal = pinnedGoal,
            decisions = mergeItems(decisions, update.decisions),
            constraints = mergeItems(constraints, update.constraints),
            state = (state + safeItems(update.state)).distinct(),
            openThreads = (openThreads.filterNot { it in resolved } +
                threads.filterNot { it.startsWith(RESOLVED) }).distinct(),
            exactValues = (exactValues + safeItems(update.exactValues)).distinct(),
            userQuotes = userQuotes,
            archiveIndex = (safeItems(update.archiveIndex).map { it.take(120) } + archiveIndex).distinct().take(8),
        )
    }

    fun withUserAnchors(removed: List<AgentMessage>): TaskCheckpoint {
        val lines = removed.asReversed().asSequence().filter { it.role == AgentRole.User }
            .flatMap { message ->
                if (message.text.toByteArray(Charsets.UTF_8).size <= 320) sequenceOf(message.text)
                else message.text.lineSequence()
            }
            .filter { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 320 && AgentContext.redact(it) == it }
        val candidates = (lines + userQuotes.asSequence()).distinct().toList()
        val (pinned, recent) = candidates.partition { line ->
            !line.trimEnd().endsWith('?') && pinnedQuote.containsMatchIn(line)
        }
        return copy(userQuotes = (pinned + recent).take(8))
    }

    fun fit(maxBytes: Int): TaskCheckpoint? {
        val withoutState = copy(state = emptyList())
        if (withoutState.encode().toByteArray(Charsets.UTF_8).size > maxBytes) {
            var candidate = withoutState
            while (candidate.encode().toByteArray(Charsets.UTF_8).size > maxBytes) {
                candidate = when {
                    candidate.archiveIndex.isNotEmpty() -> candidate.copy(archiveIndex = candidate.archiveIndex.dropLast(1))
                    candidate.exactValues.isNotEmpty() -> candidate.copy(exactValues = candidate.exactValues.drop(1))
                    candidate.userQuotes.isNotEmpty() -> candidate.copy(userQuotes = candidate.userQuotes.dropLast(1))
                    else -> return null
                }
            }
            return candidate
        }
        for (first in state.indices) {
            val candidate = if (first == 0) this else copy(state = state.drop(first))
            if (candidate.encode().toByteArray(Charsets.UTF_8).size <= maxBytes) return candidate
        }
        return withoutState
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    fun modelText(): String = buildString {
        append("GOAL\n").append(goal).append('\n')
        fun section(heading: String, items: List<String>) {
            append('\n').append(heading).append('\n')
            items.forEach { append("- ").append(it).append('\n') }
        }
        section("DECISIONS + WHY", decisions)
        section("CONSTRAINTS & PREFERENCES", constraints)
        section("STATE", state)
        section("OPEN THREADS", openThreads)
        section("EXACT VALUES", exactValues)
        if (userQuotes.isNotEmpty()) section("USER QUOTES (verbatim)", userQuotes)
        section("ARCHIVE INDEX (search words)", archiveIndex)
    }

    private fun mergeItems(previous: List<String>, updates: List<String>): List<String> {
        val incoming = safeItems(updates)
        val superseded = incoming.filter { it.startsWith(SUPERSEDED) }
            .map { it.removePrefix(SUPERSEDED) }.toSet()
        val carried = previous.map { if (it in superseded) "$SUPERSEDED$it" else it }
        return (carried + incoming.filterNot { it.startsWith(SUPERSEDED) }).distinct()
    }

    companion object {
        private const val SUPERSEDED = "SUPERSEDED: "
        private const val RESOLVED = "RESOLVED: "
        private val pinnedQuote = Regex("""\b(?:remember|pin|pinned)\b""", RegexOption.IGNORE_CASE)
        private val json = Json { encodeDefaults = true }

        private fun safeItems(items: List<String>) = items.map(AgentContext::redact).filter(String::isNotBlank)

        fun parse(raw: String): TaskCheckpoint? {
            val text = raw.trim().let { value ->
                if (value.startsWith("```")) value.substringAfter('\n', "").substringBeforeLast("```").trim()
                else value
            }
            if (text.toByteArray(Charsets.UTF_8).size > TaskState.MAX_BYTES) return null
            return try { json.decodeFromString(serializer(), text) }
            catch (_: IllegalArgumentException) { null }
        }

        fun previous(raw: String): TaskCheckpoint {
            parse(raw)?.let { return it }
            val legacy = TaskState.parse(raw) ?: return TaskCheckpoint(state = safeItems(listOf(raw)))
            return TaskCheckpoint(
                goal = legacy.objective,
                decisions = legacy.decisions,
                constraints = legacy.userConstraints,
                state = safeItems(legacy.completed + listOf(legacy.current, legacy.verification)),
                openThreads = safeItems(legacy.pending + legacy.failures + legacy.nextStep),
                exactValues = legacy.importantFiles,
            )
        }
    }
}
