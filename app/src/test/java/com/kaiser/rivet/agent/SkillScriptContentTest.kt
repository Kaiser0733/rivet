package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SkillScriptContentTest {
    private fun skill(name: String = "notes") = Skill(
        name = name, description = "Use $name.", body = "Body.",
        source = SkillSource(".rivet/skills/$name", SkillRootKind.ProjectRivet),
    )

    @Test fun aOneCharacterScriptChangeInvalidatesTheApprovalKey() = kotlinx.coroutines.runBlocking {
        val workspace = AgentToolExecutorTest.FakeWorkspace(text = "echo first\n")
        val hit = SkillScriptHit(skill(), ".rivet/skills/notes/scripts/run.sh")
        val first = SkillScriptContent.hash(hit, workspace)
        assertNotNull(first)
        val firstKey = hit.copy(contentHash = first).approvalKey()
        workspace.text = "echo second\n"
        val second = SkillScriptContent.hash(hit, workspace)
        assertNotNull(second)
        val secondKey = hit.copy(contentHash = second).approvalKey()
        assertNotEquals(first, second)
        assertNotEquals(firstKey, secondKey)
    }

    @Test fun unreadableScriptsDoNotReuseAStoredApproval() {
        val hit = SkillScriptHit(skill(), ".rivet/skills/notes/scripts/run.sh")
        assertEquals("notes:unreadable", hit.approvalKey())
        assertFalse(hit.isApproved(setOf("notes:unreadable")))
    }
}
