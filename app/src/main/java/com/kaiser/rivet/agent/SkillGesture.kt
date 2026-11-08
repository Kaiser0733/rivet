package com.kaiser.rivet.agent

/** One leading `/name` gesture: the skill to load and the task text that remains. */
internal data class SkillGesture(val skill: Skill, val task: String)

// dsh's SKILL_GESTURE (dsh-tool-skill/lib/index.js), narrowed to a leading token: /name,
// then whitespace, then the task. A bare token has no task and stays ordinary text.
private val LEADING_SKILL_GESTURE = Regex("^/([a-z0-9]+(?:-[a-z0-9]+)*)\\s+(\\S[\\s\\S]*)$")

/**
 * A gesture only fires for an installed skill that is user-invocable and only when a task
 * follows the token. Unknown names, manual-only skills and a task-less `/name` pass
 * through unchanged: no error, no hint.
 */
internal fun skillGesture(message: String, catalog: SkillCatalog?): SkillGesture? {
    val match = LEADING_SKILL_GESTURE.matchEntire(message) ?: return null
    val skill = catalog?.skills?.firstOrNull { it.name == match.groupValues[1] } ?: return null
    if (!skill.userInvocable) return null
    return SkillGesture(skill, match.groupValues[2])
}
