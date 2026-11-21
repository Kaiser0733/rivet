package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.Skill
import com.kaiser.rivet.agent.SkillRootKind
import com.kaiser.rivet.agent.SkillSource
import com.kaiser.rivet.chat.compactRowEnabled
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

        assertEquals(listOf("notes"), slashRows(skills, "no").map { it.name })
        assertEquals(listOf("notes", "release-notes", "explain-error"), slashRows(skills, "").filter { it.group == "Skills" }.map { it.name })
        assertTrue(slashRows(skills, "zzz").isEmpty())
    }

    @Test fun filterIsCaseInsensitive() {
        assertEquals(listOf("notes"), slashRows(listOf(skill("notes")), "NO").map { it.name })
    }

    @Test fun listsAllSkillsButCanOnlyInvokeUserSkills() {
        val skills = listOf(skill("notes"), skill("manual", userInvocable = false))

        val rows = slashRows(skills, "").filter { it.group == "Skills" }
        assertEquals(listOf("notes", "manual"), rows.map { it.name })
        assertEquals("Use manual when asked.", rows[1].description)
        assertTrue(rows[0].enabled)
        assertFalse(rows[1].enabled)
    }
    @Test fun builtInsKeepTheirOrderAndFilterByToken() {
        val actions = slashRows(emptyList(), "")
        assertEquals(listOf(null, null, "Commands", "Commands", "Commands"), actions.map { it.group })
        assertEquals(listOf("Goal", "Feedback", "Compact", "YOLO", "Basic YOLO"), actions.map { it.name })
        assertEquals(listOf(SlashAction.BasicYolo), slashRows(emptyList(), "basic-y").map { it.action })
        val compact = actions.single { it.action == SlashAction.Compact }
        assertTrue(compact.enabled)
        assertEquals("Compact older conversation history", compact.description)
    }

    @Test fun compactWaitsWhileAReplyRunsAndTheOtherRowsStayAvailable() {
        val rows = slashRows(emptyList(), "", compactEnabled = compactRowEnabled(streaming = true))
        val compact = rows.single { it.action == SlashAction.Compact }
        assertFalse(compact.enabled)
        assertTrue(rows.filter { it.action != SlashAction.Compact }.all { it.enabled })
        assertTrue(slashRows(emptyList(), "", compactEnabled = compactRowEnabled(streaming = false))
            .single { it.action == SlashAction.Compact }.enabled)
    }

}
