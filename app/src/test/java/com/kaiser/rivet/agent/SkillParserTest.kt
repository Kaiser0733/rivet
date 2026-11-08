package com.kaiser.rivet.agent

import org.junit.Assert.*
import org.junit.Test

class SkillParserTest {
    private fun parsed(markdown: String): Skill {
        val result = parseSkill(markdown)
        assertTrue("expected a parsed skill, got $result", result is SkillParseResult.Parsed)
        return (result as SkillParseResult.Parsed).skill
    }

    private fun invalid(markdown: String): String {
        val result = parseSkill(markdown)
        assertTrue("expected an invalid skill, got $result", result is SkillParseResult.Invalid)
        return (result as SkillParseResult.Invalid).reason
    }

    private fun frontmatter(vararg lines: String) = buildString {
        append("---\n")
        lines.forEach { append(it).append('\n') }
        append("---\nBody")
    }

    private val full = """
        ---
        name: release-notes
        description: Write release notes for a tagged build. Use when asked for changelog prose.
        license: Apache-2.0
        compatibility: Any repository.
        metadata:
          author: kaiser
          version: "2"
        allowed-tools:
          - Read
          - Bash
        disable-model-invocation: yes
        user-invocable: "off"
        ---
        # Release notes

        Follow these steps.
    """.trimIndent()

    @Test fun validSkillParsesEveryField() {
        val skill = parsed(full)
        assertEquals("release-notes", skill.name)
        assertEquals("Write release notes for a tagged build. Use when asked for changelog prose.",
            skill.description)
        assertEquals("Apache-2.0", skill.license)
        assertEquals("Any repository.", skill.compatibility)
        assertEquals(mapOf("author" to "kaiser", "version" to "2"), skill.metadata)
        assertEquals(listOf("Read", "Bash"), skill.allowedTools)
        assertTrue(skill.disableModelInvocation)
        assertFalse(skill.modelInvocable)
        assertFalse(skill.userInvocable)
        assertNull(skill.source)
        assertTrue(skill.body.startsWith("# Release notes"))
        assertTrue(skill.body.endsWith("Follow these steps."))
    }

    @Test fun defaultsAreModelAndUserInvocable() {
        val skill = parsed(frontmatter("name: explain-error", "description: Explain one error."))
        assertEquals("Explain one error.", skill.description)
        assertNull(skill.license)
        assertNull(skill.compatibility)
        assertTrue(skill.metadata.isEmpty())
        assertTrue(skill.allowedTools.isEmpty())
        assertTrue(skill.modelInvocable)
        assertTrue(skill.userInvocable)
        assertEquals("Body", skill.body)
    }

    @Test fun quotedScalarsAndInlineListsParse() {
        val skill = parsed(frontmatter(
            "name: \"commit-message\"",
            "description: 'Write a commit message; use when the diff is ready.'",
            "allowed-tools: [Read, \"Git diff\"]",
            "license: \"\"",
        ))
        assertEquals("commit-message", skill.name)
        assertEquals("Write a commit message; use when the diff is ready.", skill.description)
        assertEquals(listOf("Read", "Git diff"), skill.allowedTools)
        assertNull(skill.license)
    }

    @Test fun inlineMetadataMapParses() {
        val skill = parsed(frontmatter(
            "name: demo",
            "description: Demo skill.",
            "metadata: {author: kaiser, version: 3}",
        ))
        assertEquals(mapOf("author" to "kaiser", "version" to "3"), skill.metadata)
    }

    @Test fun scalarAllowedToolsBecomesOneEntry() {
        val skill = parsed(frontmatter(
            "name: demo",
            "description: Demo skill.",
            "allowed-tools: Bash",
        ))
        assertEquals(listOf("Bash"), skill.allowedTools)
    }

    @Test fun unknownFrontmatterKeysAreIgnored() {
        val skill = parsed(frontmatter(
            "name: demo",
            "description: Demo skill.",
            "when-to-use: whenever",
        ))
        assertEquals("demo", skill.name)
    }

    @Test fun missingNameIsInvalid() {
        assertTrue(invalid(frontmatter("description: Demo skill.")).contains("name"))
    }

    @Test fun blankNameIsInvalid() {
        assertTrue(invalid(frontmatter("name:", "description: Demo skill.")).contains("name"))
    }

    @Test fun missingDescriptionIsInvalid() {
        assertTrue(invalid(frontmatter("name: demo")).contains("description"))
    }

    @Test fun uppercaseNameIsInvalid() {
        assertTrue(invalid(frontmatter("name: Release-Notes", "description: Demo skill."))
            .contains("name"))
    }

    @Test fun underscoreNameIsInvalid() {
        assertTrue(invalid(frontmatter("name: release_notes", "description: Demo skill."))
            .contains("name"))
    }

    @Test fun tooLongNameIsInvalid() {
        assertTrue(invalid(frontmatter("name: ${"a".repeat(65)}", "description: Demo skill."))
            .contains("name"))
    }

    @Test fun nameAtLimitIsValid() {
        assertEquals("a".repeat(64),
            parsed(frontmatter("name: ${"a".repeat(64)}", "description: Demo skill.")).name)
    }

    @Test fun descriptionAtLimitIsValid() {
        val limit = "d".repeat(1024)
        assertEquals(limit, parsed(frontmatter("name: demo", "description: $limit")).description)
    }

    @Test fun tooLongDescriptionIsInvalid() {
        val reason = invalid(frontmatter("name: demo", "description: ${"d".repeat(1025)}"))
        assertTrue(reason.contains("1024"))
    }

    @Test fun noFrontmatterIsInvalid() {
        assertTrue(invalid("# Just a body\nNo frontmatter here.").contains("frontmatter"))
    }

    @Test fun unterminatedFrontmatterIsInvalid() {
        assertTrue(invalid("---\nname: demo\ndescription: Demo skill.").contains("frontmatter"))
    }

    @Test fun strayIndentationIsInvalid() {
        assertTrue(invalid("---\nname: demo\ndescription: Demo skill.\n  stray: indent\n---\nBody")
            .contains("indentation"))
    }

    @Test fun unclosedInlineListIsInvalid() {
        assertTrue(invalid(frontmatter("name: demo", "description: [unterminated")).contains("list"))
    }

    @Test fun blockScalarIsInvalid() {
        assertTrue(invalid(frontmatter("name: demo", "description: |")).contains("block"))
    }

    @Test fun nestedMappingIsInvalid() {
        assertTrue(invalid(frontmatter(
            "name: demo",
            "description: Demo skill.",
            "metadata:",
            "  author:",
            "    name: kaiser",
        )).contains("nested"))
    }

    @Test fun invalidReasonsAreOneLine() {
        assertFalse(invalid(frontmatter("name: Demo")).contains('\n'))
    }
}
