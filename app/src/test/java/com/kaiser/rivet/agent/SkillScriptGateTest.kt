package com.kaiser.rivet.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillScriptGateTest {
    private fun skill(name: String = "notes",
                      baseDir: String = ".rivet/skills/$name",
                      kind: SkillRootKind = SkillRootKind.ProjectRivet) =
        Skill(name = name, description = "Use $name.", body = "Body.",
            source = SkillSource(baseDir, kind))

    private fun catalog(vararg skills: Skill): SkillCatalog =
        SkillCatalog(skills.toList(), emptyList(), skillCatalogDigest(skills.toList()))

    private fun hit(skill: Skill = skill()) = SkillScriptHit(skill, "${skill.source!!.baseDir}/scripts/run.sh")

    // ------------------------------------------------------------ matcher

    @Test fun directPathInsideTheSkillFolderIsAHit() {
        val match = matchSkillScript("sh .rivet/skills/notes/scripts/run.sh", catalog(skill()))

        assertNotNull(match)
        assertEquals("notes", match!!.skill.name)
        assertEquals(".rivet/skills/notes/scripts/run.sh", match.path)
    }

    @Test fun interpreterArgumentIsAHit() {
        val match = matchSkillScript("sh -c 'sh .rivet/skills/notes/scripts/run.sh --fast'",
            catalog(skill()))

        assertEquals("notes", match?.skill?.name)
    }

    @Test fun quotedPathIsAHit() {
        val match = matchSkillScript("sh \".rivet/skills/notes/scripts/run.sh\"", catalog(skill()))

        assertEquals("notes", match?.skill?.name)
    }

    @Test fun cdIntoTheSkillFolderIsAHit() {
        val match = matchSkillScript("cd .rivet/skills/notes && sh scripts/run.sh", catalog(skill()))

        assertEquals("notes", match?.skill?.name)
        assertEquals(".rivet/skills/notes", match?.path)
    }

    @Test fun quotedCdIntoTheSkillFolderIsAHit() {
        val match = matchSkillScript("cd '.rivet/skills/notes' && sh scripts/run.sh",
            catalog(skill()))

        assertEquals("notes", match?.skill?.name)
    }

    @Test fun absoluteFormThatContainsTheSkillFolderIsAHit() {
        val match = matchSkillScript(
            "sh /data/user/0/com.kaiser.rivet/files/mirror/w/worktree/.rivet/skills/notes/scripts/run.sh",
            catalog(skill()))

        assertEquals("notes", match?.skill?.name)
    }

    @Test fun globalSkillFolderIsMatchedByItsAbsolutePath() {
        val global = skill("notes", "/data/user/0/com.kaiser.rivet/files/skills/notes", SkillRootKind.Global)

        val match = matchSkillScript(
            "sh /data/user/0/com.kaiser.rivet/files/skills/notes/scripts/run.sh", catalog(global))

        assertEquals("notes", match?.skill?.name)
    }

    @Test fun prefixSiblingFolderIsNotAHit() {
        assertNull(matchSkillScript("sh .rivet/skills/notes2/scripts/run.sh", catalog(skill())))
    }

    @Test fun unrelatedCommandIsNotAHit() {
        assertNull(matchSkillScript("sh scripts/run.sh", catalog(skill())))
        assertNull(matchSkillScript("pwd", catalog(skill())))
    }

    @Test fun aSkillNameInProseIsNotAHit() {
        assertNull(matchSkillScript("echo use the notes skill here", catalog(skill())))
    }

    @Test fun missingCatalogIsNotAHit() {
        assertNull(matchSkillScript("sh .rivet/skills/notes/scripts/run.sh", null))
    }

    @Test fun flatFileSkillNeverMatchesItsSharedRoot() {
        val flat = skill("notes", ".rivet/skills", SkillRootKind.ProjectRivet)

        assertNull(matchSkillScript("sh .rivet/skills/other/scripts/run.sh", catalog(flat)))
    }

    // ------------------------------------------------------------ gate decisions

    @Test fun unapprovedSkillInASilentModeInsertsAnApproval() {
        assertEquals(SkillScriptGate.InsertApproval,
            skillScriptGate(AutonomyMode.BasicYolo, hit(), emptySet(), baseAsks = false))
    }

    @Test fun approvedSkillNeedsNoGate() {
        assertEquals(SkillScriptGate.None,
            skillScriptGate(AutonomyMode.BasicYolo, hit(), setOf("notes"), baseAsks = false))
    }

    @Test fun aModeThatAlreadyAsksIsNotDoubled() {
        assertEquals(SkillScriptGate.ExistingApproval,
            skillScriptGate(AutonomyMode.Ask, hit(), emptySet(), baseAsks = true))
    }

    @Test fun yoloDisablesTheGate() {
        assertEquals(SkillScriptGate.None,
            skillScriptGate(AutonomyMode.Yolo, hit(), emptySet(), baseAsks = false))
    }

    @Test fun noHitNeedsNoGate() {
        assertEquals(SkillScriptGate.None,
            skillScriptGate(AutonomyMode.BasicYolo, null, emptySet(), baseAsks = false))
    }

    // ------------------------------------------------------------ loop wiring

    private class GateRun(
        val result: AgentRunResult,
        val approvals: List<AgentApprovalRequest>,
        val recorded: List<String>,
        val executed: Int,
    )

    private suspend fun runGated(
        mode: AutonomyMode,
        hits: List<SkillScriptHit>,
        approved: Set<String> = emptySet(),
        allow: Boolean = true,
    ): GateRun {
        val calls = hits.mapIndexed { index, _ ->
            AgentToolCall("call-$index", "run_command", """{"command":"sh script"}""")
        }
        var requests = 0
        var executed = 0
        val approvals = mutableListOf<AgentApprovalRequest>()
        val recorded = mutableListOf<String>()
        val result = AgentLoop(
            requestModel = { _, _, _ ->
                if (requests++ == 0) AgentResponse(toolCalls = calls) else AgentResponse(text = "Done")
            },
            prepareTool = { call ->
                PreparedAgentTool(
                    call,
                    AgentApprovalRequest(call, "Run command", "sh script"),
                    effect = AgentToolEffect.ForegroundCommand,
                    basicAutonomyRisk = BasicAutonomyRisk.Routine,
                    skillScript = hits[calls.indexOf(call)],
                ) {
                    executed++
                    AgentToolResult(call.id, call.name, """{"exit_code":0}""")
                }
            },
            requestApproval = { approvals += it; allow },
            approvedSkillScripts = approved,
            recordSkillScriptApproval = { recorded += it },
        ).run(listOf(AgentMessage.user("Run the script")), emptyList(), autonomyMode = mode)
        return GateRun(result, approvals, recorded, executed)
    }

    @Test fun silentModeGetsOneSkillApprovalAndRemembersItForTheTurn() = runTest {
        val run = runGated(AutonomyMode.BasicYolo, listOf(hit(), hit()))

        assertEquals(AgentStopReason.Completed, run.result.stopReason)
        assertEquals(1, run.approvals.size)
        val detail = run.approvals.single().detail
        assertTrue(detail.contains("Bundled skill: notes"))
        assertTrue(detail.contains("Approving lets this skill's scripts run without asking again."))
        assertEquals(listOf("notes"), run.recorded)
        assertEquals(2, run.executed)
    }

    @Test fun existingCommandApprovalCarriesTheSkillLineAndRecords() = runTest {
        val run = runGated(AutonomyMode.Ask, listOf(hit()))

        assertEquals(1, run.approvals.size)
        assertTrue(run.approvals.single().detail.contains("Bundled skill: notes"))
        assertEquals(listOf("notes"), run.recorded)
        assertEquals(1, run.executed)
    }

    @Test fun denialDoesNotRunOrRecord() = runTest {
        val run = runGated(AutonomyMode.Ask, listOf(hit()), allow = false)

        assertEquals(0, run.executed)
        assertTrue(run.recorded.isEmpty())
        assertEquals(AgentStopReason.Completed, run.result.stopReason)
    }

    @Test fun approvedSkillRunsWithoutTheSkillLine() = runTest {
        val run = runGated(AutonomyMode.Ask, listOf(hit()), approved = setOf("notes"))

        assertEquals(1, run.approvals.size)
        assertTrue(!run.approvals.single().detail.contains("Bundled skill:"))
        assertTrue(run.recorded.isEmpty())
    }

    @Test fun yoloNeverAsksAndNeverRecords() = runTest {
        val run = runGated(AutonomyMode.Yolo, listOf(hit()))

        assertTrue(run.approvals.isEmpty())
        assertTrue(run.recorded.isEmpty())
        assertEquals(1, run.executed)
    }
}
