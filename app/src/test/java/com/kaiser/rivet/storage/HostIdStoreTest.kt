package com.kaiser.rivet.storage

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class HostIdStoreTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("rivet_host_id", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After fun teardown() {
        context.getSharedPreferences("rivet_host_id", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun stableAcrossCalls() {
        val store = HostIdStore(context)
        assertEquals(store.getOrCreateHostId(), store.getOrCreateHostId())
    }

    @Test fun stableAcrossInstances() {
        val first = HostIdStore(context).getOrCreateHostId()
        val second = HostIdStore(context).getOrCreateHostId()
        assertEquals(first, second)
    }

    @Test fun isUuidV4() {
        val id = HostIdStore(context).getOrCreateHostId()
        // Throws on malformed input; version digit must be 4.
        val parsed = UUID.fromString(id)
        assertEquals(4, parsed.version())
        assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
    }
}
