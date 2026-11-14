package com.kaiser.rivet.subagent

import com.kaiser.rivet.agent.RunPhase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultEnvelopeTest {
    @Test fun keepsVerifiedGroundTruthAndWorkerClaimsApart() {
        val diff = WorkerDiff.diff("src/a.kt", "old".toByteArray(), "new".toByteArray())
        val json = Json.parseToJsonElement(ResultEnvelope.build(
            workerId = "worker-1",
            state = RunPhase.Done,
            claim = WorkerClaim("I fixed it.", listOf("tests pass"), listOf("none")),
            files = listOf(ChangedFile("src/a.kt", FileChangeKind.Modified, 1, 1)),
            outOfScopeChanges = listOf("src/other.kt"),
            commands = listOf(ObservedCommand("gradlew test", 0, "ok")),
            diff = diff,
        )).jsonObject

        assertEquals("worker-1", json["worker_id"]!!.jsonPrimitive.content)
        val verified = json["verified"]!!.jsonObject
        val claimed = json["claimed"]!!.jsonObject
        assertEquals("done", verified["state"]!!.jsonPrimitive.content)
        assertEquals(1, verified["files_changed"]!!.jsonArray[0].jsonObject["+"]!!.jsonPrimitive.int)
        assertEquals(1, verified["files_changed"]!!.jsonArray[0].jsonObject["-"]!!.jsonPrimitive.int)
        assertEquals("src/other.kt",
            verified["out_of_scope_changes"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("gradlew test",
            verified["commands"]!!.jsonArray[0].jsonObject["command"]!!.jsonPrimitive.content)
        assertEquals("I fixed it.", claimed["summary"]!!.jsonPrimitive.content)
        assertEquals("tests pass",
            claimed["findings"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(verified["diff"]!!.jsonPrimitive.content.contains("+new"))
        assertFalse(verified["truncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test fun failedWorkerErrorBecomesTheClaimedSummaryButStaysVerifiedToo() {
        val json = Json.parseToJsonElement(ResultEnvelope.build(
            workerId = "w",
            state = RunPhase.Failed,
            claim = WorkerClaim("partial"),
            error = "provider failed",
        )).jsonObject
        assertEquals("provider failed",
            json["claimed"]!!.jsonObject["summary"]!!.jsonPrimitive.content)
        assertEquals("provider failed",
            json["verified"]!!.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test fun commandsAreBoundedAndDiffTruncationIsSurfaced() {
        val long = "x".repeat(200)
        val truncatedDiff = WorkerDiff("a.kt", "diff", 1, 1, truncated = true)
        val json = Json.parseToJsonElement(ResultEnvelope.build(
            workerId = "w",
            state = RunPhase.Done,
            claim = WorkerClaim("done"),
            commands = listOf(ObservedCommand(long, null, null)),
            diff = truncatedDiff,
        )).jsonObject
        val command = json["verified"]!!.jsonObject["commands"]!!.jsonArray[0].jsonObject
        assertEquals(ResultEnvelope.MAX_COMMAND_CHARS,
            command["command"]!!.jsonPrimitive.content.length)
        assertTrue(json["verified"]!!.jsonObject["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("null", command["exit_code"].toString())
    }

    @Test fun assembleAwaitResultCapsAggregatePayloadAt24KiBByTruncatingDiffsProportionally() {
        val bigBefore = (1..400).joinToString("\n") { "old line $it with padding text" }.toByteArray()
        val bigAfter = (1..400).joinToString("\n") { "new line $it with padding text" }.toByteArray()
        val envelopes = (1..3).map { idx ->
            val diff = WorkerDiff.diff("src/file$idx.kt", bigBefore, bigAfter)
            ResultEnvelope.build(
                workerId = "worker-$idx",
                state = RunPhase.Done,
                claim = WorkerClaim(
                    summary = "Updated file $idx",
                    findings = listOf("finding $idx"),
                    openQuestions = emptyList(),
                ),
                files = listOf(ChangedFile("src/file$idx.kt", FileChangeKind.Modified, diff.additions, diff.deletions)),
                diff = diff,
            )
        }
        val rawSum = envelopes.sumOf { it.toByteArray(Charsets.UTF_8).size }
        assertTrue("Individual envelopes should exceed 24 KiB in total (was $rawSum)", rawSum > ResultEnvelope.MAX_AWAIT_BYTES)

        val assembled = ResultEnvelope.assembleAwaitResult(envelopes)
        val assembledBytes = assembled.toByteArray(Charsets.UTF_8).size
        assertTrue("Assembled await result must be <= 24 KiB (was $assembledBytes)", assembledBytes <= ResultEnvelope.MAX_AWAIT_BYTES)

        val parsed = Json.parseToJsonElement(assembled).jsonObject
        val workers = parsed["workers"]!!.jsonArray
        assertEquals(3, workers.size)
        for (worker in workers) {
            val verified = worker.jsonObject["verified"]!!.jsonObject
            assertTrue(verified["truncated"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(verified["diff"]!!.jsonPrimitive.content.isNotEmpty())
        }
    }
}
