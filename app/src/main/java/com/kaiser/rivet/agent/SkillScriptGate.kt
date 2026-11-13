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

/**
 * Matches a command and optional [cwd] against every directory skill in [catalog], returning
 * all matched skills in catalog order.
 */
internal fun matchSkillScripts(
    command: String,
    catalog: SkillCatalog?,
    cwd: String = "",
): List<SkillScriptHit> {
    val skills = catalog?.skills.orEmpty()
    if ((command.isEmpty() && cwd.isEmpty()) || skills.isEmpty()) return emptyList()
    val byName = skills.associateBy { it.name }
    val targets = skills.map { SkillScriptTarget(it.name, it.source?.baseDir) }
    return SkillScriptMatcher.matchTargets(command, targets, cwd).mapNotNull { matched ->
        byName[matched.name]?.let { SkillScriptHit(it, matched.path) }
    }
}

/** Single-hit wrapper retained for callers and existing tests. */
internal fun matchSkillScript(
    command: String,
    catalog: SkillCatalog?,
    cwd: String = "",
): SkillScriptHit? = matchSkillScripts(command, catalog, cwd).firstOrNull()

/**
 * The first-run decision for one command covering all matched skills: asks whenever any matched
 * skill has not yet been approved. YOLO disables the gate entirely.
 */
internal fun skillScriptGate(
    mode: AutonomyMode,
    hits: List<SkillScriptHit>,
    approved: Set<String>,
    baseAsks: Boolean,
): SkillScriptGate {
    if (!SkillScriptMatcher.isGateNeeded(mode == AutonomyMode.Yolo, hits.map { it.skill.name }, approved)) {
        return SkillScriptGate.None
    }
    return if (baseAsks) SkillScriptGate.ExistingApproval else SkillScriptGate.InsertApproval
}

internal fun skillScriptGate(
    mode: AutonomyMode,
    hit: SkillScriptHit?,
    approved: Set<String>,
    baseAsks: Boolean,
): SkillScriptGate = skillScriptGate(mode, listOfNotNull(hit), approved, baseAsks)
