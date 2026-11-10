package com.kaiser.rivet.local

/**
 * Some small models narrate the tools they are looking at instead of emitting a
 * tagged reasoning block. Only strip that opening narration when it names a real
 * local tool and uses one of the known "I am looking at the tools" openings; a
 * normal answer that happens to mention a tool is left alone.
 */
internal fun stripUntaggedToolMusing(text: String): String {
    val lines = text.lines()
    val firstIndex = lines.indexOfFirst { it.isNotBlank() }
    if (firstIndex < 0) return text
    val first = lines[firstIndex].trim()
    if (!first.contains("tool", ignoreCase = true)) return text
    if (LocalToolProtocol.coreTools.none { first.contains(it, ignoreCase = true) }) return text
    val openings = listOf("looking at the tools", "tools available", "available tools",
        "i have access to")
    if (openings.none { first.contains(it, ignoreCase = true) }) return text
    return lines.drop(firstIndex + 1).joinToString("\n").trim()
}
