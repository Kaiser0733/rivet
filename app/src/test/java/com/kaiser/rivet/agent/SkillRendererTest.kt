package com.kaiser.rivet.agent

import org.junit.Assert.*
import org.junit.Test

class SkillRendererTest {
    private fun skill(body: String = "# Release notes\n\nFollow these steps.") =
        Skill(name = "release-notes", description = "Write release notes.", body = body)

    @Test fun rendersDshCanonicalShape() {
        val expected = """
            <skill_content name="release-notes">
            <skill_resources>
            Base directory for this skill: /skills/release-notes
            Resolve relative paths mentioned by this skill against the base directory before using them. Load referenced resources only as needed.
            </skill_resources>

            <skill_instructions>
            # Release notes

            Follow these steps.
            </skill_instructions>
            </skill_content>
        """.trimIndent()
        assertEquals(expected, renderSkillContent(skill(), "/skills/release-notes"))
    }

    @Test fun bodyIsEmbeddedVerbatim() {
        val block = renderSkillContent(skill("Use <example> & friends."), "/dir")
        assertTrue(block.contains("<skill_instructions>\nUse <example> & friends.\n</skill_instructions>"))
    }

    @Test fun baseDirectoryIsEscaped() {
        val block = renderSkillContent(skill(), "/a/<b>&c")
        assertTrue(block.contains("Base directory for this skill: /a/&lt;b&gt;&amp;c\n"))
    }

    @Test fun parsedBodyRoundTripsIntoTheBlock() {
        val result = parseSkill("---\nname: demo\ndescription: Demo.\n---\n# Steps\nDo it.")
        val parsed = (result as SkillParseResult.Parsed).skill
        assertTrue(renderSkillContent(parsed, "/skills/demo")
            .contains("<skill_instructions>\n# Steps\nDo it.\n</skill_instructions>"))
    }

    @Test fun emptyBodyRendersAnEmptyInstructionsBlock() {
        val block = renderSkillContent(skill(""), "/dir")
        assertTrue(block.contains("<skill_instructions>\n\n</skill_instructions>"))
    }
}
