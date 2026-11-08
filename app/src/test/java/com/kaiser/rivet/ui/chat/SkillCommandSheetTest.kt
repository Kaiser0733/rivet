package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.Skill
import com.kaiser.rivet.agent.SkillRootKind
import com.kaiser.rivet.agent.SkillSource
import org.junit.Assert.*
import org.junit.Test

class SkillCommandSheetTest {
    private fun skill(name: String, userInvocable: Boolean = true) =
        Skill(name = name, description = "Use $name when asked.", body = "Body.",
            userInvocable = userInvocable,
            source = SkillSource(".rivet/skills/$name", SkillRootKind.ProjectRivet))

    @Test fun onlyALeadingUnfinishedTokenOpensTheSheet() {
        assertNull(skillCommandQuery("hello /notes"))
        assertNull(skillCommandQuery(""))
        assertNull(skillCommandQuery("/notes do the thing"))
        assertEquals("", skillCommandQuery("/"))
        assertEquals("notes", skillCommandQuery("/notes"))
    }

    @Test fun filterMatchesTheNamePrefix() {
        val skills = listOf(skill("notes"), skill("release-notes"), skill("explain-error"))

        assertEquals(listOf("notes"), matchingSkills(skills, "no").map { it.name })
        assertEquals(listOf("notes", "release-notes", "explain-error"), matchingSkills(skills, "").map { it.name })
        assertTrue(matchingSkills(skills, "zzz").isEmpty())
    }

    @Test fun filterIsCaseInsensitive() {
        assertEquals(listOf("notes"), matchingSkills(listOf(skill("notes")), "NO").map { it.name })
    }

    @Test fun filterKeepsOnlyUserInvocableSkills() {
        val skills = listOf(skill("notes"), skill("manual", userInvocable = false))

        assertEquals(listOf("notes"), matchingSkills(skills, "").map { it.name })
    }
}
