package com.kaiser.rivet.local

import com.kaiser.rivet.agent.AgentToolDefinition
import kotlinx.serialization.json.jsonObject

/**
 * The exact system text and tool list the local provider sends. Context preparation
 * measures this shape, not the larger cloud tool catalogue.
 *
 * A 4K window cannot carry the full coding system prompt plus seven schemas. For those
 * models we keep the safety sentence, path rule, and argument names, and drop the long
 * catalogue prose. The provider still gets the same tool names and JSON contracts.
 */
internal data class LocalWireRequest(
    val system: String,
    val tools: List<AgentToolDefinition>,
)

internal object LocalPrompt {
    const val MARKER = "\n\n## Local tools\n"
    private const val COMPACT_WINDOW = 4096

    fun wire(baseSystem: String, tools: List<AgentToolDefinition>, contextWindow: Int,
             workspace: Boolean): LocalWireRequest {
        val profiled = LocalToolProtocol.profile(tools)
        val compact = contextWindow in 1..COMPACT_WINDOW
        val sentTools = if (compact) profiled.map { it.copy(description = "") } else profiled
        val system = if (compact) {
            LocalToolProtocol.compactSystem(workspace) + LocalToolProtocol.systemSuffix(sentTools, compact = true)
        } else {
            baseSystem + LocalToolProtocol.systemSuffix(sentTools)
        }
        return LocalWireRequest(system, sentTools)
    }

    /** True when [system] was assembled by [wire] and must not be appended to twice. */
    fun isWired(system: String): Boolean = system.contains(MARKER)
}
