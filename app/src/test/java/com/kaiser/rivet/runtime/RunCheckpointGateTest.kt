package com.kaiser.rivet.runtime

import com.kaiser.rivet.agent.AgentToolCall
import com.kaiser.rivet.agent.AgentToolResult
import com.kaiser.rivet.subagent.ChangedFile
import com.kaiser.rivet.subagent.FileChangeKind
import com.kaiser.rivet.subagent.FileScopeRegistry
import com.kaiser.rivet.workspace.WorkspacePath
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunCheckpointGateTest {
    private data class RunCheckpointEntry(
        val id: String,
        val sequence: Int,
        val summary: String,
        val allowDirty: Boolean,
        var finished: Boolean = false,
    )

    private class FakeCheckpointBackend : CheckpointBackend {
        var mirrorGeneration = 0
        var recordedPostGeneration: Int? = null
        var throwOnBegin: MirrorFailure? = null
        var finishReturnsChanges = true
        var beginTurnCalls = 0
        var postCalls = 0
        var matchCalls = 0
        val finishedTurnIds = mutableListOf<String>()
        val runCheckpoints = mutableListOf<RunCheckpointEntry>()

        override suspend fun beginCheckpoint(
            workspaceId: String,
            runId: String?,
            sessionId: String?,
        ): String {
            throwOnBegin?.let { throw it }
            beginTurnCalls += 1
            return "turn-cp-$beginTurnCalls"
        }

        override suspend fun finishCheckpoint(workspaceId: String, id: String): Boolean {
            finishedTurnIds += id
            return finishReturnsChanges
        }

        override suspend fun recordCheckpointPost(workspaceId: String, id: String) {
            postCalls += 1
            recordedPostGeneration = mirrorGeneration
        }

        override suspend fun checkpointMatchesPost(workspaceId: String, id: String): Boolean {
            matchCalls += 1
            return recordedPostGeneration == mirrorGeneration
        }

        override suspend fun beginRunCheckpoint(
            workspaceId: String,
            runId: String,
            sequence: Int,
            summary: String,
            allowDirty: Boolean,
        ): String {
            val id = "run-cp-$sequence"
            runCheckpoints += RunCheckpointEntry(id, sequence, summary, allowDirty)
            return id
        }

        override suspend fun finishRunCheckpoint(workspaceId: String, id: String) {
            runCheckpoints.firstOrNull { it.id == id }?.finished = true
        }
    }

    private fun call(id: String, name: String = "write_file") = AgentToolCall(id, name, "{}")
    private fun ok(id: String, name: String = "write_file") = AgentToolResult(id, name, "ok")

    @Test
    fun siblingMutationUpdatesPostManifestWithoutFalseUndoConflict() = runTest {
        val backend = FakeCheckpointBackend()
        val gate = RunCheckpointGate(backend)
        gate.startRun("ws-1", "run-1", "Implement feature", resuming = false)
        gate.beginTurn("ws-1", "run-1", "sess-1")

        // Coordinator write 1 initializes turn checkpoint and records post manifest.
        assertNull(gate.beforeMutation(RunCheckpointGate.COORDINATOR, call("c1")))
        backend.mirrorGeneration += 1
        gate.afterMutation(RunCheckpointGate.COORDINATOR, call("c1"), ok("c1"))

        // Sibling worker write matches coordinator's post manifest, mutates, and advances post.
        assertNull(gate.beforeMutation("worker-1", call("w1")))
        assertFalse(gate.checkpointBroken)
        backend.mirrorGeneration += 1
        gate.afterMutation("worker-1", call("w1"), ok("w1"))

        // Coordinator write 2 sees worker's updated post manifest and is NOT flagged as undo_conflict.
        assertNull(gate.beforeMutation(RunCheckpointGate.COORDINATOR, call("c2")))
        assertFalse(gate.checkpointBroken)
        backend.mirrorGeneration += 1
        gate.afterMutation(RunCheckpointGate.COORDINATOR, call("c2"), ok("c2"))

        assertEquals(3, backend.postCalls)
        assertEquals(2, backend.matchCalls)
        assertEquals(
            TurnCheckpointOutcome.Finished("turn-cp-1", hasChanges = true),
            gate.finishTurnCheckpoint(),
        )
    }

    @Test
    fun externalEditOutsideGateTriggersCheckpointBroken() = runTest {
        val backend = FakeCheckpointBackend()
        val gate = RunCheckpointGate(backend)
        gate.beginTurn("ws-1", "run-1", "sess-1")

        assertNull(gate.beforeMutation(RunCheckpointGate.COORDINATOR, call("c1")))
        backend.mirrorGeneration += 1
        gate.afterMutation(RunCheckpointGate.COORDINATOR, call("c1"), ok("c1"))

        // Untracked external edit changes the workspace without afterMutation updating post.
        backend.mirrorGeneration += 1

        assertEquals("checkpoint_unavailable", gate.beforeMutation("worker-1", call("w1")))
        assertTrue(gate.checkpointBroken)
        assertEquals(TurnCheckpointOutcome.Broken, gate.finishTurnCheckpoint())
    }

    @Test
    fun writeLeaseSerializesWritersAndUnlocksWhenBlockedOrThrowingMirrorFailure() = runTest {
        val backend = FakeCheckpointBackend()
        val gate = RunCheckpointGate(backend)
        gate.beginTurn("ws-1", "run-1", "sess-1")

        assertNull(gate.beforeMutation(RunCheckpointGate.COORDINATOR, call("c1")))
        var workerEntered = false
        val worker = async {
            val blocker = gate.beforeMutation("worker-1", call("w1"))
            workerEntered = true
            assertNull(blocker)
            gate.afterMutation("worker-1", call("w1"), ok("w1"))
        }
        yield()
        assertFalse("worker must wait on writeLease while coordinator mutates", workerEntered)

        gate.afterMutation(RunCheckpointGate.COORDINATOR, call("c1"), ok("c1"))
        worker.await()
        assertTrue(workerEntered)

        // Blocker via MirrorFailure must release writeLease instead of deadlocking the next caller.
        gate.beginTurn("ws-1", "run-1", "sess-2")
        backend.throwOnBegin = MirrorFailure("sync_required")
        assertEquals("sync_required", gate.beforeMutation(RunCheckpointGate.COORDINATOR, call("c2")))
        backend.throwOnBegin = null
        assertNull(gate.beforeMutation("worker-1", call("w2")))
        gate.afterMutation("worker-1", call("w2"), ok("w2"))

        // Missing workspaceId also returns checkpoint_unavailable without leaking writeLease.
        gate.beginTurn(null, null, "sess-3")
        assertEquals("checkpoint_unavailable", gate.beforeMutation(RunCheckpointGate.COORDINATOR, call("c3")))
        gate.beginTurn("ws-1", "run-1", "sess-4")
        assertNull(gate.beforeMutation(RunCheckpointGate.COORDINATOR, call("c4")))
        gate.afterMutation(RunCheckpointGate.COORDINATOR, call("c4"), ok("c4"))
    }

    @Test
    fun runBoundariesSequenceAcrossDeleteAndEveryTenMutationsAcrossWriters() = runTest {
        val backend = FakeCheckpointBackend()
        var boundaryNotifications = 0
        val gate = RunCheckpointGate(backend) { boundaryNotifications += 1 }

        gate.startRun("ws-1", "run-1", "Build feature", resuming = false)
        assertEquals(1, boundaryNotifications)
        assertEquals(
            listOf(RunCheckpointEntry("run-cp-0", 0, "Before: Build feature", allowDirty = false, finished = false)),
            backend.runCheckpoints,
        )

        gate.beginRunBoundary("Before deleting a file")
        assertEquals(2, boundaryNotifications)
        assertTrue(backend.runCheckpoints[0].finished)
        assertEquals(
            RunCheckpointEntry("run-cp-1", 1, "Before deleting a file", allowDirty = true, finished = false),
            backend.runCheckpoints[1],
        )

        gate.beginTurn("ws-1", "run-1", "sess-1")
        for (i in 1..9) {
            val writer = if (i % 2 == 0) "worker-1" else RunCheckpointGate.COORDINATOR
            assertNull(gate.beforeMutation(writer, call("m$i")))
            backend.mirrorGeneration += 1
            gate.afterMutation(writer, call("m$i"), ok("m$i"))
        }
        assertEquals(2, backend.runCheckpoints.size)

        // 10th mutation triggers "Every 10 file edits" regardless of which writer performs it.
        assertNull(gate.beforeMutation("worker-1", call("m10")))
        assertEquals(3, backend.runCheckpoints.size)
        assertTrue(backend.runCheckpoints[1].finished)
        assertEquals(
            RunCheckpointEntry("run-cp-2", 2, "Every 10 file edits", allowDirty = true, finished = false),
            backend.runCheckpoints[2],
        )
        gate.afterMutation("worker-1", call("m10"), ok("m10"))
        assertEquals(3, boundaryNotifications)

        gate.finishRunCheckpoint()
        assertTrue(backend.runCheckpoints[2].finished)
        assertEquals(4, boundaryNotifications)
    }

    @Test
    fun enforcesFileScopeAndAttributesPerWriterByteDiffsAndOutOfScopeShellChanges() = runTest {
        val files = linkedMapOf(
            "src/auth/login.kt" to "fun login() = false\n".toByteArray(),
            "src/ui/home.kt" to "fun home() = 1\n".toByteArray(),
        )
        fun currentManifest(): Map<String, CheckpointEntry> = files.mapValues { (_, bytes) ->
            CheckpointEntry(directory = false, size = bytes.size.toLong(), sha256 = bytes.contentHashCode().toString())
        }
        var recordedManifest: Map<String, CheckpointEntry> = emptyMap()
        val backend = object : CheckpointBackend {
            override suspend fun beginCheckpoint(workspaceId: String, runId: String?, sessionId: String?): String {
                recordedManifest = currentManifest()
                return "cp-1"
            }
            override suspend fun finishCheckpoint(workspaceId: String, id: String): Boolean = true
            override suspend fun recordCheckpointPost(workspaceId: String, id: String) {
                recordedManifest = currentManifest()
            }
            override suspend fun checkpointMatchesPost(workspaceId: String, id: String): Boolean =
                recordedManifest == currentManifest()
            override suspend fun beginRunCheckpoint(
                workspaceId: String,
                runId: String,
                sequence: Int,
                summary: String,
                allowDirty: Boolean,
            ): String = "rcp-$sequence"
            override suspend fun finishRunCheckpoint(workspaceId: String, id: String) {}
            override suspend fun readManifest(
                workspaceId: String,
                id: String,
                scanLive: Boolean,
            ): Map<String, CheckpointEntry> = if (scanLive) currentManifest() else recordedManifest
            override suspend fun readFileBytes(workspaceId: String, path: String, maxBytes: Int): ByteArray? =
                files[path]?.takeIf { it.size <= maxBytes }
        }

        val gate = RunCheckpointGate(backend)
        gate.startRun("ws-1", "run-1", "Delegate auth", resuming = false)
        gate.beginTurn("ws-1", "run-1", "sess-1")
        gate.scopeRegistry.register("worker-1", "Fix login", listOf(WorkspacePath.parse("src/auth")))

        // Worker blocked outside its write_scope; coordinator blocked inside worker's write_scope.
        val outOfScopeCall = AgentToolCall("1", "write_file", """{"path":"src/ui/home.kt"}""")
        assertEquals(FileScopeRegistry.OUTSIDE_FILE_SCOPE, gate.beforeMutation("worker-1", outOfScopeCall))

        val ownedByWorkerCall = AgentToolCall("2", "write_file", """{"path":"src/auth/login.kt"}""")
        assertEquals("owned_by_worker:Fix login", gate.beforeMutation(RunCheckpointGate.COORDINATOR, ownedByWorkerCall))

        // Worker edits in-scope file: before bytes captured on first touch, after bytes captured after mutation.
        assertNull(gate.beforeMutation("worker-1", ownedByWorkerCall))
        files["src/auth/login.kt"] = "fun login() = true\nfun logout() = Unit\n".toByteArray()
        gate.afterMutation("worker-1", ownedByWorkerCall, ok("2"))

        // Worker runs a shell command that modifies an out-of-scope file.
        val shellCall = AgentToolCall("3", "run_command", """{"command":"sed -i ..."}""")
        assertNull(gate.beforeMutation("worker-1", shellCall))
        files["src/ui/home.kt"] = "fun home() = 2\n".toByteArray()
        gate.afterMutation("worker-1", shellCall, ok("3"))

        val attribution = gate.writerAttribution("worker-1")
        assertEquals(
            listOf(
                ChangedFile("src/auth/login.kt", FileChangeKind.Modified, additions = 2, deletions = 1),
                ChangedFile("src/ui/home.kt", FileChangeKind.Modified, additions = 0, deletions = 0),
            ),
            attribution.files,
        )
        assertEquals(listOf("src/ui/home.kt"), attribution.outOfScopeChanges)
        assertTrue(attribution.diff != null)
        assertTrue(attribution.diff!!.text.contains("-fun login() = false"))
        assertTrue(attribution.diff!!.text.contains("+fun login() = true"))
    }
}
