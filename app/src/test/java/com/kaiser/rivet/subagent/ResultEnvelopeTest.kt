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
}
