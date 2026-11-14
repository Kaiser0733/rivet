package com.kaiser.rivet.subagent

import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.workspace.WorkspacePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubagentSchedulerTest {
    private fun cloud(id: String, model: String = "gpt-4o-mini") = ProviderConfig(
        id = id,
        type = ProviderType.OpenAi,
        name = "OpenAI",
        baseUrl = "https://example.invalid/v1",
        model = model,
    )

    private fun local(id: String, model: String = "qwen-1.7b") = ProviderConfig(
        id = id,
        type = ProviderType.Local,
        name = "On this phone",
        baseUrl = "local://",
        model = model,
    )

    private fun request(
        job: String,
        model: ProviderConfig,
        scope: List<WorkspacePath> = emptyList(),
    ) = SubagentRequest(job, model, scope)

    private fun live(
        job: String,
        model: ProviderConfig,
        scope: List<WorkspacePath> = emptyList(),
        running: Boolean = true,
    ) = SubagentLive(job, model, scope, running)

    @Test fun thirdCloudWorkerQueuesForASlot() {
        val config = cloud("c1")
        val admission = SubagentScheduler.admit(
            request("third", config),
            listOf(live("one", config), live("two", config)),
            config,
        )
        assertTrue(admission is Admission.Queue)
        admission as Admission.Queue
        assertEquals(QueueReason.Slot, admission.reason)
        assertEquals("2 workers are already running", admission.detail)
    }

    @Test fun secondLocalWorkerIsRefusedWithTheRamLine() {
        val local = local("l1")
        val admission = SubagentScheduler.admit(
            request("second local", local),
            listOf(live("first local", local)),
            cloud("coordinator"),
        )
        assertTrue(admission is Admission.Refuse)
        val line = (admission as Admission.Refuse).line
        assertTrue(line.contains("one local model at a time"))
        assertTrue(line.contains("first local"))
    }

    @Test fun localCoordinatorCannotSpawnADifferentLocalModel() {
        val coordinator = local("l1", "qwen-1.7b")
        val other = local("l1", "llama-3.2-3b")
        val admission = SubagentScheduler.admit(
            request("different local", other),
            emptyList(),
            coordinator,
        )
        assertTrue(admission is Admission.Refuse)
        val line = (admission as Admission.Refuse).line
        assertTrue(line.contains("would unload"))
        assertTrue(line.contains("qwen-1.7b"))
    }

    @Test fun overlappingScopesQueueEvenWithAFreeSlot() {
        val config = cloud("c1")
        val admission = SubagentScheduler.admit(
            request("overlap", config, listOf(WorkspacePath.parse("src/auth"))),
            listOf(live("owner", config, listOf(WorkspacePath.parse("src/auth/login.kt")))),
            config,
        )
        assertTrue(admission is Admission.Queue)
        admission as Admission.Queue
        assertEquals(QueueReason.Files, admission.reason)
        assertEquals("same files as owner", admission.detail)
    }

    @Test fun terminalWorkerNoLongerOccupiesASlot() {
        val config = cloud("c1")
        val admission = SubagentScheduler.admit(
            request("next", config),
            listOf(
                live("done", config, running = false),
                live("failed", config, running = false),
            ),
            config,
        )
        assertEquals(Admission.Start, admission)
    }

    @Test fun localCoordinatorMaySpawnItsOwnModelWhenTheLocalSlotIsFree() {
        val coordinator = local("l1", "qwen-1.7b")
        val admission = SubagentScheduler.admit(
            request("time-share", coordinator),
            emptyList(),
            coordinator,
        )
        assertEquals(Admission.Start, admission)
    }

    @Test fun localWorkerAtTheTotalCapQueuesForASlot() {
        val local = local("l1")
        val cloud = cloud("c1")
        val admission = SubagentScheduler.admit(
            request("local", local),
            listOf(live("one", cloud), live("two", cloud)),
            cloud,
        )
        assertTrue(admission is Admission.Queue)
        assertEquals(QueueReason.Slot, (admission as Admission.Queue).reason)
    }

    @Test fun nonOverlappingScopesWithFreeSlotStart() {
        val config = cloud("c1")
        val admission = SubagentScheduler.admit(
            request("two", config, listOf(WorkspacePath.parse("src/two"))),
            listOf(live("one", config, listOf(WorkspacePath.parse("src/one")))),
            config,
        )
        assertEquals(Admission.Start, admission)
    }
}
