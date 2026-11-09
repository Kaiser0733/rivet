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

    @Test fun capsAndMonthlyLedgerPersist() {
        val store = SpendStore(context)
        store.save(SpendCaps(perRunUsd = 2.0, monthlyUsd = 20.0, perRunTokens = 100,
            monthlyTokens = 1_000), 0.5)
        val reloaded = SpendStore(context)
        assertEquals(2.0, reloaded.caps().perRunUsd!!, 0.0001)
        assertEquals(1_000L, reloaded.caps().monthlyTokens)
        assertEquals(0.5, reloaded.userPricePerMillionUsd()!!, 0.0001)
        assertEquals(0.0, reloaded.month().first, 0.0001)
        reloaded.add(0.42, 500)
        val month = SpendStore(context).month()
        assertEquals(0.42, month.first, 0.0001)
        assertEquals(500L, month.second)
        assertTrue(SpendStore.isLocal("http://127.0.0.1:11434"))
        assertFalse(SpendStore.isLocal("https://api.openai.com/v1"))
    }
}
