package com.kaiser.rivet.browser

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSourceSafetyTest {
    private fun source(name: String): String =
        listOf(File("src/main/java/com/kaiser/rivet/browser/$name"),
            File("app/src/main/java/com/kaiser/rivet/browser/$name"))
            .first { it.isFile }.readText()

    @Test fun onlyTheVisualDesktopPathSetsAUserAgent() {
        val files = listOf(File("src/main/java/com/kaiser/rivet/browser"),
            File("app/src/main/java/com/kaiser/rivet/browser")).first { it.isDirectory }
            .walkTopDown().filter { it.extension == "kt" }
        assertTrue(files.toList().isNotEmpty())
        val readers = files.filter { it.name == "ReaderService.kt" }
        assertTrue(readers.none { it.readText().contains("userAgent") })
    }

    @Test fun thereIsNoRawEvalToolAndNoFileOpen() {
        val tools = source("BrowserTools.kt")
        assertFalse(tools.contains("\"browser_eval\""))
        val reader = source("ReaderService.kt")
        assertTrue(reader.contains("BrowserUrlPolicy.requireReadable("))
        assertFalse(reader.contains("scheme in setOf(\"http\", \"https\", \"file\")"))
        val policy = source("BrowserUrlPolicy.kt")
        assertTrue(policy.contains("Project previews use the local preview server"))
        assertFalse(policy.contains("scheme in setOf(\"http\", \"https\", \"file\")"))
    }

    @Test fun touchNeverStartsTakeover() {
        val host = listOf(File("src/main/java/com/kaiser/rivet/ui/browser/BrowserHost.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/browser/BrowserHost.kt")).first { it.isFile }.readText()
        assertFalse(host.contains("setDriving"))
        assertTrue(host.contains("AndroidView"))
    }

    @Test fun textOpenAndReadDoNotTouchVisibleState() {
        val session = source("BrowserSession.kt")
        val open = session.substringAfter("suspend fun openText(").substringBefore("suspend fun openVisual(")
        assertFalse(open.contains("_state.update"))
        val read = session.substringAfter("suspend fun read(").substringBefore("suspend fun find(")
        assertFalse(read.contains("_state.update"))
    }

    @Test fun theBrowserIsAFullScreenScreenNotASheet() {
        val app = listOf(File("src/main/java/com/kaiser/rivet/ui/RivetApp.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/RivetApp.kt")).first { it.isFile }.readText()
        assertTrue(app.contains("BrowserScreen"))
        assertTrue(app.contains("BrowserHost"))
        assertFalse(app.contains("BrowserSheet"))
    }

    @Test fun chatShowsTheWatchCardForVisualSessions() {
        val chat = listOf(File("src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt")).first { it.isFile }.readText()
        assertTrue(chat.contains("BrowserWatchCard"))
        assertTrue(chat.contains("BrowserDrivingPolicy.cardVisible"))
        assertTrue(chat.contains("browserState.agentRunActive"))
    }

    @Test fun passwordFieldsAreOnTheRefusalPath() {
        val safety = source("BrowserFormSafety.kt")
        assertTrue(safety.contains("current-password"))
        assertTrue(safety.contains("cc-number"))
        assertTrue(safety.contains("one-time-code"))
    }

    @Test fun urlFieldWiresFocusLatchThroughOnValueChange() {
        val screen = listOf(File("src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt")).first { it.isFile }.readText()
        assertFalse(screen.contains("onValueChange = { address = it }"))
        assertTrue(screen.contains("UrlFieldSelection.Controller"))
        assertTrue(screen.contains("urlField.onValueChange"))
        assertTrue(screen.contains("urlField.onFocusChanged"))
    }

    @Test fun bannerLivesInColumnAboveHostWithOpaqueSurface() {
        val app = listOf(File("src/main/java/com/kaiser/rivet/ui/RivetApp.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/RivetApp.kt")).first { it.isFile }.readText()
        assertFalse(app.contains("chromeHeightPx"))
        assertFalse(app.contains("onChromeHeight"))

        val screen = listOf(File("src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt")).first { it.isFile }.readText()
        assertTrue(screen.contains("Box(Modifier.weight(1f).fillMaxWidth().clipToBounds())"))
        val noticeBlock = screen.substringAfter("state.error != null || state.notice != null || state.budgetCloseAction")
        assertTrue(noticeBlock.trimStart().startsWith(")"))
        assertTrue(screen.contains("BrowserNotice(\n                    if (state.error != null)"))
    }

    @Test fun teardownTerminatesTrackedProcessesAndClearsPendingRestart() {
        val host = listOf(File("src/main/java/com/kaiser/rivet/ui/browser/BrowserHost.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/browser/BrowserHost.kt")).first { it.isFile }.readText()
        assertTrue(host.contains("HeldWebView"))

        val session = source("BrowserSession.kt")
        val endSessionBody = session.substringAfter("fun endSession()").substringBefore("fun clearNotice()")
        assertTrue(endSessionBody.contains("pendingRestart = null"))
        val teardownBody = session.substringAfter("fun requestRendererTeardown()").substringBefore("private fun armHangValve()")
        assertTrue(teardownBody.contains("processRegistry.terminateAll"))
    }

    @Test fun closeWindowDefersSnapshotRestoreAndReaderErrorsSurface() {
        val session = source("BrowserSession.kt")
        val closeWindowBody = session.substringAfter("fun closeWindow(id: String)").substringBefore("suspend fun bookmark()")
        assertTrue(closeWindowBody.contains("unparkRenderer(verifyPaint = false)"))
        assertTrue(closeWindowBody.contains("pendingSnapshotRestore ="))
        assertFalse(closeWindowBody.contains("loadSnapshotState(view, it)"))

        val screen = listOf(File("src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt")).first { it.isFile }.readText()
        assertFalse(screen.contains("runCatching { session.showReader() }"))
        assertTrue(screen.contains("state.url.isBlank() && (!state.rendererVisible || state.activeWindowId == null)"))
    }
}
