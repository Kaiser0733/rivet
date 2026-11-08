package com.kaiser.rivet.agent

/**
 * The Level-1 skill listing, carried as a normal context snapshot so it rides the same
 * digest-diff machinery as project instructions: published once, then republished only
 * when the rendered listing changes. [modelMessages] keeps only the newest one, so the
 * model sees a replacement rather than a stack.
 */
internal object SkillContext {
    const val KIND = SKILL_CATALOG_KIND
    const val MAX_DESCRIPTION_CHARS = 500

    private const val INTRO = "If the user names a skill, or the task clearly matches a skill's " +
        "description, call the `skill` tool with the exact skill name before taking task actions. " +
        "Load all applicable skills, then follow their full instructions. This catalog contains " +
        "summaries only; do not infer or follow a skill's instructions until it has been loaded."

    /** Empty when nothing is listed, so a skill-less install adds no context at all. */
    fun listing(catalog: SkillCatalog?): String {
        val listed = catalog?.skills.orEmpty().filter { it.modelInvocable }
        if (listed.isEmpty()) return ""
        val budget = InternalContext.MAX_CONTENT_BYTES - 1024
        var bytes = "<available_skills>\n\n</available_skills>\n\n$INTRO"
            .toByteArray(Charsets.UTF_8).size
        val lines = mutableListOf<String>()
        var dropped = 0
        for (skill in listed) {
            val line = "- `${skill.name}`: ${catalogDescription(skill.description)}"
            val size = line.toByteArray(Charsets.UTF_8).size + 1
            if (bytes + size > budget) {
                dropped++
                continue
            }
            bytes += size
            lines += line
        }
        return buildString {
            append("<available_skills>\n")
            lines.forEach { append(it).append('\n') }
            append("</available_skills>\n\n")
            append(INTRO)
            if (dropped > 0) append("\n($dropped more installed skills are not listed in this catalog.)")
        }
    }

    fun updates(messages: List<AgentMessage>, workspace: String, catalog: SkillCatalog?): List<AgentMessage> {
        val text = listing(catalog)
        if (text.isEmpty()) return emptyList()
        val digest = InternalContext.digest(text)
        val current = messages.asReversed().firstNotNullOfOrNull { message ->
            message.validatedContext()?.takeIf { it.kind == KIND && it.workspaceId == workspace }?.digest
        }
        if (current == digest) return emptyList()
        return listOf(InternalContext(kind = KIND, workspaceId = workspace,
            content = text, digest = digest).event())
    }

    /**
     * The Level-2 attachment for a manual `/name` invocation: the rendered block, clamped
     * to the context limit so an oversized SKILL.md cannot invalidate the turn.
     */
    fun invocation(skill: Skill, workspace: String): AgentMessage {
        val block = renderSkillContent(skill, skill.source?.baseDir.orEmpty())
        val marker = "\n\n(skill content truncated to fit the context limit)"
        val bytes = block.toByteArray(Charsets.UTF_8)
        val content = if (bytes.size <= InternalContext.MAX_CONTENT_BYTES) block else
            bytes.copyOf(InternalContext.MAX_CONTENT_BYTES - marker.toByteArray(Charsets.UTF_8).size)
                .toString(Charsets.UTF_8).dropLastWhile { it.isHighSurrogate() } + marker
        return InternalContext(kind = SKILL_INVOCATION_KIND, workspaceId = workspace,
            content = content, digest = InternalContext.digest(content)).event()
    }

    private fun catalogDescription(value: String): String {
        val normalized = escapeText(value.replace(Regex("\\s+"), " ").trim())
        if (normalized.length <= MAX_DESCRIPTION_CHARS) return normalized
        return normalized.take(MAX_DESCRIPTION_CHARS - 3) + "..."
    }

    private fun escapeText(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
