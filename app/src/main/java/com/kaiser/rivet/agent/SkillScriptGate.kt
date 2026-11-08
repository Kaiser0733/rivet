package com.kaiser.rivet.agent

/** A command that references a bundled file (or the folder) of one installed skill. */
data class SkillScriptHit(val skill: Skill, val path: String)

internal enum class SkillScriptGate {
    /** No gate: no hit, the skill is already approved, or YOLO removed approvals. */
    None,

    /** The command would otherwise run silently: insert a first-run approval. */
    InsertApproval,

    /** The mode already asks for this command: keep that one prompt, add the skill line. */
    ExistingApproval,
}

private fun isPathChar(value: Char): Boolean =
    value.isLetterOrDigit() || value == '.' || value == '_' || value == '-'

/**
 * Matches a command against the base directory of every directory skill in [catalog].
 *
 * A hit is a reference to a path *inside* a skill folder, or a `cd <folder>` target, using
 * either the catalog form (project-relative like `.rivet/skills/notes`) or an absolute
 * form that contains it (mirror paths, the global folder under app storage). Matching is
 * boundary-aware, so `.rivet/skills/notes2` is not `notes`; a skill's name in prose or a
 * relative `scripts/x` with no skill folder is not a hit. Flat-file skills share their
 * root with siblings and bundle nothing runnable, so they never match.
 */
internal fun matchSkillScript(command: String, catalog: SkillCatalog?): SkillScriptHit? {
    val skills = catalog?.skills.orEmpty()
    if (command.isEmpty() || skills.isEmpty()) return null
    val text = command.replace('\\', '/')
    for (skill in skills) {
        val base = skill.source?.baseDir?.trimEnd('/')?.replace('\\', '/') ?: continue
        if (base.isEmpty() || base.substringAfterLast('/') != skill.name) continue
        var from = 0
        while (true) {
            val index = text.indexOf(base, from)
            if (index < 0) break
            from = index + 1
            val before = text.getOrNull(index - 1)
            if (before != null && isPathChar(before)) continue
            val after = text.getOrNull(index + base.length)
            val inside = after == '/'
            val boundary = after == null || after.isWhitespace() || after in "\"'`;&|)"
            if (inside || (boundary && isCdTarget(text, index))) {
                return SkillScriptHit(skill, referencedPath(text, index, base.length))
            }
        }
    }
    return null
}

/** True when the token before the base directory is `cd` (quotes allowed). */
private fun isCdTarget(text: String, index: Int): Boolean {
    val head = text.substring(0, index).trimEnd()
    val token = head.trimEnd('"', '\'').trimEnd()
        .takeLastWhile { !it.isWhitespace() && it !in ";&|(" }
    return token == "cd"
}

/** The command text from the base directory to the end of its path token. */
private fun referencedPath(text: String, index: Int, length: Int): String {
    var end = index + length
    while (end < text.length && !text[end].isWhitespace() && text[end] !in "\"'`;&|)") end++
    return text.substring(index, end)
}

/**
 * The first-run decision for one command: only add a prompt when the mode would otherwise
 * run the command silently. YOLO removes every approval, so the gate is inert there.
 */
internal fun skillScriptGate(
    mode: AutonomyMode,
    hit: SkillScriptHit?,
    approved: Set<String>,
    baseAsks: Boolean,
): SkillScriptGate {
    if (mode == AutonomyMode.Yolo) return SkillScriptGate.None
    if (hit == null || hit.skill.name in approved) return SkillScriptGate.None
    return if (baseAsks) SkillScriptGate.ExistingApproval else SkillScriptGate.InsertApproval
}
