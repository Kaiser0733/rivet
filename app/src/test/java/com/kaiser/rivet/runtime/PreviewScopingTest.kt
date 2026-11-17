package com.kaiser.rivet.runtime

import com.kaiser.rivet.workspace.WorkspacePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewScopingTest {
    @Test fun aSecondEntryReusesTheRootPortWithTheRelativePath() {
        assertEquals(
            "http://127.0.0.1:54001/.rivet-test/probe.html",
            previewEntryUrl(
                "http://127.0.0.1:54001/",
                WorkspacePath.ROOT,
                WorkspacePath.parse(".rivet-test/probe.html"),
            ),
        )
        assertEquals(
            "http://127.0.0.1:54001/probe.html",
            previewEntryUrl(
                "http://127.0.0.1:54001/",
                WorkspacePath.parse("site"),
                WorkspacePath.parse("site/probe.html"),
            ),
        )
    }

    @Test fun previewLookupIsScopedToWorkspaceAndRoot() {
        val processes = ManagedProcesses()
        val id = processes.registerPreview(
            "ws-a", "A", "index.html", "http://127.0.0.1:54001/",
            rootPath = "site", stop = {},
        )
        processes.markPreviewRunning(id!!)
        assertEquals(id, processes.previewFor("ws-a", "site")!!.id)
        assertNull(processes.previewFor("ws-a", ""))
        assertNull(processes.previewFor("ws-b", "site"))
    }
}
