package com.kaiser.rivet.browser

import android.app.Application
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BrowserProjectScopeTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun bindingAnotherProjectClearsTransientStateAndSwapsSnapshotStores() {
        val app: Application = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "browser-snapshots")
        root.deleteRecursively()
        val session = BrowserSession(app, models = null)
        session.bindProject("A")
        session.newWindow()
        val windowA = session.state.value.activeWindowId
        assertTrue(windowA != null)
        session.bindProject("B")
        assertEquals("B", session.projectKey)
        assertTrue(session.state.value.windows.isEmpty())
        assertTrue(session.state.value.domains.isEmpty())
        assertEquals("", session.state.value.url)
        session.bindProject("A")
        assertEquals("A", session.projectKey)
        assertTrue(session.state.value.windows.isNotEmpty())
        assertTrue(root.listFiles().orEmpty().size >= 2)
    }

    @Test fun clearingOneProjectDoesNotDeleteAnotherProjectsSnapshotDirectory() {
        val app: Application = RuntimeEnvironment.getApplication()
        val root = File(app.filesDir, "browser-snapshots")
        root.deleteRecursively()
        val session = BrowserSession(app, models = null)
        session.bindProject("A")
        session.newWindow()
        session.bindProject("B")
        session.newWindow()
        session.clearBrowserData()
        assertTrue(session.state.value.windows.isEmpty())
        session.bindProject("A")
        assertTrue("A's snapshots must survive B's clear", session.state.value.windows.isNotEmpty())
        session.bindProject("C")
        assertNotEquals("B", session.projectKey)
        assertFalse(root.listFiles().orEmpty().none { it.isDirectory })
    }
}
