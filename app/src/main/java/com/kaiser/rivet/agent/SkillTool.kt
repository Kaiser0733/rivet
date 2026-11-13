package com.kaiser.rivet.agent

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `skill` tool's whole behaviour: the Level-2 load. A hit returns dsh's canonical
 * `<skill_content>` block; a manual-only skill is refused; an unknown name lists what is
 * installed so the model can correct itself. Read-only: it only returns text, and bundled
 * scripts still pass through the normal command approvals when the agent runs them.
 */
internal fun skillToolResult(
    call: AgentToolCall,
    catalog: SkillCatalog?,
    name: String,
    loadedSkills: Set<String> = emptySet(),
): AgentToolResult {
    val skills = catalog?.skills.orEmpty()
    val skill = skills.firstOrNull { it.name == name }
    if (skill == null) {
        val available = skills.filter { it.modelInvocable }.map { it.name }
        return AgentToolResult(call.id, call.name, buildJsonObject {
            put("error", "skill_not_found")
            put("retryable", false)
            put("required_action", if (available.isEmpty())
                "No skills are installed in this session. Continue with the request and the other tools."
            else "Available skills: ${available.joinToString(", ")}. Call skill again with one of these exact names.")
        }.toString(), error = true, summary = "Failed  skill")
    }
    // Manual beats auto: a `/name` gesture already put this skill in the turn.
    if (name in loadedSkills) {
        return AgentToolResult(call.id, call.name, buildJsonObject {
            put("skill", name)
            put("already_loaded", true)
            put("required_action", "The skill is already loaded for this turn. Follow the instructions already in the conversation; do not load it again.")
        }.toString(), summary = "Skill already loaded  $name")
    }
    if (!skill.modelInvocable) {
        return AgentToolResult(call.id, call.name, buildJsonObject {
            put("error", "skill_manual_only")
            put("retryable", false)
            put("required_action", "The skill \"${skill.name}\" can only be invoked by the user. Do not call it again.")
        }.toString(), error = true, summary = "Failed  skill")
    }
    return AgentToolResult(call.id, call.name,
        renderSkillContent(skill, skill.source?.baseDir.orEmpty()),
        summary = "Loaded skill  ${skill.name}")
}
