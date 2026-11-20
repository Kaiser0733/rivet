package com.kaiser.rivet.replay

import com.kaiser.rivet.agent.AgentActivityProjection
import com.kaiser.rivet.agent.AgentConversationItem
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.AgentRole
import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.agent.RunRecord

internal sealed interface ReplayBeat {
    val holdMs: Long

    data class PromptCard(val text: String, override val holdMs: Long) : ReplayBeat
    data class StepCard(val title: String, val detail: String?, override val holdMs: Long) : ReplayBeat
    data class PreviewCard(val shotPath: String, val caption: String, override val holdMs: Long) : ReplayBeat
    data class ResultCard(val text: String, override val holdMs: Long) : ReplayBeat
}

internal data class ReplayStory(
    val projectName: String,
    val handle: String?,
    val beats: List<ReplayBeat>,
    val totalMs: Long,
) {
    companion object {
        private const val MAX_BEATS = 12
        private const val MAX_PREVIEWS = 3

        /** The transcript is the selected turn's slice of the visible session history. */
        fun from(
            run: RunRecord,
            transcript: List<AgentMessage>,
            projectName: String,
            handle: String? = null,
            availableShots: Set<String> = emptySet(),
            outcomeSaved: Boolean = true,
        ): ReplayStory {
            val visible = transcript.filter { it.internalContext == null && it.role != AgentRole.Context }
            val prompt = visible.firstOrNull { it.role == AgentRole.User }?.text
                ?.takeIf(String::isNotBlank) ?: run.title.ifBlank { "No prompt saved." }
            val groups = AgentActivityProjection.conversation(visible)
                .filterIsInstance<AgentConversationItem.Activity>()
                .map { it.group }
            val previews = visible.asSequence().flatMap { it.toolResults.asSequence() }
                .filter { !it.error && it.name in setOf("browser_shot", "phone_shot") }
                .mapNotNull { result ->
                    val path = result.imagePath?.takeIf { it in availableShots } ?: return@mapNotNull null
                    path to if (result.name == "phone_shot") "Phone screenshot" else "Browser screenshot"
                }.distinctBy { it.first }.take(MAX_PREVIEWS).toList()
            val stepLimit = MAX_BEATS - 2 - previews.size
            val steps = if (groups.isEmpty()) {
                run.steps.takeLast(stepLimit).map { "Step ${it.index}" to it.summary }
            } else {
                groups.takeLast(stepLimit).map { group ->
                    group.summary to group.operations.firstNotNullOfOrNull {
                        it.detail.takeIf(String::isNotBlank)
                    }
                }
            }
            val answer = visible.lastOrNull {
                it.role == AgentRole.Assistant && it.toolCalls.isEmpty() && it.text.isNotBlank()
            }?.text?.let { excerpt(it, 390) }
            val result = if (!outcomeSaved) {
                if (answer == null) "Run outcome wasn't saved." else "Run outcome wasn't saved.\n\nLast reply\n$answer"
            } else when (run.phase) {
                RunPhase.Done -> answer ?: "Run finished."
                RunPhase.Failed -> "The run failed."
                RunPhase.Stopped -> "The run was stopped."
                RunPhase.Paused -> "The run paused."
                RunPhase.Queued, RunPhase.Running -> "The run was still in progress."
            }

            val totalMs = (run.updatedAt - run.startedAt).coerceIn(30_000L, 60_000L)
            val weight = 5 + steps.size * 4 + previews.size * 6 + 6
            var elapsedWeight = 0
            fun hold(units: Int): Long {
                val before = totalMs * elapsedWeight / weight
                elapsedWeight += units
                return totalMs * elapsedWeight / weight - before
            }
            val beats = buildList {
                add(ReplayBeat.PromptCard(excerpt(prompt, 280), hold(5)))
                steps.forEach { (title, detail) ->
                    add(ReplayBeat.StepCard(excerpt(title, 140), detail?.let { excerpt(it, 140) }, hold(4)))
                }
                previews.forEach { (path, caption) -> add(ReplayBeat.PreviewCard(path, caption, hold(6))) }
                add(ReplayBeat.ResultCard(result, hold(6)))
            }
            return ReplayStory(excerpt(projectName, 80), handle?.let { excerpt(it, 60) }, beats, totalMs)
        }

        private fun excerpt(value: String, limit: Int): String {
            val text = value.trim()
            if (text.length <= limit) return text
            return text.take(limit - 1).dropLastWhile { it.isHighSurrogate() }.trimEnd() + "…"
        }
    }
}
