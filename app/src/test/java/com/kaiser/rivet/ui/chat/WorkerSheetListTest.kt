package com.kaiser.rivet.ui.chat

import com.kaiser.rivet.agent.RunPhase
import com.kaiser.rivet.subagent.WorkerRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkerSheetListTest {
    private fun worker(id: String, state: RunPhase, createdAt: Long = 0L) = WorkerRecord(
        id = id, runId = "run", parentSessionId = null, workspaceId = null,
        job = "Job $id", brief = "", configId = "cfg", model = "m", state = state,
        createdAt = createdAt,
    )

    @Test fun everyWorkerOfTheRunIsListedNotOnlyTheQueuedOne() {
        val rows = workerSheetRows(listOf(
            worker("queued", RunPhase.Queued, createdAt = 3),
            worker("running", RunPhase.Running, createdAt = 2),
            worker("done", RunPhase.Done, createdAt = 1),
        ))
        assertEquals(listOf("running", "queued", "done"), rows.map { it.id })
        assertEquals(3, rows.size)
    }

    @Test fun finishedWorkersKeepTheirOwnStateWord() {
        val rows = workerSheetRows(listOf(
            worker("a", RunPhase.Done), worker("b", RunPhase.Failed), worker("c", RunPhase.Stopped),
        ))
        assertEquals(listOf("done", "failed", "stopped"), rows.map(::workerSheetState))
    }

    @Test fun duplicateWorkerIdsAppearOnce() {
        val rows = workerSheetRows(listOf(
            worker("same", RunPhase.Running), worker("same", RunPhase.Running),
        ))
        assertEquals(1, rows.size)
        assertTrue(rows.single().id == "same")
    }
}
