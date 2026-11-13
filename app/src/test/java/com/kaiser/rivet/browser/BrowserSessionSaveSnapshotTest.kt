package com.kaiser.rivet.browser

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BrowserSessionSaveSnapshotTest {
    @Test fun selectWindowWithNoRendererKeepsSavedWindowUrl() {
        val app: Application = RuntimeEnvironment.getApplication()
        val session = BrowserSession(app, models = null)
        session.newWindow()
        val firstId = session.state.value.activeWindowId!!
        session.newWindow()
        val secondId = session.state.value.activeWindowId!!

        // Switching windows saves the outgoing window with no renderer
        // attached; its URL must survive that save (H14 regression: the
        // no-renderer branch tried copy(currentUrl = ...), but currentUrl
        // is derived from the history, so the module did not compile).
        session.selectWindow(firstId)

        val saved = session.state.value.windows.first { it.id == secondId }
        assertEquals("about:blank", saved.url)
        assertEquals(firstId, session.state.value.activeWindowId)
    }
}
