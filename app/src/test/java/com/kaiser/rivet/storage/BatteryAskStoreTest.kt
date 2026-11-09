package com.kaiser.rivet.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BatteryAskStoreTest {
    @Test fun asksOnceAndRemembersDismissal() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val store = BatteryAskStore(context)
        assertTrue(store.shouldAsk())
        store.markAsked()
        assertFalse(BatteryAskStore(context).shouldAsk())
    }
}
