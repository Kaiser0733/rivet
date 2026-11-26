package com.kaiser.rivet.storage

import com.kaiser.rivet.agent.SpendCaps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SpendStoreTest {
    private val context = org.robolectric.RuntimeEnvironment.getApplication()

    @Test fun freshCapsAreOnAndAnExplicitOptOutSurvives() {
        val store = SpendStore(context)
        val caps = store.caps()
        assertEquals(SpendCaps(1.0, 15.0, 500_000, 5_000_000), caps)
        store.save(SpendCaps(), null)
        assertEquals(SpendCaps(), SpendStore(context).caps())
    }

    @Test fun capsAndMonthlyLedgerPersist() {
        val store = SpendStore(context)
        store.save(SpendCaps(perRunUsd = 2.0, monthlyUsd = 20.0, perRunTokens = 100,
            monthlyTokens = 1_000), 0.5)
        val reloaded = SpendStore(context)
        assertEquals(2.0, reloaded.caps().perRunUsd!!, 0.0001)
        assertEquals(1_000L, reloaded.caps().monthlyTokens ?: -1L)
        assertEquals(0.5, reloaded.userPricePerMillionUsd()!!, 0.0001)
        assertEquals(0.0, reloaded.month().first, 0.0001)
        reloaded.add(0.42, 500)
        val month = SpendStore(context).month()
        assertEquals(0.42, month.first, 0.0001)
        assertEquals(500L, month.second)
        assertTrue(SpendStore.isLocal("http://127.0.0.1:11434"))
        assertTrue(SpendStore.isLocal("http://[::1]:11434"))
        assertTrue(SpendStore.isLocal("local://"))
        assertFalse(SpendStore.isLocal("https://api.openai.com/v1"))
        assertFalse(SpendStore.isLocal("https://localhost.evil.com/v1"))
        assertFalse(SpendStore.isLocal("http://172.200.0.1/v1"))
    }

    @Test fun addAccumulatesSubMicrodollarCostsWithoutPrecisionLoss() {
        val store = SpendStore(context)
        repeat(1_000) {
            store.add(0.0000004, 10L)
        }
        val (cost, tokens) = SpendStore(context).month()
        assertEquals(0.0004, cost, 1e-9)
        assertEquals(10_000L, tokens)
    }
}
