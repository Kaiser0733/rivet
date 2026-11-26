package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCheckpointTest {
    @Test fun trimmingKeepsTheNewestStateAndRecordsTheOmissionAcrossReload() {
        val checkpoint = TaskCheckpoint(goal = "Ship the docs", state = listOf("a".repeat(900), "latest"))
        val fitted = requireNotNull(checkpoint.fit(500))
        val restored = requireNotNull(TaskCheckpoint.parse(fitted.encode()))

        assertEquals(listOf("latest"), restored.state)
        assertTrue(restored.modelText().contains("older checkpoint details were omitted"))
        assertTrue(restored.encode().toByteArray().size <= 500)
    }
}
