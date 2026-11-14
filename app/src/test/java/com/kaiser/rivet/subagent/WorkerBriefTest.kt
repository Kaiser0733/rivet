package com.kaiser.rivet.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerBriefTest {
    @Test fun parsesAndRendersReadOnlyWorkerContract() {
        val parsed = WorkerBrief.parse(
            """{"job":"Review the auth diff","brief":"Read the changes and report risks.","write_scope":[]}"""
        )
        assertTrue(parsed is BriefParse.Valid)
        val brief = (parsed as BriefParse.Valid).brief
        val text = brief.render()
        assertTrue(text.contains("You are a Rivet worker for one job."))
        assertTrue(text.contains("Job: Review the auth diff"))
        assertTrue(text.contains("Read the changes and report risks."))
        assertTrue(text.contains("Write scope: read-only"))
        assertTrue(text.contains("Summary:"))
        assertTrue(text.contains("Findings:"))
        assertTrue(text.contains("Open questions:"))
        assertTrue(text.contains("observed command result"))
        assertTrue(text.contains("You cannot start workers."))
    }

    @Test fun writeScopeAndOptionalFieldsRender() {
        val parsed = WorkerBrief.parse(
            """{"job":"Rename","brief":"Do it.","write_scope":["src/a","src/b"],""" +
                """"skill":"review","retry_of":"worker-1"}"""
        )
        assertTrue(parsed is BriefParse.Valid)
        val brief = (parsed as BriefParse.Valid).brief
        assertEquals(2, brief.writeScope.size)
        assertTrue(brief.render().contains("Write scope: src/a, src/b"))
        assertTrue(brief.render().contains("Skill: review"))
        assertTrue(brief.render().contains("Retry of: worker-1"))
    }

    @Test fun enforcesJobBriefAndScopeBounds() {
        val jobAtLimit = "j".repeat(WorkerBrief.MAX_JOB_CHARS)
        val tooLongJob = "j".repeat(WorkerBrief.MAX_JOB_CHARS + 1)
        assertTrue(WorkerBrief.parse("""{"job":"$jobAtLimit","brief":"ok"}""") is BriefParse.Valid)
        assertTrue(WorkerBrief.parse("""{"job":"$tooLongJob","brief":"ok"}""") is BriefParse.Invalid)

        val briefAtLimit = "b".repeat(WorkerBrief.MAX_BRIEF_BYTES)
        val briefTooLong = "b".repeat(WorkerBrief.MAX_BRIEF_BYTES + 1)
        assertTrue(WorkerBrief.parse("""{"job":"job","brief":"$briefAtLimit"}""") is BriefParse.Valid)
        assertTrue(WorkerBrief.parse("""{"job":"job","brief":"$briefTooLong"}""") is BriefParse.Invalid)

        val scope32 = (1..WorkerBrief.MAX_SCOPE_PATHS).joinToString(",") { "\"src/$it\"" }
        val scope33 = (1..(WorkerBrief.MAX_SCOPE_PATHS + 1)).joinToString(",") { "\"src/$it\"" }
        assertTrue(WorkerBrief.parse("""{"job":"job","brief":"ok","write_scope":[$scope32]}""")
            is BriefParse.Valid)
        assertTrue(WorkerBrief.parse("""{"job":"job","brief":"ok","write_scope":[$scope33]}""")
            is BriefParse.Invalid)
    }

    @Test fun rejectsMalformedArgumentsAndInvalidPaths() {
        assertTrue(WorkerBrief.parse("not json") is BriefParse.Invalid)
        assertTrue(WorkerBrief.parse("""{"job":"job"}""") is BriefParse.Invalid)
        assertTrue(WorkerBrief.parse("""{"job":"job","brief":"  "}""") is BriefParse.Invalid)
        assertTrue(WorkerBrief.parse("""{"job":"job","brief":"ok","write_scope":["../escape"]}""")
            is BriefParse.Invalid)
        assertTrue(WorkerBrief.parse("""{"job":"job","brief":"ok","write_scope":["ok", 3]}""")
            is BriefParse.Invalid)
    }

    @Test fun deduplicatesTheWriteScope() {
        val parsed = WorkerBrief.parse(
            """{"job":"job","brief":"ok","write_scope":["src/a","src/a","src/b"]}"""
        )
        assertTrue(parsed is BriefParse.Valid)
        assertEquals(2, (parsed as BriefParse.Valid).brief.writeScope.size)
    }

    @Test fun rendersLoadedSkillBodyIntoInitialWorkerMessage() {
        val parsed = WorkerBrief.parse(
            """{"job":"Audit auth","brief":"Inspect login flow.","skill":"security-audit"}"""
        ) as BriefParse.Valid
        val rendered = parsed.brief.withSkillBody("1. Check token expiry.\n2. Check CSRF.").render()
        assertTrue(rendered.contains("Skill: security-audit"))
        assertTrue(rendered.contains("Skill instructions (security-audit):"))
        assertTrue(rendered.contains("1. Check token expiry.\n2. Check CSRF."))
    }
}
