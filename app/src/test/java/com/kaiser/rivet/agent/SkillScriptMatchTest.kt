package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillScriptMatchTest {
    private fun target(name: String, baseDir: String = ".rivet/skills/$name") =
        SkillScriptTarget(name = name, baseDir = baseDir)

    @Test fun matchesCommandWorkingDirectoryInsideAndAboveSkillFolder() {
        val notes = target("notes")
        val insideHits = SkillScriptMatcher.matchTargets(
            command = "sh scripts/run.sh",
            targets = listOf(notes),
            cwd = ".rivet/skills/notes",
        )
        assertEquals(listOf("notes"), insideHits.map { it.name })

        val relativeFromParent = SkillScriptMatcher.matchTargets(
            command = "sh ./notes/scripts/run.sh",
            targets = listOf(notes),
            cwd = ".rivet/skills",
        )
        assertEquals(listOf("notes"), relativeFromParent.map { it.name })
    }

    @Test fun matchesCdWithDoubleDashPushdAndDotSlash() {
        val notes = target("notes")
        assertEquals(
            listOf("notes"),
            SkillScriptMatcher.matchTargets(
                command = "cd -- .rivet/skills/notes && sh scripts/run.sh",
                targets = listOf(notes),
            ).map { it.name },
        )
        assertEquals(
            listOf("notes"),
            SkillScriptMatcher.matchTargets(
                command = "pushd -P ./.rivet/skills/notes && sh run.sh",
                targets = listOf(notes),
            ).map { it.name },
        )
    }

    @Test fun multiSkillCommandRequiresApprovalWhenAnyMatchedSkillUnapproved() {
        val hits = SkillScriptMatcher.matchTargets(
            command = "sh .rivet/skills/alpha/run.sh && sh .rivet/skills/beta/run.sh",
            targets = listOf(target("alpha"), target("beta")),
        )
        assertEquals(listOf("alpha", "beta"), hits.map { it.name })
        assertTrue(
            SkillScriptMatcher.isGateNeeded(
                isYolo = false,
                matchedNames = hits.map { it.name },
                approved = setOf("alpha"),
            ),
        )
        assertFalse(
            SkillScriptMatcher.isGateNeeded(
                isYolo = false,
                matchedNames = hits.map { it.name },
                approved = setOf("alpha", "beta"),
            ),
        )
        assertFalse(
            SkillScriptMatcher.isGateNeeded(
                isYolo = true,
                matchedNames = hits.map { it.name },
                approved = emptySet(),
            ),
        )
    }
}
