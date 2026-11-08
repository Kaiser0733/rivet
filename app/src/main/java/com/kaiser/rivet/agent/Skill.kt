package com.kaiser.rivet.agent

/**
 * Where a skill was discovered. Discovery (step 2) fills this in; [Skill.source] stays null
 * until then. Ordered by precedence, project first.
 */
enum class SkillRootKind { ProjectRivet, ProjectAgents, Global, Bundled }

/** The directory a skill's files live in, and which root found it. */
data class SkillSource(val baseDir: String, val kind: SkillRootKind)

/**
 * One parsed SKILL.md. [body] is the Markdown after the frontmatter, already trimmed.
 *
 * Invocation defaults follow dsh: the model may load a skill unless the frontmatter sets
 * `disable-model-invocation: true`, and the user may invoke it unless `user-invocable:
 * false`. [modelInvocable] is the derived flag the Level-1 catalog filters on.
 */
data class Skill(
    val name: String,
    val description: String,
    val body: String,
    val license: String? = null,
    val compatibility: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val allowedTools: List<String> = emptyList(),
    val disableModelInvocation: Boolean = false,
    val userInvocable: Boolean = true,
    val source: SkillSource? = null,
) {
    val modelInvocable: Boolean get() = !disableModelInvocation
}
