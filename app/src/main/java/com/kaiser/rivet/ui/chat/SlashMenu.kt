package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.Skill

internal enum class SlashAction(val token: String) {
    Goal("goal"), Feedback("feedback"), Compact("compact"), Yolo("yolo"), BasicYolo("basic-yolo"),
}

internal data class SlashRow(
    val group: String?,
    val name: String,
    val description: String,
    val action: SlashAction? = null,
    val skill: Skill? = null,
    val enabled: Boolean = true,
)

internal fun skillCommandQuery(text: String): String? {
    if (!text.startsWith("/")) return null
    return text.drop(1).takeUnless { it.any(Char::isWhitespace) }
}

internal fun slashRows(installed: List<Skill>, query: String, compactEnabled: Boolean = true): List<SlashRow> = buildList {
    fun row(group: String?, name: String, description: String, action: SlashAction) {
        if (action.token.startsWith(query, ignoreCase = true) ||
            name.startsWith(query, ignoreCase = true)) {
            add(SlashRow(group, name, description, action = action,
                enabled = action != SlashAction.Compact || compactEnabled))
        }
    }
    row(null, "Goal", "Set or view the goal for a long-running task", SlashAction.Goal)
    row(null, "Feedback", "Record feedback about this session", SlashAction.Feedback)
    row("Commands", "Compact", "Compact older conversation history", SlashAction.Compact)
    row("Commands", "YOLO", "Run without approval prompts", SlashAction.Yolo)
    row("Commands", "Basic YOLO", "Ask before sensitive actions", SlashAction.BasicYolo)
    installed.filter { it.name.startsWith(query, ignoreCase = true) }
        .forEach { skill -> add(SlashRow(group = "Skills", name = skill.name,
            description = skill.description, skill = skill, enabled = skill.userInvocable != false)) }
}
