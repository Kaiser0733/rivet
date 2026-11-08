package com.kaiser.rivet.agent

import org.junit.Assert.*
import org.junit.Test

class SkillGestureTest {
    private fun skill(name: String, userInvocable: Boolean = true) =
        Skill(name = name, description = "Use $name when asked.", body = "Body of $name.",
            userInvocable = userInvocable,
            source = SkillSource(".rivet/skills/$name", SkillRootKind.ProjectRivet))

    private fun catalog(vararg skills: Skill): SkillCatalog =
        SkillCatalog(skills.toList(), emptyList(), skillCatalogDigest(skills.toList()))

    @Test fun namedSkillLoadsAndKeepsTheTaskExactly() {
        val gesture = skillGesture("/release-notes bump to 2.0 and  tag it",
            catalog(skill("release-notes")))

        assertNotNull(gesture)
        assertEquals("release-notes", gesture!!.skill.name)
        assertEquals("bump to 2.0 and  tag it", gesture.task)
    }

    @Test fun multilineTaskIsKeptVerbatim() {
        val gesture = skillGesture("/notes first line\n\nsecond line",
            catalog(skill("notes")))

        assertEquals("first line\n\nsecond line", gesture?.task)
    }

    @Test fun unknownTokenIsUntouched() {
        assertNull(skillGesture("/unknown do the thing", catalog(skill("notes"))))
    }

    @Test fun nonUserInvocableSkillIsUntouched() {
        assertNull(skillGesture("/notes do the thing",
            catalog(skill("notes", userInvocable = false))))
    }

    @Test fun tokenWithoutATaskIsUntouched() {
        assertNull(skillGesture("/notes", catalog(skill("notes"))))
    }

    @Test fun tokenNotAtTheStartIsUntouched() {
        assertNull(skillGesture("please /notes do the thing", catalog(skill("notes"))))
    }

    @Test fun missingCatalogIsUntouched() {
        assertNull(skillGesture("/notes do the thing", null))
    }

    @Test fun invocationIsAValidContextCarryingTheRenderedBlock() {
        val message = SkillContext.invocation(skill("notes"), "ws")
        val context = message.validatedContext()

        assertNotNull(context)
        assertEquals(SKILL_INVOCATION_KIND, context!!.kind)
        assertEquals("ws", context.workspaceId)
        assertTrue(context.content.contains("<skill_content name=\"notes\">"))
        assertTrue(context.content.contains("Body of notes."))
        assertTrue(message.text.isEmpty())
    }
}
