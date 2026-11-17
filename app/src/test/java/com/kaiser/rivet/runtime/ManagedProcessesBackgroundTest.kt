package com.kaiser.rivet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedProcessesBackgroundTest {
    @Test fun backgroundCountsAsPersistentAndStopForWorkspaceStopsIt() {
        val processes = ManagedProcesses()
        var stopped = 0
        val id = processes.registerBackground("npm run dev", "tree-a", "Project A") { stopped++ }!!
        assertEquals(ManagedProcessKind.Background, processes.info(id, "tree-a")!!.kind)
        assertEquals("Background command for Project A", processes.stopDescription(id, "tree-a"))
        assertEquals(1, processes.foregroundState.value.activeBackgrounds)
        assertTrue(processes.foregroundState.value.hasActiveWork)
        processes.stopForWorkspace("tree-a")
        assertEquals(1, stopped)
        assertEquals(ManagedProcessStatus.Stopped, processes.info(id, "tree-a")!!.status)
        assertEquals(0, processes.foregroundState.value.activeBackgrounds)
        processes.finishBackground(id, ManagedProcessStatus.Completed, 0)
        assertEquals(ManagedProcessStatus.Stopped, processes.info(id, "tree-a")!!.status)
    }

    @Test fun previewsAreReusablePerWorkspaceAndRoot() {
        val processes = ManagedProcesses()
        val first = processes.registerPreview(
            "tree-a", "Project A", "index.html", "http://127.0.0.1:54001/",
            rootPath = "site", stop = {},
        )!!
        processes.markPreviewRunning(first)
        assertEquals(first, processes.previewFor("tree-a", "site")!!.id)
        assertNull(processes.previewFor("tree-a", ""))
        assertNull(processes.previewFor("tree-b", "site"))
    }

    @Test fun stopForOtherWorkspacesLeavesCurrentPreview() {
        val processes = ManagedProcesses()
        var stoppedA = false
        var stoppedB = false
        val a = processes.registerPreview(
            "tree-a", "A", "index.html", "http://127.0.0.1:54001/",
            rootPath = "site", stop = { stoppedA = true },
        )!!
        val b = processes.registerPreview(
            "tree-b", "B", "index.html", "http://127.0.0.1:54002/",
            rootPath = "site", stop = { stoppedB = true },
        )!!
        processes.markPreviewRunning(b)
        processes.markPreviewRunning(a)
        processes.stopForOtherWorkspaces("tree-a")
        assertTrue(stoppedB)
        assertFalse(stoppedA)
        assertEquals(a, processes.previewFor("tree-a", "site")!!.id)
    }
}
