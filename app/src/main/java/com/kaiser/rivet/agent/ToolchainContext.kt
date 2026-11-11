package com.kaiser.rivet.agent

import com.kaiser.rivet.runtime.ToolchainAvailability

/**
 * Per-turn availability, not a system-prompt fact. A conversation carried over from an
 * older build must not keep claiming Python/Git are missing after the toolchain arrives.
 * This rides the same InternalContext digest machinery as the skill catalog.
 */
internal object ToolchainContext {
    const val KIND = "toolchain_availability"

    fun line(availability: ToolchainAvailability): String = buildString {
        append("Toolchain availability: BusyBox ")
        append(if (availability.busybox) "available" else "unavailable")
        append("; Python 3.12.3 ")
        append(if (availability.python) "available" else "unavailable")
        append("; Git 2.56 ")
        append(if (availability.git) "available" else "unavailable")
        if (availability.writableEntryPoints.isNotEmpty()) {
            append("; TOOLCHAIN AUDIT FAILED: writable user entry points ")
            append(availability.writableEntryPoints.joinToString(", "))
        }
        append(". HTTP downloads use busybox wget; curl is not part of this toolchain.")
    }

    fun updates(messages: List<AgentMessage>, workspace: String,
                availability: ToolchainAvailability): List<AgentMessage> {
        val text = line(availability)
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
