package com.kaiser.rivet.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Model-generated task notes; never a source of policy or workspace truth. */
@Serializable
data class TaskState(
    val objective: String,
    val userConstraints: List<String> = emptyList(),
    val decisions: List<String> = emptyList(),
    val completed: List<String> = emptyList(),
    val current: String = "",
    val pending: List<String> = emptyList(),
    val failures: List<String> = emptyList(),
    val importantFiles: List<String> = emptyList(),
    val verification: String = "",
    val nextStep: String = "",
) {
    fun encode(): String = Json.encodeToString(serializer(), redacted()).also {
        require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
    }

    private fun redacted() = copy(
        objective = AgentContext.redact(objective),
        userConstraints = userConstraints.map(AgentContext::redact),
        decisions = decisions.map(AgentContext::redact),
        completed = completed.map(AgentContext::redact),
        current = AgentContext.redact(current),
        pending = pending.map(AgentContext::redact),
        failures = failures.map(AgentContext::redact),
        importantFiles = importantFiles.map(AgentContext::redact),
        verification = AgentContext.redact(verification),
        nextStep = AgentContext.redact(nextStep),
    )

    companion object {
        const val MAX_BYTES = 8 * 1024
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): TaskState? {
            val text = raw.trim().let { value ->
                if (value.startsWith("```")) value.substringAfter('\n', "").substringBeforeLast("```").trim()
                else value
            }
            if (text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
            val state = try { json.decodeFromString(serializer(), text) }
                catch (_: Exception) { return null }
            val scalars = listOf(state.objective, state.current, state.verification, state.nextStep)
            val lists = listOf(state.userConstraints, state.decisions, state.completed,
                state.pending, state.failures, state.importantFiles)
            if (state.objective.isBlank() || scalars.any { it.length > 1000 } ||
                lists.any { it.size > 20 || it.any { item -> item.length > 800 } }) return null
            return try { state.redacted().also { it.encode() } }
                catch (_: IllegalArgumentException) { null }
        }
    }
}
