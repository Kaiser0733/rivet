package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.ProviderError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLimitLoopTest {

    @Test
    fun anOverflowWithNumbersReachesTheObserverAndRetriesOnce() = runBlocking {
        var requests = 0
        var compacted = 0
        var observedLimit: Int? = null
        var observedEstimate = 0L
        val loop = AgentLoop(
            requestModel = { _, _, _ ->
                requests++
                if (requests == 1) throw ProviderError.ContextOverflow(128_000, 130_000)
                AgentResponse(text = "done")
            },
            prepareTool = { error("no tools") },
            requestApproval = { true },
            compactContext = { messages, force -> if (force) { compacted++; emptyList() } else messages },
            onContextLimitObserved = { error, estimate ->
                observedLimit = error.reportedLimitTokens
                observedEstimate = estimate
            },
        )
        val result = try {
            loop.run(listOf(AgentMessage.user("hello"), AgentMessage.user("again")), emptyList())
        } catch (e: Throwable) {
            println("FAIL requests=$requests compacted=$compacted limit=$observedLimit")
            throw e
        }
        assertEquals(AgentStopReason.Completed, result.stopReason)
        assertEquals(2, requests)
        assertEquals(128_000, observedLimit)
        assertTrue(observedEstimate > 0)
    }

    @Test
    fun aSecondOverflowEndsTheTurnInsteadOfLooping() = runBlocking {
        var requests = 0
        val loop = AgentLoop(
            requestModel = { _, _, _ ->
                requests++
                throw ProviderError.ContextOverflow(128_000, 130_000)
            },
            prepareTool = { error("no tools") },
            requestApproval = { true },
            compactContext = { messages, force -> if (force) emptyList() else messages },
        )
        var failed = false
        try {
            loop.run(listOf(AgentMessage.user("hello"), AgentMessage.user("again")), emptyList())
        } catch (_: ProviderError.ContextOverflow) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(2, requests)
    }

    @Test
    fun outputLimitEvidenceReachesTheObserver() = runBlocking {
        var observed: Int? = null
        val loop = AgentLoop(
            requestModel = { _, _, _ -> throw ProviderError.OutputLimitExceeded(8_192, 64_000) },
            prepareTool = { error("no tools") },
            requestApproval = { true },
            onOutputLimitObserved = { observed = it.reportedLimitTokens },
        )
        var failed = false
        try {
            loop.run(listOf(AgentMessage.user("hello"), AgentMessage.user("again")), emptyList())
        } catch (_: ProviderError.OutputLimitExceeded) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(8_192, observed)
    }
}
