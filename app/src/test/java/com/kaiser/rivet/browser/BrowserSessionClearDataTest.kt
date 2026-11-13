package com.kaiser.rivet.browser

import android.app.Application
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BrowserSessionClearDataTest {
    @Test fun clearBrowserDataWipesSnapshotFilesAndInMemoryTabsState() {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        session.newWindow()
        assertFalse(session.state.value.windows.isEmpty())
        val snapshotsDir = File(app.filesDir, "browser-snapshots")
        assertTrue(snapshotsDir.exists())

        session.clearBrowserData()

        assertTrue(session.state.value.windows.isEmpty())
        assertTrue(session.state.value.domains.isEmpty())
        assertNull(session.state.value.activeWindowId)
        assertEquals("", session.state.value.url)
        assertFalse(snapshotsDir.exists() && snapshotsDir.listFiles()?.isNotEmpty() == true)
    }
}
