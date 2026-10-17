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

    @Test fun isUrnUuidV4() {
        val id = HostIdStore(context).getOrCreateHostId()
        assertTrue(id.startsWith("urn:uuid:"))
        // Throws on malformed input; version digit must be 4.
        val parsed = UUID.fromString(id.removePrefix("urn:uuid:"))
        assertEquals(4, parsed.version())
        assertTrue(id.removePrefix("urn:uuid:").matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
    }

    @Test fun migratesBareUuidInPlace() {
        val bare = UUID.randomUUID().toString()
        context.getSharedPreferences("rivet_host_id", Context.MODE_PRIVATE)
            .edit().putString("ext_agent_host_id", bare).commit()
        val migrated = HostIdStore(context).getOrCreateHostId()
        assertEquals("urn:uuid:$bare", migrated)
        // Stable afterwards: no double-prefixing on subsequent reads.
        assertEquals("urn:uuid:$bare", HostIdStore(context).getOrCreateHostId())
    }

    @Test fun pendingClientIdRoundTrip() {
        val store = HostIdStore(context)
        // Absent by default.
        assertEquals(null, store.pendingClientId())
        store.savePendingClientId("oaiapp_test123")
        assertEquals("oaiapp_test123", store.pendingClientId())
        // Visible across instances (same prefs).
        assertEquals("oaiapp_test123", HostIdStore(context).pendingClientId())
        // Overwrite wins: the latest callback's id is the one a retry needs.
        store.savePendingClientId("oaiapp_test456")
        assertEquals("oaiapp_test456", store.pendingClientId())
        store.clearPendingClientId()
        assertEquals(null, store.pendingClientId())
    }
}
