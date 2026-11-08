package com.kaiser.rivet.agent

import org.junit.Assert.*
import org.junit.Test

class SkillToolTest {
    private val call = AgentToolCall(id = "call-1", name = "skill", arguments = "{\"name\":\"notes\"}")

    private fun skill(
        name: String,
        disableModelInvocation: Boolean = false,
        baseDir: String = ".rivet/skills/$name",
    ) = Skill(name = name, description = "Use $name when asked.",
        body = "# $name\n\nFollow these steps.",
        disableModelInvocation = disableModelInvocation,
        source = SkillSource(baseDir, SkillRootKind.ProjectRivet))

    private fun catalog(vararg skills: Skill): SkillCatalog =
        SkillCatalog(skills.toList(), emptyList(), skillCatalogDigest(skills.toList()))

    @Test fun hitRendersTheSkillContentBlock() {
        val result = skillToolResult(call, catalog(skill("notes")), "notes")

        assertFalse(result.error)
        assertEquals("call-1", result.callId)
        assertEquals("skill", result.name)
        assertTrue(result.content.startsWith("<skill_content name=\"notes\">"))
        assertTrue(result.content.contains("Base directory for this skill: .rivet/skills/notes"))
        assertTrue(result.content.contains("\n<skill_instructions>\n# notes\n\nFollow these steps.\n</skill_instructions>"))
        assertEquals("Loaded skill  notes", result.summary)
    }

    @Test fun alreadyLoadedSkillIsNotRenderedAgain() {
        val result = skillToolResult(call, catalog(skill("notes")), "notes",
            loadedSkills = setOf("notes"))

        assertFalse(result.error)
        assertTrue(result.content.contains("already_loaded"))
        assertFalse(result.content.contains("<skill_content"))
    }

    @Test fun manualOnlySkillIsRefused() {
        val result = skillToolResult(call, catalog(skill("notes", disableModelInvocation = true)), "notes")

        assertTrue(result.error)
        assertTrue(result.content.contains("skill_manual_only"))
        assertTrue(result.content.contains("can only be invoked by the user"))
    }

    @Test fun unknownNameListsTheAvailableSkills() {
        val result = skillToolResult(call,
            catalog(skill("explain-error"), skill("notes")), "explain")

        assertTrue(result.error)
        assertTrue(result.content.contains("skill_not_found"))
        assertTrue(result.content.contains("explain-error"))
        assertTrue(result.content.contains("notes"))
        assertTrue(result.content.contains("exact names"))
    }

    @Test fun emptyCatalogSaysNothingIsInstalled() {
        val result = skillToolResult(call, null, "notes")

        assertTrue(result.error)
        assertTrue(result.content.contains("skill_not_found"))
        assertTrue(result.content.contains("No skills are installed"))
    }
}
