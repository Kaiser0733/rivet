package com.kaiser.rivet.agent

/**
 * One discovered but unusable skill: a malformed SKILL.md, a name that does not match its
 * folder/file, an unreadable file, or a same-root duplicate. Surfaced by the Skills screen
 * (spec §4); never loaded and never injected.
 */
data class SkillError(val displayName: String, val path: String, val reason: String)

/**
 * The result of one discovery pass.
 *
 * [skills] are the winning skills (lowest rank per name), each with its [Skill.source]
 * filled, sorted by name. [errors] are display-only entries sorted by path. [digest] is
 * stable for identical input, so step 3 can republish the Level-1 listing only when the
 * loaded set changes.
 */
data class SkillCatalog(
    val skills: List<Skill>,
    val errors: List<SkillError>,
    val digest: String,
) {
    val isEmpty: Boolean get() = skills.isEmpty() && errors.isEmpty()
}

/**
 * SHA-256 over the winning skills' name, description and source path, in name order, with
 * a NUL separator so no field can bleed into the next one. Errors stay out of the digest:
 * the Level-1 listing only contains loaded skills.
 */
internal fun skillCatalogDigest(skills: List<Skill>): String {
    val canonical = buildString {
        for (skill in skills.sortedBy { it.name }) {
            append(skill.name).append('\u0000')
            append(skill.description).append('\u0000')
            append(skill.source?.baseDir.orEmpty()).append('\u0000')
        }
    }
    return InternalContext.digest(canonical)
}
