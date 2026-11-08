package com.kaiser.rivet.agent

/**
 * The canonical Level-2 block, copied from dsh-tool-skill's `renderSkillContent` so the
 * model sees the same shape whether a skill was invoked manually or loaded by the tool.
 *
 * The body is embedded verbatim (skills are trusted local content); the name rides an
 * escaped attribute and the base directory is escaped prose so neither can open or close
 * the framing tags. The `<skill_resources>` hint is dsh's Level-3 mechanism: bundled files
 * are never pre-loaded, the agent reads them with its normal tools relative to [baseDir].
 */
fun renderSkillContent(skill: Skill, baseDir: String): String = buildString {
    append("<skill_content name=\"").append(escapeAttribute(skill.name)).append("\">\n")
    append("<skill_resources>\n")
    append("Base directory for this skill: ").append(escapeText(baseDir)).append('\n')
    append("Resolve relative paths mentioned by this skill against the base directory before using them. ")
    append("Load referenced resources only as needed.\n")
    append("</skill_resources>\n\n")
    append("<skill_instructions>\n")
    append(skill.body).append('\n')
    append("</skill_instructions>\n")
    append("</skill_content>")
}

private fun escapeAttribute(value: String): String =
    value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

private fun escapeText(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
