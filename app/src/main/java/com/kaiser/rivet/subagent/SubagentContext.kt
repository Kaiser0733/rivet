package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.AgentContext
import com.kaiser.rivet.agent.AgentMessage
import com.kaiser.rivet.agent.InternalContext
import com.kaiser.rivet.agent.validatedContext
import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType

/**
 * Digest-diffed `subagent_pool` snapshot and per-worker `subagent_result` events
 * delivered through `InternalContext`.
 */
object SubagentContext {
    const val POOL_KIND = "subagent_pool"
    const val RESULT_KIND = "subagent_result"

    const val OFF_LINE =
        "Subagents are off in Settings → Subagents. Do not call spawn_subagent unless the user asks to set them up."

    fun poolContent(
        state: SubagentPoolState,
        configs: List<ProviderConfig>,
        coordinatorConfig: ProviderConfig? = null,
    ): String {
        val resolved = SubagentPool.resolve(state, configs)
        if (!resolved.canSpawn) return resolved.offLine ?: OFF_LINE
        return buildString {
            appendLine("Subagent pool:")
            val activeEntries = if (state.allowAgentChoice) resolved.entries else listOfNotNull(resolved.fixed)
            if (state.allowAgentChoice) {
                appendLine("Mode: agent may choose (pass model alias below to spawn_subagent)")
            } else {
                appendLine("Mode: fixed: ${resolved.fixed?.label ?: activeEntries.first().label}")
            }
            appendLine("Models:")
            for (entry in activeEntries) {
                appendLine("- ${entry.alias}: ${entry.label}")
            }
            appendLine("Caps: max ${SubagentScheduler.MAX_RUNNING} running, at most ${SubagentScheduler.MAX_LOCAL} local")
            if (activeEntries.any { it.local }) {
                if (coordinatorConfig?.type == ProviderType.Local) {
                    appendLine("Local note: local inference time-shares one slot with this local coordinator.")
                } else {
                    appendLine("Local note: local inference time-shares one slot when a local coordinator is active.")
                }
            }
            appendLine("Rules:")
            appendLine("1. Do simple or 1–2 file work yourself.")
            appendLine("2. Spawn for parallel research, independent verification, or non-overlapping file areas.")
            appendLine("3. Declare exact write_scope paths ([] for read-only); overlapping scopes queue instead of running in parallel.")
            appendLine("4. spawn_subagent returns immediately; do your own non-overlapping work before calling await_subagents.")
            append("5. Never re-run a worker's commands to \"double check\" unless its result reported a failure or open question.")
        }
    }

    fun poolUpdates(
        messages: List<AgentMessage>,
        workspace: String,
        state: SubagentPoolState,
        configs: List<ProviderConfig>,
        coordinatorConfig: ProviderConfig? = null,
    ): List<AgentMessage> {
        val content = poolContent(state, configs, coordinatorConfig)
        val digest = InternalContext.digest(content)
        val current = messages.asReversed().firstNotNullOfOrNull { message ->
            message.validatedContext()?.takeIf {
                it.kind == POOL_KIND && it.workspaceId == workspace
            }?.digest
        }
        if (current == digest) return emptyList()
        return listOf(
            InternalContext(
                kind = POOL_KIND,
                workspaceId = workspace,
                content = content,
                digest = digest,
            ).event(),
        )
    }

    fun resultContent(workerId: String, job: String, envelopeJson: String): String {
        val header = "Subagent finished ($workerId · ${job.trim()}):\n"
        val budget = (InternalContext.MAX_CONTENT_BYTES - header.toByteArray(Charsets.UTF_8).size).coerceAtLeast(0)
        val redacted = AgentContext.redact(envelopeJson)
        val clamped = InternalContext.clampUtf8(redacted, budget)
        return header + clamped
    }

    fun resultEvent(
        workspace: String,
        workerId: String,
        job: String,
        envelopeJson: String,
    ): AgentMessage {
        val content = resultContent(workerId, job, envelopeJson)
        return InternalContext(
            kind = RESULT_KIND,
            workspaceId = workspace,
            content = content,
            digest = InternalContext.digest(content),
        ).event()
    }
}
