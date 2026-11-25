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

    @Test fun evalIsRegisteredOnlyAsBoundedSameOriginToolAndNoFileOpen() {
        val tools = source("BrowserTools.kt")
        assertTrue(tools.contains("\"browser_eval\""))
        val session = source("BrowserSession.kt")
        assertTrue(session.contains("BrowserEvalPolicy.originOf(liveUrl)"))
        assertTrue(session.contains("BrowserEvalPolicy.wrapScript"))
        assertTrue(session.contains("BrowserAuthGuard.isAuthSurface(liveUrl)"))
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

    @Test fun theFullScreenBrowserShowsTheAgentsLiveSession() {
        // The chat watch card was replaced by the full-screen browser (brief §8): the
        // live session and the agent-driving banner live on BrowserScreen now.
        val chat = listOf(File("src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/chat/ChatScreen.kt")).first { it.isFile }.readText()
        assertFalse(chat.contains("BrowserWatchCard"))

        val app = listOf(File("src/main/java/com/kaiser/rivet/ui/RivetApp.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/RivetApp.kt")).first { it.isFile }.readText()
        assertTrue(app.contains("BrowserScreen("))
        // The screen is composed for the agent's renderer even before the user opens it,
        // and it hosts the same live session the agent is driving.
        assertTrue(app.contains("browserState.rendererVisible && !browserState.teardownRequested"))
        assertTrue(app.contains("BrowserHost(chatViewModel.browser, hostModifier, onPageInteraction)"))

        val screen = listOf(File("src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt")).first { it.isFile }.readText()
        assertTrue(screen.contains("BrowserDrivingPolicy.bannerVisible("))
        assertTrue(screen.contains("state.agentRunActive, state.agentVisualActive, state.driving"))
        assertTrue(screen.contains("session.takeControl()"))
    }

    @Test fun passwordFieldsAreOnTheRefusalPath() {
        val safety = source("BrowserFormSafety.kt")
        assertTrue(safety.contains("current-password"))
        assertTrue(safety.contains("cc-number"))
        assertTrue(safety.contains("one-time-code"))
    }

    @Test fun urlFieldUsesUnfocusedOverlayAndClearsFocusAfterPageInteraction() {
        val screen = listOf(File("src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt")).first { it.isFile }.readText()
        assertTrue(screen.contains("matchParentSize()"))
        assertTrue(screen.contains("clearAndSetSemantics"))
        assertTrue(screen.contains("focusRequester.requestFocus()"))
        assertTrue(screen.contains("focusManager.clearFocus()"))
        assertTrue(screen.contains("onPageInteraction"))
        val submit = screen.substringAfter("val submitUrl: () -> Unit = {").substringBefore("val pageOptions")
        assertTrue(submit.contains("clearUrlFocus()"))
        // The toolbar's Go key runs the same submit, which clears focus first.
        assertTrue(screen.contains("KeyboardActions(onGo = { onSubmit() })"))
        assertTrue(screen.contains("onSubmit = submitUrl"))
        // Page interaction (the host's touch callback) clears the URL field's focus.
        assertTrue(screen.contains("if (isUrlFocused) focusManager.clearFocus()"))
        assertTrue(screen.contains("LaunchedEffect(state.visible)"))
        assertTrue(screen.contains("if (!state.visible)"))
        assertFalse(screen.contains("UrlFieldSelection.Controller"))
        assertFalse(screen.contains("selectAllLatch"))
        assertFalse(screen.contains("pendingSelectAllOnTap"))
    }

    @Test fun pageTouchCanClearFocusWithoutStartingTakeover() {
        val host = listOf(File("src/main/java/com/kaiser/rivet/ui/browser/BrowserHost.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/browser/BrowserHost.kt")).first { it.isFile }.readText()
        assertTrue(host.contains("setOnTouchListener"))
        assertTrue(host.contains("MotionEvent.ACTION_DOWN"))
        assertTrue(host.contains("pageInteraction()"))
        assertFalse(host.contains("setDriving"))
    }

    @Test fun bannerLivesInColumnAboveHostWithOpaqueSurface() {
        val app = listOf(File("src/main/java/com/kaiser/rivet/ui/RivetApp.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/RivetApp.kt")).first { it.isFile }.readText()
        assertFalse(app.contains("chromeHeightPx"))
        assertFalse(app.contains("onChromeHeight"))

        val screen = listOf(File("src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt")).first { it.isFile }.readText()
        val host = "Box(Modifier.weight(1f).fillMaxWidth().clipToBounds())"
        assertTrue(screen.contains(host))
        // Errors and notices still sit in the column above the host, so nothing can
        // cover them, and BrowserNotice is an opaque full-width band.
        val noticeAt = screen.indexOf("state.error != null || state.notice != null || state.budgetCloseAction")
        val hostAt = screen.indexOf(host)
        assertTrue("notice band is rendered above the host", noticeAt in 0 until hostAt)
        assertTrue(screen.contains("private fun BrowserNotice(color: Color"))
        assertTrue(screen.contains("Surface(Modifier.fillMaxWidth(), color = color)"))
        assertTrue(screen.contains("state.error?.let { error ->"))
        assertTrue(screen.contains("state.notice?.let {"))
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
        // Restore is deferred: the surviving tab is loaded first, the snapshot follows
        // in onPageFinished rather than being evaluated against the outgoing page.
        assertTrue(closeWindowBody.indexOf("pendingSnapshotRestore =") <
            closeWindowBody.indexOf("view.loadUrl(activeUrl)"))

        val screen = listOf(File("src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/browser/BrowserScreen.kt")).first { it.isFile }.readText()
        assertFalse(screen.contains("runCatching { session.showReader() }"))
        val readerToggle = screen.substringAfter("session.showReader()").substringBefore("onBack()")
        assertTrue(readerToggle.contains("session.showError(error)"))
        assertTrue(screen.contains("state.url.isBlank() || state.url == \"about:blank\""))
    }

    @Test fun rendererAttachDisablesHardwareLayerAndDropsSurfaceReattach() {
        val session = source("BrowserSession.kt")
        assertTrue(session.contains("view.setLayerType(android.view.View.LAYER_TYPE_NONE, null)"))
        assertFalse(session.contains("LAYER_TYPE_HARDWARE"))
        assertFalse(session.contains("surfaceReattacher"))
        assertTrue(session.contains("PaintVerdict.classifyPixels"))
        assertTrue(session.contains("PaintStaleness.isNavigationStale"))

        val host = listOf(File("src/main/java/com/kaiser/rivet/ui/browser/BrowserHost.kt"),
            File("app/src/main/java/com/kaiser/rivet/ui/browser/BrowserHost.kt")).first { it.isFile }.readText()
        assertFalse(host.contains("surfaceReattacher"))
    }

    @Test fun liveRendererReadsAreNeverServedFromAStaleCache() {
        val session = source("BrowserSession.kt")
        val read = session.substringAfter("suspend fun read(").substringBefore("suspend fun find(")
        assertTrue(read.contains("if (renderer != null && activeUrl.isNotBlank())"))
        assertTrue(read.contains("readRenderedDocument(visibleOnly)"))
        assertTrue(read.contains("readerCache.cached(visibleOnly)"))
        assertTrue(session.contains("data-rivet-hidden"))
        assertTrue(session.contains("document.body.textContent"))
    }

    @Test fun openNormalizesPreflightAndCapturesMainFrameErrors() {
        val session = source("BrowserSession.kt")
        assertTrue(session.contains("BrowserUrlPolicy.normalize(rawUrl)"))
        assertTrue(session.contains("pendingMainFrameError"))
        assertTrue(session.contains("lastMainHttpStatus"))
        assertTrue(session.contains("browser_cleartext_blocked"))
        val policy = source("BrowserUrlPolicy.kt")
        assertTrue(policy.contains("fun normalize(raw: String)"))
    }

    @Test fun browserDataClearsOnlyTheCurrentProject() {
        val session = source("BrowserSession.kt")
        val clear = session.substringAfter("fun clearBrowserData()").substringBefore("fun afterInteraction()")
        assertTrue(clear.contains("snapshots.clear()"))
        assertTrue(clear.contains("shotStore.directory.deleteRecursively()"))
        assertFalse(clear.contains("File(app.filesDir, \"browser-snapshots\").deleteRecursively()"))
        assertFalse(clear.contains("File(app.filesDir, \"browser-shots\").deleteRecursively()"))
        assertTrue(session.contains("fun bindProject(key: String)"))
    }
}
