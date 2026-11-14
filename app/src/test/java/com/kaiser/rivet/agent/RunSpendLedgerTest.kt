package com.kaiser.rivet.agent

import com.kaiser.rivet.provider.ProviderConfig
import com.kaiser.rivet.provider.ProviderType
import com.kaiser.rivet.storage.LocalEndpointPolicy
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSpendLedgerTest {
    private fun cloudConfig(id: String, model: String = "gpt-4o") = ProviderConfig(
        id = id,
        type = ProviderType.OpenAi,
        name = id,
        baseUrl = "https://api.openai.com/v1",
        model = model,
    )

    private fun localConfig() = ProviderConfig(
        id = "local",
        type = ProviderType.Local,
        name = "Local",
        baseUrl = "local://model.gguf",
        model = "qwen-1.7b",
    )

    @Test
    fun coordinatorAndWorkersSumIntoRunTotalAndSingleFirePause() = runBlocking {
        var storedCost = 0.0
        var storedTokens = 0L
        val pauses = AtomicInteger(0)
        var caps = SpendCaps(perRunUsd = 1.0)
        val ledger = RunSpendLedger(
            addMonthlySpend = { cost, tokens ->
                storedCost += cost
                storedTokens += tokens
            },
            readCaps = { caps },
            readUserPricePerMillionUsd = { null },
            isLocalEndpoint = LocalEndpointPolicy::isLocal,
            onBudgetPause = { pauses.incrementAndGet() },
        )

        // gpt-4o: $2.50 / 1M input, $10.00 / 1M output -> 100k input = $0.25, 20k output = $0.20 => $0.45 per call.
        val callUsage = AgentUsage(inputTokens = 100_000, outputTokens = 20_000)
        val results = listOf(
            async(Dispatchers.Default) { ledger.record(cloudConfig("coord"), callUsage, estimated = false) },
            async(Dispatchers.Default) { ledger.record(cloudConfig("worker-1"), callUsage, estimated = false) },
            async(Dispatchers.Default) { ledger.record(cloudConfig("worker-2"), callUsage, estimated = false) },
        ).awaitAll()

        // 3 * $0.45 = $1.35 across coordinator + two workers.
        assertEquals(1.35, storedCost, 0.0001)
        assertEquals(360_000L, storedTokens)
        assertEquals(1.35, results.maxOf { it.runSpendUsd }, 0.0001)
        assertEquals(1, pauses.get())
        assertEquals(1, results.count { it.shouldPause })
        assertTrue(ledger.budgetPaused)

        assertTrue(ledger.raiseCapForCurrentRun())
        assertFalse(ledger.budgetPaused)
        val afterRaise = ledger.record(cloudConfig("worker-1"), callUsage, estimated = false)
        assertFalse(afterRaise.shouldPause)
        assertEquals(1.80, afterRaise.runSpendUsd, 0.0001)
        assertEquals(1, pauses.get())
    }

    @Test
    fun localModelsRecordNoSpendAndPreserveCloudLabelWhenMixed() {
        var addedCalls = 0
        val ledger = RunSpendLedger(
            addMonthlySpend = { _, _ -> addedCalls++ },
            readCaps = { SpendCaps(perRunUsd = 0.001) },
            readUserPricePerMillionUsd = { null },
            isLocalEndpoint = LocalEndpointPolicy::isLocal,
        )

        val localOnly = ledger.record(localConfig(), AgentUsage(inputTokens = 5_000, outputTokens = 1_000), estimated = false)
        assertTrue(localOnly.local)
        assertNull(localOnly.label)
        assertFalse(localOnly.shouldPause)
        assertEquals(0, addedCalls)

        val cloud = ledger.record(cloudConfig("worker"), AgentUsage(inputTokens = 1_000_000, outputTokens = 0), estimated = true)
        assertFalse(cloud.local)
        assertTrue(cloud.shouldPause)
        assertEquals(1, addedCalls)

        val localAfterCloud = ledger.record(localConfig(), AgentUsage(inputTokens = 5_000, outputTokens = 1_000), estimated = false)
        assertTrue(localAfterCloud.local)
        assertEquals("$2.50", localAfterCloud.label)
        assertFalse(localAfterCloud.shouldPause)
    }
}
