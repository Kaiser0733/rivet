package com.kaiser.rivet.agent

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("OMITTED") val omitted: Boolean = false,
) {
    fun merge(update: TaskCheckpoint, pinnedGoal: String): TaskCheckpoint {
        val threads = safeItems(update.openThreads)
        val resolved = threads.filter { it.startsWith(RESOLVED) }
            .map { it.removePrefix(RESOLVED) }.toSet()
        val archive = (safeItems(update.archiveIndex).map { it.take(120) } + archiveIndex).distinct()
        return TaskCheckpoint(
            goal = pinnedGoal,
            decisions = mergeItems(decisions, update.decisions),
            constraints = mergeItems(constraints, update.constraints),
            state = (state + safeItems(update.state)).distinct(),
            openThreads = (openThreads.filterNot { it in resolved } +
                threads.filterNot { it.startsWith(RESOLVED) }).distinct(),
            exactValues = (exactValues + safeItems(update.exactValues)).distinct(),
            userQuotes = userQuotes,
            archiveIndex = archive.take(8),
            omitted = omitted || archive.size > 8,
        )
    }

    fun withUserAnchors(removed: List<AgentMessage>): TaskCheckpoint {
        val lines = removed.asReversed().asSequence().filter { it.role == AgentRole.User }
            .flatMap { message ->
                if (message.text.toByteArray(Charsets.UTF_8).size <= 320) sequenceOf(message.text)
                else message.text.lineSequence()
            }
            .filter { it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 320 && AgentContext.redact(it) == it }
        val quotes = (lines + userQuotes.asSequence()).distinct().take(9).toList()
        return copy(userQuotes = quotes.take(8), omitted = omitted || quotes.size > 8)
    }

    fun fit(maxBytes: Int): TaskCheckpoint? {
        if (encode().toByteArray(Charsets.UTF_8).size <= maxBytes) return this
        val withoutState = copy(state = emptyList(), omitted = true)
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
        for (first in 1 until state.size) {
            val candidate = copy(state = state.drop(first), omitted = true)
            if (candidate.encode().toByteArray(Charsets.UTF_8).size <= maxBytes) return candidate
        }
        return withoutState
    }

    fun encode(): String = json.encodeToString(serializer(), this)

    fun modelText(): String = buildString {
        if (omitted) append("Some older checkpoint details were omitted.\n\n")
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
            val legacy = TaskState.parse(raw)
                ?: return TaskCheckpoint(state = safeItems(listOf(raw)), omitted = raw.isNotBlank())
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
