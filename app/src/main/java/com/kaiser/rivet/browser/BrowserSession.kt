package com.kaiser.rivet.browser

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.view.PixelCopy
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.kaiser.rivet.ui.browser.BrowserTabs
import com.kaiser.rivet.ui.browser.BrowserWindowInfo
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.app.DownloadManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import com.kaiser.rivet.local.LocalModelManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class BrowserMode { Reader, Renderer }

data class BrowserWindowSummary(
    val id: String,
    val title: String,
    val url: String,
    val fidelity: BrowserFidelity,
    val active: Boolean,
    val thumbnailFile: String? = null,
    val favicon: android.graphics.Bitmap? = null,
)

data class BrowserHandoffState(val url: String, val reason: String)

data class BrowserDownloadPrompt(
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val metered: Boolean,
)

internal const val PAINT_WATCHDOG_ERROR =
    "The page stopped rendering."

internal fun shouldClearPaintError(
    state: BrowserUiState,
    sampledUrl: String,
    verdict: PaintVerdict,
): Boolean = verdict == PaintVerdict.Painted && state.error == PAINT_WATCHDOG_ERROR &&
    state.url == sampledUrl

class BrowserChatPage internal constructor(
    val url: String,
    val title: String = "",
    val excerpt: String = "",
)

data class BrowserUiState(
    /** The full-screen visual browser is on screen. */
    val visible: Boolean = false,
    val url: String = "",
    val title: String = "",
    val mode: BrowserMode = BrowserMode.Reader,
    val readerText: String = "",
    val chunkIndex: Int = 0,
    val chunkCount: Int = 0,
    val chunkAnchor: String = "",
    val needsRenderer: Boolean = false,
    val rendererVisible: Boolean = false,
    val rendererHostGeneration: Long = 0,
    val teardownRequested: Boolean = false,
    val pageReady: Boolean = false,
    val readerMode: Boolean = false,
    val watchVisible: Boolean = false,
    val watchSessionId: String? = null,
    val chatPage: BrowserChatPage? = null,
    val chatBrowserWorking: Boolean = false,
    val visualScreenshot: String? = null,
    val tier: BrowserTier = BrowserTier.Normal,
    val capMb: Int = 250,
    val modelLoaded: Boolean = false,
    val budgetLine: String = "",
    val domains: List<BrowserVisit> = emptyList(),
    val windows: List<BrowserWindowSummary> = emptyList(),
    val activeWindowId: String? = null,
    val driving: BrowserDriving = BrowserDriving.Agent,
    val agentRunActive: Boolean = false,
    val agentRendererUse: Boolean = false,
    val handoff: BrowserHandoffState? = null,
    val authNotice: String? = null,
    val downloadPrompt: BrowserDownloadPrompt? = null,
    val notice: String? = null,
    val budgetCloseAction: Boolean = false,
    val error: String? = null,
    val errorRetry: Boolean = false,
    val loadValveNotice: String? = null,
    val fullPage: Boolean = false,
    val allowJs: Boolean = true,
    val blockThirdParty: Boolean = true,
    val desktopMode: Boolean = false,
    val fidelity: BrowserFidelity? = null,
    val modal: BrowserModalState? = null,
) {
    internal fun chatPreviewVisible(chatId: String?): Boolean =
        chatPage != null && chatId != null && watchSessionId == chatId && !visible && handoff == null

    /** Derived, never written: both the button and the banner read this. */
    val agentVisualActive: Boolean
        get() = BrowserDrivingPolicy.visualActive(
            agentRunActive, agentRendererUse, rendererVisible && !teardownRequested)
}

data class BrowserOpenResult(
    val url: String,
    val title: String,
    val chunks: Int,
    val needsRenderer: Boolean,
    val tier: BrowserTier,
    val capMb: Int,
    val handoff: Boolean = false,
    val httpStatus: Int? = null,
    val readerHttpStatus: Int? = null,
    val httpWarning: String? = null,
    val nodeCount: Int = 0,
    val hasCanvas: Boolean = false,
    val jsEnabled: Boolean = true,
    val viewportWidth: Int = 0,
    val viewportHeight: Int = 0,
)

data class BrowserEvalResult(val content: String, val origin: String)

enum class BrowserKeyAction { Press, Down, Up }

enum class BrowserModalKind { Alert, Confirm, Prompt }

data class BrowserModalState(val kind: BrowserModalKind, val message: String)

data class BrowserKeyTarget(
    val found: Boolean,
    val sensitive: Boolean,
    val risky: Boolean,
    val description: String,
)

data class BrowserStatusResult(
    val alive: Boolean,
    val healthy: Boolean,
    val mode: String,
    val errorKind: String?,
    val url: String,
    val title: String,
    val tier: BrowserTier,
    val capMb: Int,
    val loopbackExempt: Boolean,
    val tierAllows: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val pageReady: Boolean,
    val allowJs: Boolean,
    val userDriving: Boolean,
    val handoff: Boolean,
    val jsErrors: Int,
    val lastError: String?,
    val hint: String? = null,
    val modal: BrowserModalState? = null,
)

data class BrowserResetResult(
    val reset: Boolean,
    val rendererKilled: Boolean,
    val storageCleared: Boolean,
    val reopened: Boolean,
    val previousUrl: String,
    val url: String,
    val title: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val tier: BrowserTier,
    val alive: Boolean,
    val detail: String? = null,
)

data class BrowserWaitResult(
    val matched: Boolean,
    val elapsedMs: Long,
    val url: String,
    val title: String,
    val condition: String,
)

data class BrowserKeyResult(
    val key: String,
    val modifiers: List<String>,
    val action: String,
    val dispatched: String,
    val url: String,
    val changed: String,
    val target: String,
)

data class BrowserConsoleSnapshot(
    val result: BrowserConsoleQueryResult,
    val rendererAlive: Boolean,
    val url: String,
)

data class BrowserReadResult(
    val url: String,
    val title: String,
    val chunk: Int,
    val total: Int,
    val anchor: String,
    val text: String,
    val isLast: Boolean,
    val refs: List<BrowserElementRef> = emptyList(),
)


/**
 * Session-scoped browser state and agent backend. The WebView is owned by the sheet
 * host and attached here; the session never creates a second renderer.
 */
private const val LOAD_VALVE_NOTICE =
    "The phone cannot handle this project's load; the browser renderer was stopped."

class BrowserSession(
    private val app: Application,
    private val models: LocalModelManager?,
) : ComponentCallbacks2 {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val actionLock = Mutex()
    private val prefs = app.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val readerService = ReaderService()
    private val readerCache = ReaderCache()
    private val consoleBuffer = BrowserConsoleBuffer()
    private val trail = BrowserDomainTrail()
    private var snapshots = BrowserSnapshotStore(File(app.filesDir, "browser-snapshots"))
    private var shotStore = BrowserShotStore(app.filesDir, "unbound")
    var projectKey: String = "unbound"
        set(value) {
            field = value.ifBlank { "unbound" }
            shotStore = BrowserShotStore(app.filesDir, field)
        }
    private val windows = linkedMapOf<String, BrowserSnapshot>().apply {
        snapshots.loadAll().forEach { put(it.id, it) }
    }
    private val tabIcons = mutableMapOf<String, Pair<String, android.graphics.Bitmap>>()
    private var activeWindowId: String? = null
    private var reader: ReaderDocument? = null
    private val hangValve = BrowserHangValve()
    private var hangArmed = false
    private var hangCallback: Choreographer.FrameCallback? = null
    private var paintWatchdogJob: kotlinx.coroutines.Job? = null
    private var pendingRestart: RendererRestart? = null
    private var rendererRecoveryJob: kotlinx.coroutines.Job? = null
    private var rendererGeneration = 0L
    private var navigationGeneration = 0L
    private var lastRecoveryUrl: String? = null
    private var pageLoading: Boolean = false
    @Volatile private var pendingMainFrameError: BrowserFailure? = null
    private var lastMainHttpStatus: Int? = null
    private var lastHttpWarning: String? = null
    private var activeUrl: String = ""
    private var activeTitle: String = ""
    private var authLimitStated = false
    private var renderer: WebView? = null
    /** The view the session last tore down, so a caller can tell "released" from "never mine". */
    private var releasedRenderer: WebView? = null
    private var pendingModal: PendingModal? = null
    private var teardown = RendererTeardown()
    private val processRegistry = RendererProcessRegistry()
    internal var rendererTerminateSupported: () -> Boolean = {
        runCatching {
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_TERMINATE)
        }.getOrDefault(false)
    }
    internal var renderProcessHandleProvider: (WebView) -> Pair<Any, (Any) -> Boolean>? = { view ->
        if (runCatching { WebViewFeature.isFeatureSupported(WebViewFeature.GET_WEB_VIEW_RENDERER) }.getOrDefault(false)) {
            WebViewCompat.getWebViewRenderProcess(view)?.let { process ->
                process to { target -> (target as androidx.webkit.WebViewRenderProcess).terminate() }
            }
        } else null
    }
    private var rendererFailure: String? = null
    private var rendererReady: CompletableDeferred<WebView>? = null
    private var pageFinished: CompletableDeferred<Unit>? = null
    private var budget = BrowserGovernor.decide(BrowserMemorySignals(0))
    private var lastRendererStart = 0L
    private var lastActivityAt = 0L
    private var userControlAt = 0L
    internal var memoryProbe = BrowserMemoryProbe(rendererPss = { sampleRendererPss() })
    private var baselineOwnPss = 0L
    private var baselineAvailMem = 0L
    private var lastOwnGrowthBytes = 0L
    private var observedBrowserBytes = 0L
    private var elementRefs: List<BrowserElementRef> = emptyList()
    private var pendingSnapshotRestore: Pair<String, BrowserSnapshot>? = null
    private var pendingRendererLoad: String? = null
    private var idleJob: kotlinx.coroutines.Job? = null
    private val idleArming = BrowserIdleArming()
    private var appBackgrounded = false
    private var releasingView: WebView? = null
    private var pendingGoneView: WebView? = null
    private var pendingGoneJob: kotlinx.coroutines.Job? = null
    private var releaseJob: kotlinx.coroutines.Job? = null
    private var periodic: kotlinx.coroutines.Job? = null
    private var baselineJob: kotlinx.coroutines.Job? = null
    private var postLoadBudgetJob: kotlinx.coroutines.Job? = null
    private var teardownWatchdog: kotlinx.coroutines.Job? = null
    private val releasedViews = java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>())
    private val rotationScroll = ScrollRestore()
    private var scrollLayoutListener: android.view.View.OnLayoutChangeListener? = null
    private var lastPaintedFrame: NavigationFrame? = null

    private val _state = MutableStateFlow(BrowserUiState(
        allowJs = prefs.getBoolean("allow_js", true),
        blockThirdParty = prefs.getBoolean("block_third_party", true),
        desktopMode = prefs.getBoolean("desktop", BrowserDesktopMode.DEFAULT_DESKTOP),
    ))
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    var bookmarkWriter: (suspend (String, String) -> String?)? = null
    var previewStarter: (suspend (String, String) -> String?)? = null
    var customTabOpener: ((String) -> Boolean)? = null

    init {
        app.registerComponentCallbacks(this)
        refreshBudget()
        models?.loadedId?.let { loaded -> scope.launch { loaded.collect { refreshBudget() } } }
        models?.modelFootprintBytes?.let { footprint ->
            scope.launch { footprint.collect { refreshBudget() } }
        }
    }

    fun beginAgentRun(sessionId: String? = null) {
        _state.update { it.copy(agentRunActive = true, agentRendererUse = false,
            watchVisible = it.watchVisible && it.watchSessionId == sessionId, watchSessionId = sessionId,
            chatPage = if (it.watchSessionId == sessionId) it.chatPage else null,
            chatBrowserWorking = false, driving = BrowserDriving.Agent) }
    }

    internal fun dismissChatPreview() {
        _state.update { it.copy(watchVisible = false, chatPage = null, chatBrowserWorking = false) }
    }

    private fun markChatBrowserWork(page: BrowserChatPage? = null) {
        _state.update {
            val target = page ?: it.chatPage
            if (!it.agentRunActive || it.watchSessionId == null || target == null) it
            else it.copy(chatPage = target, chatBrowserWorking = true)
        }
    }

    fun endAgentRun() {
        releaseJob?.cancel()
        releaseJob = null
        _state.update { it.copy(agentRunActive = false, agentRendererUse = false,
            chatBrowserWorking = false, driving = BrowserDriving.Agent) }
    }

    /** Human open: always the full-screen visual browser, never the reader face. */
    fun userOpen() = userOpen(activeUrl.ifBlank { _state.value.url }
        .takeUnless { it.isBlank() || it == "about:blank" } ?: BrowserUrlPolicy.START_PAGE)

    private fun userOpen(target: String, restore: Boolean = false) {
        idleJob?.cancel(); idleJob = null
        idleArming.onActivity()
        lastActivityAt = System.currentTimeMillis()
        val wasReader = _state.value.readerMode
        _state.update { it.copy(visible = true, readerMode = false, error = null, errorRetry = false) }
        if (target.isNotBlank() &&
            (wasReader || renderer == null || _state.value.teardownRequested || _state.value.url != target)) {
            scope.launch {
                cancelRendererRecovery()
                try {
                    openVisual(target, userInitiated = true, restart = null, restoreSnapshot = restore || wasReader)
                } catch (failure: BrowserFailure) {
                    showError(failure)
                }
            }
        }
    }

    fun show() = userOpen()

    /** Watch an existing AI visual session without reloading it. */
    fun watch() {
        idleJob?.cancel(); idleJob = null
        idleArming.onActivity()
        lastActivityAt = System.currentTimeMillis()
        if (!_state.value.readerMode && !_state.value.teardownRequested &&
            (renderer != null || _state.value.rendererVisible)) {
            _state.update { it.copy(visible = true, readerMode = false) }
        } else userOpen()
    }

    internal fun openChatBrowser() {
        val current = _state.value
        val page = current.chatPage ?: return
        if (current.rendererVisible && !current.teardownRequested && current.url == page.url) {
            watch()
        } else if (page.url == activeUrl && !BrowserGovernor.canOpenRenderer(budget.tier, interaction = true)) {
            _state.update { it.copy(visible = true, url = page.url, title = page.title, error = null) }
            scope.launch {
                try { showReader() }
                catch (failure: BrowserFailure) { showError(failure) }
            }
        } else {
            userOpen(page.url)
        }
    }

    /** Closing the screen hides the chat card and lets the visual session idle-expire. */
    fun close() {
        cancelRendererRecovery()
        _state.update { it.copy(visible = false, readerMode = false, watchVisible = false,
            chatPage = null, chatBrowserWorking = false) }
        idleArming.onHidden()
        armIdle()
    }

    /** MainActivity.onStop: the same three-minute clock, not a second timer. */
    fun onAppBackground() {
        appBackgrounded = true
        if (idleArming.onBackground(renderer != null || _state.value.rendererVisible,
                System.currentTimeMillis())) armIdle()
    }

    /** MainActivity.onStart: enforce an expired deadline, or rearm a still-hidden browser. */
    fun onAppForeground() {
        appBackgrounded = false
        val now = System.currentTimeMillis()
        val visible = _state.value.visible
        val wasArmed = idleArming.armed
        val deadline = idleArming.deadline
        if (idleArming.onForeground(now)) {
            idleJob?.cancel()
            idleJob = null
            endSession()
        } else if (BrowserIdleTimer.shouldRearmHiddenBrowser(visible, wasArmed, deadline, now)) {
            armIdle(remainingMs = deadline - now, deadline = deadline)
        } else {
            idleJob?.cancel()
            idleJob = null
        }
    }

    private fun armIdle(
        remainingMs: Long = BrowserIdleTimer.IDLE_MS,
        deadline: Long = System.currentTimeMillis() + remainingMs,
    ) {
        idleJob?.cancel()
        idleArming.armUntil(deadline)
        idleJob = scope.launch {
            kotlinx.coroutines.delay(remainingMs)
            if (BrowserIdleTimer.shouldEndOnTimer(_state.value.visible, appBackgrounded)) endSession()
        }
    }

    /** The visible Close control and browser_close: save, destroy, free, clear the card. */
    fun endSession() {
        cancelRendererRecovery()
        postLoadBudgetJob?.cancel()
        postLoadBudgetJob = null
        releaseJob?.cancel()
        releaseJob = null
        idleJob?.cancel()
        idleJob = null
        idleArming.onActivity()
        val id = activeWindowId
        val current = id?.let { windows[it] }
        if (id != null && current != null) {
            snapshots.save(current.copy(title = activeTitle,
                lastUsedAt = System.currentTimeMillis()))
        }
        saveActiveSnapshot()
        readerCache.invalidate()
        elementRefs = emptyList()
        pendingSnapshotRestore = null
        _state.update {
            it.copy(visible = false, readerMode = false,
                visualScreenshot = null, watchVisible = false, pageReady = false, driving = BrowserDriving.Agent,
                chatPage = null, chatBrowserWorking = false,
                agentRendererUse = false, budgetCloseAction = false,
                handoff = null, authNotice = null, downloadPrompt = null, loadValveNotice = null)
        }
        disarmHangValve()
        consoleBuffer.clear()
        shotStore.cleanSession()
        requestRendererTeardown()
        armTeardownWatchdog()
    }

    fun clearNotice() {
        _state.update { it.copy(notice = null, error = null, loadValveNotice = null) }
    }

    fun contextLine(): String {
        refreshBudget()
        val line = BrowserGovernor.contextLine(budget)
        val modes = " Browser text reads stay invisible; visual page is for the user."
        val valve = _state.value.loadValveNotice?.let { " $it" }.orEmpty()
        return if (_state.value.authNotice != null) "$line$modes Identity sign-in stays in your browser.$valve"
        else "$line$modes$valve"
    }

    fun refreshBudget(publish: Boolean = true, ownGrowthBytes: Long? = null) {
        if (ownGrowthBytes != null) lastOwnGrowthBytes = ownGrowthBytes.coerceAtLeast(0L)
        val manager = app.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        val loaded = models?.loadedId?.value != null
        val footprint = if (loaded) models?.modelFootprintBytes?.value ?: 0L else 0L
        val liveUrl = activeUrl.ifBlank { _state.value.url }
        val loopback = BrowserAuthGuard.isLoopback(liveUrl)
        if (loopback) {
            lastOwnGrowthBytes = 0L
            observedBrowserBytes = 0L
            budget = BrowserGovernor.decide(BrowserMemorySignals(info.availMem, footprint,
                observedBrowserBytes = 0L, loopbackLive = true))
            if (publish) {
                _state.update {
                    it.copy(tier = BrowserTier.Normal, capMb = 0, modelLoaded = budget.modelLoaded,
                        budgetLine = BrowserGovernor.contextLine(budget),
                        budgetCloseAction = false)
                }
            }
            armHangValve()
            return
        }
        disarmHangValve()
        if (baselineAvailMem > 0L) {
            observedBrowserBytes = BrowserObservedCost.bytes(
                lastOwnGrowthBytes, baselineAvailMem, info.availMem)
        } else if (ownGrowthBytes != null) {
            observedBrowserBytes = lastOwnGrowthBytes
        }
        val previousTier = budget.tier
        budget = BrowserGovernor.decide(BrowserMemorySignals(info.availMem, footprint,
            observedBrowserBytes = observedBrowserBytes))
        if (publish) {
            val visible = _state.value.visible
            val actions = if (_state.value.rendererVisible) {
                BrowserShedLadder.actions(previousTier, budget.tier, visible)
            } else emptyList()
            val announcements = mutableListOf<String>()
            actions.forEach { action ->
                when (action) {
                    BrowserShedAction.TightenDiet -> {
                        _state.update { it.copy(fullPage = false) }
                        // Reload so the already-loaded page actually gets placeholders.
                        renderer?.let { view -> navigate(view) { reload() } }
                        announcements += "diet tightened"
                    }
                    BrowserShedAction.UnloadFarthestWindow -> {
                        unloadInactiveWindows()
                        announcements += "an inactive window was unloaded"
                    }
                    BrowserShedAction.ReaderFirstMessage ->
                        announcements += "Browser is over its memory budget — switched to reader-first."
                    BrowserShedAction.ShowBudgetCloseAction ->
                        announcements += "Browser is over its memory budget — close the browser to free memory."
                    BrowserShedAction.DestroyHiddenRenderer -> requestRendererTeardown()
                }
            }
            var budgetAction = false
            if (budget.tier == BrowserTier.NoRenderer && _state.value.rendererVisible && visible) {
                budgetAction = true
                if (announcements.none { it.contains("close the browser") }) {
                    announcements += "Browser is over its memory budget — close the browser to free memory."
                }
            }
            if (budget.tier != BrowserTier.NoRenderer) budgetAction = false
            val prefix = "Browser memory: ${budget.observedMb}MB used."
            _state.update {
                it.copy(tier = budget.tier, capMb = budget.capMb, modelLoaded = budget.modelLoaded,
                    budgetLine = BrowserGovernor.contextLine(budget),
                    budgetCloseAction = budgetAction,
                    notice = if (announcements.isEmpty()) it.notice
                    else (listOf(prefix) + announcements).joinToString(" "))
            }
        }
        if (publish && !BrowserGovernor.canOpenRenderer(budget.tier, interaction = true) &&
            _state.value.rendererVisible && !_state.value.visible) {
            requestRendererTeardown()
        }
    }

    private fun sampleRendererPss(): Long? = runCatching {
        val manager = app.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val ownPid = android.os.Process.myPid()
        val ownUid = android.os.Process.myUid()
        val candidates = manager.runningAppProcesses.orEmpty()
            .filter { it.uid == ownUid && it.pid != ownPid }
        if (candidates.isEmpty()) return null
        val memory = manager.getProcessMemoryInfo(candidates.map { it.pid }.toIntArray())
        val processes = candidates.mapIndexed { index, process ->
            RendererProcess(process.pid, process.uid,
                (memory.getOrNull(index)?.totalPss?.toLong() ?: 0L) * 1024L)
        }
        selectRendererPss(processes, ownPid, ownUid)
    }.getOrNull()

    private fun availableMemBytes(): Long? {
        val manager = app.getSystemService(android.app.ActivityManager::class.java) ?: return null
        val info = android.app.ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        return info.availMem
    }

    private fun unloadInactiveWindows() {
        val active = activeWindowId
        val farthest = windows.filterKeys { it != active }
            .minByOrNull { it.value.lastUsedAt }?.key ?: return
        windows.remove(farthest)
        _state.update { it.copy(windows = windowSummaries()) }
    }

    internal suspend fun openForAgent(
        url: String,
        newWindow: Boolean,
        projectPreview: Boolean,
        claimRenderer: () -> Boolean,
    ): BrowserOpenResult {
        val page = BrowserChatPage(url)
        markChatBrowserWork(page)
        try {
            if (projectPreview) {
                return openVisual(url, userInitiated = false, newWindow = newWindow,
                    projectPreview = true, agentAction = true)
            }

            val result = openText(url, newWindow)
            val excerpt = reader?.text.orEmpty().trim().take(320)
            _state.update {
                if (it.chatPage !== page) it
                else it.copy(chatPage = BrowserChatPage(result.url, result.title, excerpt))
            }
            if (!result.handoff && _state.value.agentRunActive &&
                BrowserGovernor.canTakeScreenshot(result.tier) && claimRenderer()) {
                try {
                    openVisual(result.url, userInitiated = false, agentAction = true)
                } catch (_: BrowserFailure) { }
            }
            return result
        } catch (failure: BrowserFailure) {
            dismissUnfinishedChatPreview(page)
            throw failure
        } catch (cancelled: CancellationException) {
            dismissUnfinishedChatPreview(page)
            throw cancelled
        }
    }

    private fun dismissUnfinishedChatPreview(page: BrowserChatPage) {
        _state.update {
            if (it.chatPage !== page) it else it.copy(chatPage = null, chatBrowserWorking = false)
        }
    }

    suspend fun open(rawUrl: String, newWindow: Boolean = false, projectPreview: Boolean = false): BrowserOpenResult =
        openText(rawUrl, newWindow, projectPreview)

    /** Invisible AI text path: fetch and parse without touching screen state. */
    suspend fun openText(rawUrl: String, newWindow: Boolean = false, projectPreview: Boolean = false): BrowserOpenResult =
        actionLock.withLock {
            val url = BrowserUrlPolicy.normalize(rawUrl)
            if (BrowserAuthGuard.isAuthSurface(url)) {
                stateHandoff(url, "identity")
                return@withLock BrowserOpenResult(url, "", 0, false, budget.tier, budget.capMb, handoff = true)
            }
            try {
                val page = readerService.fetchPage(url, visibleOnly = true)
                if (newWindow || activeWindowId == null) createWindow(url, publish = false)
                activeUrl = url
                elementRefs = emptyList()
                readerCache.navigate()
                refreshBudget(publish = false)
                val document = page.document
                readerCache.record(readerCache.navigationToken, document, visibleOnly = true)
                page.rawHtml?.let { readerCache.recordRaw(readerCache.navigationToken, it) }
                reader = document
                activeTitle = document.title
                recordVisit(url, projectPreview || BrowserAuthGuard.isLoopback(url), publish = false)
                updateActiveWindow(url, document.title, publish = false)
                BrowserOpenResult(url, document.title, document.chunks.size,
                    document.needsRenderer, budget.tier, budget.capMb,
                    readerHttpStatus = page.httpStatus, nodeCount = page.nodeCount,
                    hasCanvas = page.hasCanvas, jsEnabled = _state.value.allowJs)
            } catch (handoff: BrowserHandoff) {
                stateHandoff(handoff.url, handoff.reason)
                BrowserOpenResult(url, "", 0, false, budget.tier, budget.capMb, handoff = true)
            }
        }

    /** Full visual load for a human or an agent interaction. */
    suspend fun openVisual(rawUrl: String, userInitiated: Boolean = true,
                           newWindow: Boolean = false, projectPreview: Boolean = false,
                           agentAction: Boolean = false): BrowserOpenResult {
        cancelRendererRecovery()
        return openVisual(rawUrl, userInitiated, newWindow, projectPreview, agentAction, restart = null)
    }

    private suspend fun openVisual(rawUrl: String, userInitiated: Boolean, newWindow: Boolean = false,
                                   projectPreview: Boolean = false, agentAction: Boolean = false,
                                   restart: RendererRestart?, forceLoad: Boolean = false,
                                   restoreSnapshot: Boolean = false): BrowserOpenResult =
        actionLock.withLock {
            if (restart != null) {
                if (pendingRestart !== restart || rendererGeneration != restart.generation ||
                    activeWindowId != restart.windowId || activeUrl != restart.url) {
                    throw CancellationException("Renderer recovery superseded")
                }
                pendingRestart = null
            } else {
                pendingRestart = null
                lastRecoveryUrl = null
            }
            idleJob?.cancel(); idleJob = null
            idleArming.onActivity()
            val url = BrowserUrlPolicy.normalize(rawUrl)
            activeUrl = url
            pendingMainFrameError = null
            lastMainHttpStatus = null
            lastHttpWarning = null
            if (BrowserAuthGuard.isLoopback(url)) {
                val probe = withContext(Dispatchers.IO) { BrowserOpenPreflight.probeLoopback(url) }
                lastMainHttpStatus = probe.httpStatus
                lastHttpWarning = probe.warning
            }
            elementRefs = emptyList()
            readerCache.navigate()
            refreshBudget(publish = true)
            if (BrowserAuthGuard.isAuthSurface(url)) {
                stateHandoff(url, "identity")
                return@withLock BrowserOpenResult(url, "", 0, false, budget.tier, budget.capMb, handoff = true)
            }
            if (newWindow || activeWindowId == null) createWindow(url, publish = userInitiated)
            activeUrl = url
            reader = null
            // Wait until ensureRenderer() eligibility checks pass before flipping rendererVisible.
            _state.update {
                it.copy(visible = it.visible || userInitiated,
                    pageReady = false, visualScreenshot = null,
                    url = url, mode = BrowserMode.Renderer,
                    readerMode = false, error = null, errorRetry = false, notice = null, handoff = null,
                    authNotice = null, loadValveNotice = null)
            }
            pendingSnapshotRestore = activeWindowId?.let { id ->
                val snapshot = restart?.snapshot ?: if (restoreSnapshot) windows[id] else null
                snapshot?.let { id to it }
            }
            val view = ensureRenderer(showWatch = !userInitiated,
                allowUserDriving = userInitiated || restart != null, agentAction = agentAction)
            loadForInteraction(view, url, forceLoad)
            awaitLaidOut(view)
            if (view !== renderer || _state.value.teardownRequested) {
                throw BrowserFailure("browser_renderer_unavailable", "The page renderer stopped.")
            }
            pendingMainFrameError?.let { throw it }
            schedulePostLoadBudgetChecks()
            activeUrl = view.url ?: url
            activeTitle = view.title.orEmpty()
            recordVisit(activeUrl, projectPreview || BrowserAuthGuard.isLoopback(url), publish = userInitiated)
            updateActiveWindow(activeUrl, activeTitle, publish = userInitiated)
            _state.update {
                it.copy(url = activeUrl, title = activeTitle, mode = BrowserMode.Renderer,
                    pageReady = true, fidelity = BrowserFidelity.Reloaded,
                    activeWindowId = activeWindowId,
                    windows = if (userInitiated) windowSummaries() else it.windows)
            }
            val diagnostics = withTimeoutOrNull(1_500) {
                val raw = evaluate(view, """
                    (function(){return JSON.stringify({
                      nodes:document.querySelectorAll('*').length,
                      canvas:!!document.querySelector('canvas')
                    });})()
                """.trimIndent())
                val clean = BrowserElementRefs.unwrapJsResult(raw)
                runCatching { org.json.JSONObject(clean) }.getOrNull()
            }
            val vw = withContext(Dispatchers.Main) { view.width }
            val vh = withContext(Dispatchers.Main) { view.height }
            BrowserOpenResult(activeUrl, activeTitle, 0, false, budget.tier, budget.capMb,
                httpStatus = lastMainHttpStatus,
                httpWarning = lastHttpWarning ?: lastMainHttpStatus?.takeIf { it >= 400 }?.let {
                    "Server returned HTTP $it. Check preview_entry or server routes."
                },
                nodeCount = diagnostics?.optInt("nodes", 0) ?: 0,
                hasCanvas = diagnostics?.optBoolean("canvas", false) ?: false,
                jsEnabled = _state.value.allowJs,
                viewportWidth = vw, viewportHeight = vh)
        }

    /**
     * Same-origin, bounded synchronous JS evaluation. Promises are not awaited; use
     * waitFor for async conditions. Auth surfaces and cross-origin navigation are
     * refused before and after the call.
     */
    suspend fun eval(
        expression: String,
        expectedOrigin: String? = null,
        timeoutMs: Long = 2_000L,
    ): BrowserEvalResult = actionLock.withLock {
        requireNoPageModal()
        // A handoff is a valid open state even though it has no page URL; report the
        // reason the user needs, not "open a page first".
        if (_state.value.handoff != null) {
            throw BrowserFailure("browser_handoff",
                "Identity work moved to the phone's browser. Continue there, then share the page back.")
        }
        if (activeUrl.isBlank()) throw BrowserFailure("browser_not_open", "Open a page first.")
        if (!_state.value.allowJs) {
            throw BrowserFailure("browser_js_disabled",
                "JavaScript is disabled. Enable JavaScript in browser settings or use browser_read.")
        }
        val expected = expectedOrigin?.takeIf { it.isNotBlank() }
        if (expected != null && BrowserEvalPolicy.originOf(expected) == null) {
            throw BrowserFailure("browser_origin_mismatch",
                "expected_origin is not an http(s) origin. Pass an origin such as http://127.0.0.1:45179.")
        }
        val view = ensureRenderer()
        loadInteraction(view)
        awaitLaidOut(view)
        val liveUrl = withContext(Dispatchers.Main) { view.url } ?: activeUrl
        if (BrowserAuthGuard.isAuthSurface(liveUrl) || _state.value.handoff != null) {
            throw BrowserFailure("browser_handoff",
                "Identity work moved to the phone's browser. Continue there, then share the page back.")
        }
        val actualOrigin = BrowserEvalPolicy.originOf(liveUrl)
            ?: throw BrowserFailure("browser_origin_mismatch",
                "The live page has no http(s) origin. browser_eval only runs on http(s) pages; use browser_read.")
        if (expected != null && BrowserEvalPolicy.originOf(expected) != actualOrigin) {
            throw BrowserFailure("browser_origin_mismatch",
                "Expected origin $expected but the live page is $actualOrigin. Re-open the expected page with browser_open.")
        }
        val wrapped = try {
            BrowserEvalPolicy.wrapScript(expression, maxChars = BrowserEvalPolicy.MAX_SCRIPT_CHARS)
        } catch (e: IllegalArgumentException) {
            throw BrowserFailure("invalid_arguments", e.message ?: "The JS expression is invalid or too large.")
        }
        val timeout = timeoutMs.coerceIn(100L, 5_000L)
        val raw = withTimeoutOrNull(timeout) { evalScript(view, wrapped) }
            ?: throw BrowserFailure("browser_eval_timeout",
                "Script evaluation timed out after ${timeout}ms. Simplify the expression or call browser_reset(reopen=true) if page JS hung.")
        val clean = BrowserElementRefs.unwrapJsResult(raw)
        val bounded = BrowserEvalPolicy.boundJson(clean)
        val payload = runCatching { org.json.JSONObject(bounded.json) }.getOrElse {
            throw BrowserFailure("browser_eval_exception",
                "The page returned an unreadable JS result. Retry with a simpler expression or inspect browser_console.")
        }
        payload.put("origin", actualOrigin)
        payload.put("truncated", payload.optBoolean("truncated", bounded.truncated))
        val afterUrl = withContext(Dispatchers.Main) { view.url } ?: activeUrl
        if (BrowserAuthGuard.isAuthSurface(afterUrl) || _state.value.handoff != null) {
            throw BrowserFailure("browser_handoff",
                "The page moved to an identity surface during evaluation. Continue in the phone's browser.")
        }
        if (BrowserEvalPolicy.originOf(afterUrl) != actualOrigin) {
            throw BrowserFailure("browser_origin_mismatch",
                "The page navigated to ${BrowserEvalPolicy.originOf(afterUrl) ?: "an unknown origin"} during evaluation. Re-open the expected page and retry.")
        }
        BrowserEvalResult(payload.toString(), actualOrigin)
    }

    internal var evalScript: suspend (WebView, String) -> String = { view, script ->
        evaluate(view, script)
    }

    internal var loadInteraction: suspend (WebView) -> Unit = { view ->
        loadForInteraction(view, activeUrl)
    }

    internal var rendererLayoutTimeoutMs: Long = 4_000L

    internal suspend fun awaitLaidOut(view: WebView, timeoutMs: Long = rendererLayoutTimeoutMs) {
        fun outcome(): LayoutWait = layoutWait(
            width = view.width, height = view.height,
            rendererLost = rendererWasLost(
                teardownRequested = _state.value.teardownRequested,
                released = releasedRenderer,
                current = renderer,
                view = view,
            ),
        )
        if (withContext(Dispatchers.Main) { outcome() } == LayoutWait.Ready) return
        withContext(Dispatchers.Main) { view.requestLayout() }
        val finished = withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            while (true) {
                when (withContext(Dispatchers.Main) { outcome() }) {
                    LayoutWait.Ready -> return@withTimeoutOrNull true
                    // The memory governor can take the renderer away while the sheet is
                    // still attaching. That is not a degenerate viewport, and saying so
                    // sent people to a browser_reset call they cannot make from the UI.
                    LayoutWait.LostRenderer -> return@withTimeoutOrNull false
                    LayoutWait.Waiting -> delay(16L)
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        } ?: false
        if (!finished) {
            throw BrowserFailure("browser_renderer_unavailable",
                "The browser viewport is degenerate (1x1 or not laid out). Call browser_reset(reopen=true) to rebuild the renderer.")
        }
    }

    /**
     * Targeted renderer/session-rot recovery. Unlike browser_close this does not save
     * stale page state for the next open; unlike clearBrowserData it never touches
     * other projects, cookies/DOM storage (unless asked), or the domain trail.
     */
    /** Non-destructive health probe. Never creates or attaches a renderer. */
    suspend fun status(): BrowserStatusResult = withContext(Dispatchers.Main.immediate) {
        refreshBudget(publish = false)
        val current = _state.value
        val gone = rendererFailure != null
        val live = renderer?.takeUnless { gone || current.teardownRequested }
        val alive = live != null
        val width = live?.width ?: 0
        val height = live?.height ?: 0
        val loopback = budget.loopbackExempt || BrowserAuthGuard.isLoopback(activeUrl)
        val guide = BrowserGovernor.tierGuide(budget.tier, loopback)
        val readerMode = renderer == null && (current.readerMode || reader != null)
        val healthy = !gone && (readerMode || (alive && width > 1 && height > 1))
        val modal = current.modal
        val hint = when {
            gone -> "The renderer is gone. Call browser_reset(reopen=true) to rebuild it."
            modal != null -> "page_modal_blocked: a JavaScript ${modal.kind.name.lowercase()} dialog is showing. " +
                "Call browser_reset(reopen=true) to clear it."
            alive && (width <= 1 || height <= 1) ->
                "Viewport is degenerate (1x1). Call browser_reset(reopen=true) to rebuild the renderer."
            else -> null
        }
        BrowserStatusResult(
            alive = alive,
            healthy = healthy,
            mode = when {
                gone -> "gone"
                alive -> "renderer"
                readerMode -> "reader"
                else -> "none"
            },
            errorKind = if (gone) "browser_renderer_unavailable" else null,
            url = _state.value.url.ifBlank { activeUrl },
            title = _state.value.title.ifBlank { activeTitle },
            tier = budget.tier,
            capMb = budget.capMb,
            loopbackExempt = loopback,
            tierAllows = guide.summary,
            viewportWidth = width,
            viewportHeight = height,
            pageReady = !gone && current.pageReady,
            allowJs = _state.value.allowJs,
            userDriving = _state.value.driving == BrowserDriving.User,
            handoff = _state.value.handoff != null,
            jsErrors = consoleBuffer.errorCount(),
            lastError = consoleBuffer.lastErrorMessage(),
            hint = hint,
            modal = modal,
        )
    }

    suspend fun reset(reopen: Boolean = true, clearStorage: Boolean = false): BrowserResetResult {
        if (!reopen) dismissChatPreview()
        val previousUrl = activeUrl.ifBlank { _state.value.url }
        cancelRendererRecovery()
        lastRecoveryUrl = null
        postLoadBudgetJob?.cancel(); postLoadBudgetJob = null
        paintWatchdogJob?.cancel(); paintWatchdogJob = null
        pendingSnapshotRestore = null
        rotationScroll.clear()
        lastPaintedFrame = null
        pageLoading = false
        readerCache.invalidate()
        reader = null
        elementRefs = emptyList()
        consoleBuffer.clear()
        if (rendererFailure == null && !_state.value.teardownRequested) renderer?.clearCache(true)
        _state.update {
            it.copy(error = null, notice = null, loadValveNotice = null,
                visualScreenshot = null, pageReady = false, handoff = null, authNotice = null,
                downloadPrompt = null, budgetCloseAction = false)
        }
        requestRendererTeardown()
        awaitRendererTeardown()
        rendererFailure = null
        teardownWatchdog?.cancel(); teardownWatchdog = null
        activeTitle = ""
        activeWindowId?.let { id -> windows[id]?.let { snapshot ->
            val clean = snapshot.copy(title = "", scrollY = 0, formFields = emptyMap(),
                fidelity = BrowserFidelity.Reloaded)
            windows[id] = clean
            snapshots.save(clean)
        } }
        baselineOwnPss = 0L
        baselineAvailMem = 0L
        lastOwnGrowthBytes = 0L
        observedBrowserBytes = 0L
        _state.update {
            it.copy(activeWindowId = activeWindowId, windows = windowSummaries(),
                url = if (reopen) it.url else previousUrl, title = "",
                rendererVisible = false, pageReady = false, visualScreenshot = null,
                error = null, notice = null, loadValveNotice = null,
                mode = BrowserMode.Reader, fidelity = BrowserFidelity.Reloaded,
                teardownRequested = false)
        }
        var storageCleared = false
        if (clearStorage) {
            runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
            storageCleared = true
        }
        var reopened = false
        var url = previousUrl
        var title = ""
        var width = 0
        var height = 0
        var detail: String? = null
        if (reopen && previousUrl.isNotBlank()) {
            if (budget.tier == BrowserTier.NoRenderer) {
                detail = "Renderer reset succeeded, but memory tier norenderer blocks reopening the renderer. Use browser_read or open in the phone's browser."
            } else {
                markChatBrowserWork(BrowserChatPage(previousUrl))
                val result = openVisual(previousUrl, userInitiated = false,
                    projectPreview = BrowserAuthGuard.isLoopback(previousUrl), agentAction = true)
                reopened = true
                url = result.url
                title = result.title
                withContext(Dispatchers.Main) {
                    width = renderer?.width ?: 0
                    height = renderer?.height ?: 0
                }
            }
        } else if (reopen) {
            detail = "There was no previous URL to reopen."
        }
        val alive = renderer != null && !_state.value.teardownRequested
        return BrowserResetResult(true, true, storageCleared, reopened, previousUrl, url,
            title, width, height, budget.tier, alive, detail)
    }

    suspend fun waitFor(
        selector: String? = null,
        text: String? = null,
        gone: Boolean = false,
        networkIdle: Boolean = false,
        js: String? = null,
        timeoutMs: Long = 5_000L,
        pollMs: Long = 150L,
    ): BrowserWaitResult {
        if (selector.isNullOrBlank() && text.isNullOrBlank() && !networkIdle && js.isNullOrBlank()) {
            throw BrowserFailure("invalid_arguments",
                "Provide at least one condition: selector, text, network_idle, or js.")
        }
        if (activeUrl.isBlank()) throw BrowserFailure("browser_not_open", "Open a page first.")
        if (!js.isNullOrBlank() && !_state.value.allowJs) {
            throw BrowserFailure("browser_js_disabled",
                "JavaScript is disabled. Enable JavaScript or wait on selector/text instead.")
        }
        val timeout = timeoutMs.coerceIn(100L, 15_000L)
        val poll = pollMs.coerceIn(50L, 1_000L)
        actionLock.withLock {
            val view = ensureRenderer()
            loadForInteraction(view, activeUrl)
            val url = withContext(Dispatchers.Main) { view.url } ?: activeUrl
            if (BrowserAuthGuard.isAuthSurface(url) || _state.value.handoff != null) {
                throw BrowserFailure("browser_handoff",
                    "Identity work moved to the phone's browser. Continue there, then share the page back.")
            }
            installNetworkHook(view)
            installConsoleErrorHook(view)
        }
        val started = SystemClock.elapsedRealtime()
        var stableSince = 0L
        var stableCount = -1
        val condition = buildString {
            if (!selector.isNullOrBlank()) append("selector:$selector")
            if (!text.isNullOrBlank()) append(if (isNotEmpty()) " + " else "").append("text:\"$text\"")
            if (networkIdle) append(if (isNotEmpty()) " + " else "").append("network_idle")
            if (!js.isNullOrBlank()) append(if (isNotEmpty()) " + " else "").append("js")
            if (gone) append(" (gone=true)")
        }
        while (true) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (_state.value.driving == BrowserDriving.User) {
                throw BrowserFailure("browser_user_driving",
                    "The user is driving the browser. Ask them to hand it back, then retry browser_wait_for.")
            }
            if (BrowserAuthGuard.isAuthSurface(currentUrl()) || _state.value.handoff != null) {
                throw BrowserFailure("browser_handoff",
                    "Identity work moved to the phone's browser. Continue there, then share the page back.")
            }
            if (renderer == null || _state.value.teardownRequested) {
                throw BrowserFailure("browser_renderer_unavailable",
                    "The browser renderer stopped during the wait. Call browser_reset(reopen=true) and retry.")
            }
            val probe = actionLock.withLock { waitProbe(selector, text, gone, networkIdle, js) }
            var networkMatched = true
            if (networkIdle) {
                val now = SystemClock.elapsedRealtime()
                if (!pageLoading && probe.readyComplete && probe.inFlight == 0) {
                    if (stableCount != probe.resourceCount) {
                        stableCount = probe.resourceCount
                        stableSince = now
                    }
                    networkMatched = stableSince > 0L && now - stableSince >= 500L
                } else {
                    stableSince = 0L
                    stableCount = -1
                    networkMatched = false
                }
            }
            var matched = probe.selectorMatched && probe.textMatched && networkMatched
            if (js != null && js.isNotBlank() && matched) {
                matched = evalPredicate(js)
            }
            if (matched) {
                val url = currentUrl()
                return BrowserWaitResult(true, SystemClock.elapsedRealtime() - started,
                    url, _state.value.title, condition)
            }
            val elapsed = SystemClock.elapsedRealtime() - started
            if (elapsed >= timeout) {
                throw BrowserFailure("browser_wait_timeout",
                    "Timed out after ${elapsed}ms waiting for $condition. Inspect current state with browser_status, browser_console, or browser_read.")
            }
            delay(poll)
        }
    }

    private data class WaitProbe(
        val selectorMatched: Boolean,
        val textMatched: Boolean,
        val readyComplete: Boolean,
        val inFlight: Int,
        val resourceCount: Int,
    )

    private suspend fun waitProbe(
        selector: String?,
        text: String?,
        gone: Boolean,
        networkIdle: Boolean,
        js: String?,
    ): WaitProbe {
        val raw = evaluate(renderer ?: throw BrowserFailure("browser_renderer_unavailable",
            "The browser renderer stopped during the wait. Call browser_reset(reopen=true) and retry."),
            waitProbeScript(selector, text, gone))
        val clean = BrowserElementRefs.unwrapJsResult(raw)
        val obj = runCatching { org.json.JSONObject(clean) }.getOrNull()
        return WaitProbe(
            selectorMatched = obj?.optBoolean("selector", true) ?: true,
            textMatched = obj?.optBoolean("text", true) ?: true,
            readyComplete = obj?.optString("ready", "") == "complete",
            inFlight = obj?.optInt("inFlight", 0) ?: 0,
            resourceCount = obj?.optInt("resources", 0) ?: 0,
        )
    }

    private suspend fun evalPredicate(expression: String): Boolean {
        val view = renderer ?: return false
        val wrapped = try { BrowserEvalPolicy.wrapScript(expression) } catch (_: IllegalArgumentException) { return false }
        val raw = withTimeoutOrNull(500L) { evalScript(view, wrapped) } ?: return false
        val clean = BrowserElementRefs.unwrapJsResult(raw)
        val obj = runCatching { org.json.JSONObject(clean) }.getOrNull() ?: return false
        if (!obj.optBoolean("ok", false)) return false
        return when (val value = obj.opt("value")) {
            is Boolean -> value
            is Number -> value.toDouble() != 0.0
            is String -> value.isNotEmpty() && value != "false" && value != "0"
            null, org.json.JSONObject.NULL -> false
            else -> true
        }
    }

    private fun waitProbeScript(selector: String?, text: String?, gone: Boolean): String = """
        (function(){
          function visible(el){
            if(!el) return false;
            var cs=getComputedStyle(el);
            if(cs.display==='none'||cs.visibility==='hidden'||cs.visibility==='collapse'||
              parseFloat(cs.opacity)===0) return false;
            var r=el.getBoundingClientRect();
            return r.width>0&&r.height>0;
          }
          var sel=${jsonString(selector)};
          var txt=${jsonString(text)};
          var gone=${gone};
          var el=sel?document.querySelector(sel):null;
          var selMatch=sel?(gone?!visible(el):visible(el)):true;
          var body=(document.body&&document.body.innerText)||'';
          var textMatch=txt?(gone?body.indexOf(txt)<0:body.indexOf(txt)>=0):true;
          return JSON.stringify({selector:!!selMatch,text:!!textMatch,
            ready:document.readyState,inFlight:window.__rivetInFlight||0,
            resources:(performance.getEntriesByType?performance.getEntriesByType('resource').length:0)});
        })()
    """.trimIndent()

    private fun installNetworkHook(view: WebView) {
        if (!_state.value.allowJs) return
        view.evaluateJavascript("""
          (function(){
            if(window.__rivetNetHook) return;
            window.__rivetNetHook=1; window.__rivetInFlight=0;
            var f=window.fetch;
            if(f){window.fetch=function(){window.__rivetInFlight++;
              return f.apply(this,arguments).finally(function(){window.__rivetInFlight--;});};}
            var send=XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.send=function(){window.__rivetInFlight++;
              this.addEventListener('loadend',function(){window.__rivetInFlight--;});
              return send.apply(this,arguments);};
          })()
        """.trimIndent(), null)
    }

    suspend fun classifyKeyTarget(spec: BrowserKeySpec, selector: String?): BrowserKeyTarget =
        actionLock.withLock {
            requireNoPageModal()
            if (activeUrl.isBlank()) throw BrowserFailure("browser_not_open", "Open a page first.")
            val view = ensureRenderer()
            loadForInteraction(view, activeUrl)
            val url = withContext(Dispatchers.Main) { view.url } ?: activeUrl
            if (BrowserAuthGuard.isAuthSurface(url) || _state.value.handoff != null) {
                throw BrowserFailure("browser_handoff",
                    "Identity work moved to the phone's browser. Continue there, then share the page back.")
            }
            keyTarget(view, spec, selector)
        }

    suspend fun pressKey(
        spec: BrowserKeySpec,
        selector: String? = null,
        action: BrowserKeyAction = BrowserKeyAction.Press,
        holdMs: Int = 50,
    ): BrowserKeyResult = actionLock.withLock {
        requireNoPageModal()
        if (activeUrl.isBlank()) throw BrowserFailure("browser_not_open", "Open a page first.")
        val view = ensureRenderer()
        loadForInteraction(view, activeUrl)
        val beforeUrl = _state.value.url
        val beforeTitle = _state.value.title
        val url = withContext(Dispatchers.Main) { view.url } ?: activeUrl
        if (BrowserAuthGuard.isAuthSurface(url) || _state.value.handoff != null) {
            throw BrowserFailure("browser_handoff",
                "Identity work moved to the phone's browser. Continue there, then share the page back.")
        }
        val target = keyTarget(view, spec, selector)
        if (target.sensitive) {
            throw BrowserFailure("browser_sensitive_field",
                "Rivet will not send keys to a password, one-time-code, or payment field. Use the page in your browser or take over and type it yourself.")
        }
        if (!target.found) {
            throw BrowserFailure("browser_target",
                "Rivet couldn't find that key target. Check the selector or omit it to use the focused element.")
        }
        val clampedHold = holdMs.coerceIn(0, 2_000)
        val native = withContext(Dispatchers.Main) {
            view.isAttachedToWindow && view.hasWindowFocus()
        }
        var dispatched = "synthetic"
        if (native) {
            evaluate(view, keyCounterScript())
            withContext(Dispatchers.Main) {
                view.requestFocus()
                val downAt = SystemClock.uptimeMillis()
                fun event(code: Int, act: Int, at: Long) =
                    android.view.KeyEvent(at, at, act, code, 0, spec.metaState)
                when (action) {
                    BrowserKeyAction.Down -> view.dispatchKeyEvent(event(spec.keyCode,
                        android.view.KeyEvent.ACTION_DOWN, downAt))
                    BrowserKeyAction.Up -> view.dispatchKeyEvent(event(spec.keyCode,
                        android.view.KeyEvent.ACTION_UP, downAt))
                    BrowserKeyAction.Press -> {
                        view.dispatchKeyEvent(event(spec.keyCode,
                            android.view.KeyEvent.ACTION_DOWN, downAt))
                        if (clampedHold > 0) delay(clampedHold.toLong())
                        view.dispatchKeyEvent(event(spec.keyCode,
                            android.view.KeyEvent.ACTION_UP, downAt + clampedHold))
                    }
                }
            }
            if (action != BrowserKeyAction.Up) delay(16L)
            // The native event counter is read through the same bridge in a second call;
            // a missed read is treated as synthetic rather than double-fired.
            val counter = runCatching { evaluate(view, "window.__rivetKeyCount||0") }
                .getOrDefault("0").trim().toIntOrNull() ?: 0
            if (counter > 0) dispatched = "native"
            else syntheticKey(view, spec, action, target, url)
        } else {
            syntheticKey(view, spec, action, target, url)
        }
        requireNoPageModal()
        recordVisit(url, publish = false)
        val changed = changeLine(beforeUrl, beforeTitle)
        BrowserKeyResult(spec.key, spec.modifiers.map { it.name.lowercase() }.sorted(),
            action.name.lowercase(), dispatched, url, changed, target.description)
    }

    private suspend fun keyTarget(view: WebView, spec: BrowserKeySpec, selector: String?): BrowserKeyTarget {
        val raw = evaluate(view, keyTargetScript(selector, spec.key))
        val clean = BrowserElementRefs.unwrapJsResult(raw)
        val obj = runCatching { org.json.JSONObject(clean) }.getOrNull()
            ?: return BrowserKeyTarget(false, false, false, "")
        return BrowserKeyTarget(
            found = obj.optBoolean("found", false),
            sensitive = obj.optBoolean("sensitive", false),
            risky = obj.optBoolean("risky", false),
            description = obj.optString("description", "").take(120),
        )
    }

    private suspend fun syntheticKey(
        view: WebView,
        spec: BrowserKeySpec,
        action: BrowserKeyAction,
        target: BrowserKeyTarget,
        url: String,
    ) {
        evaluate(view, keyDispatchScript(spec, action, target.description))
    }

    private fun keyCounterScript(): String = """
        (function(){
          if(!window.__rivetKeyHook){
            window.__rivetKeyHook=1;
            window.addEventListener('keydown',function(){window.__rivetKeyCount=(window.__rivetKeyCount||0)+1;},true);
            window.addEventListener('keyup',function(){window.__rivetKeyCount=(window.__rivetKeyCount||0)+1;},true);
          }
          window.__rivetKeyCount=0;
          return '1';
        })()
    """.trimIndent()

    private fun keyTargetScript(selector: String?, key: String): String = """
        (function(){
          var sel=${jsonString(selector)};
          var ref=sel?/^#(\d+)$/.exec(sel):null;
          var el=ref?[].slice.call(document.querySelectorAll('a,button,input,[role=button]'))[parseInt(ref[1],10)-1]||null:(sel?document.querySelector(sel):document.activeElement);
          if(!el) return JSON.stringify({found:false,sensitive:false,risky:false,description:''});
          var type=(el.getAttribute('type')||el.type||'text').toLowerCase();
          var ac=(el.getAttribute('autocomplete')||'').toLowerCase();
          var hint=(el.getAttribute('name')||'')+' '+(el.getAttribute('placeholder')||'')+' '+(el.getAttribute('aria-label')||'')+' '+ac;
          var sensitive=type==='password'||type==='hidden'||type==='file'||
            /one-time-code|cc-|current-password|new-password/.test(ac)||
            /pass(word|cod)|otp|one.?time|card|cvv|cvc|security.?code/i.test(hint);
          var form=el.closest('form');
          var label=(el.innerText||el.value||el.getAttribute('aria-label')||'').trim();
          var risky=(type==='submit'||(form&&(el.type==='submit'||/submit|send|publish|pay|buy|post|delete|confirm/i.test(label))));
          try { el.focus(); } catch(e) {}
          return JSON.stringify({found:true,sensitive:!!sensitive,risky:!!risky,description:label||type||el.tagName});
        })()
    """.trimIndent()

    private fun keyDispatchScript(
        spec: BrowserKeySpec,
        action: BrowserKeyAction,
        target: String,
    ): String = """
        (function(){
          var el=document.activeElement||document.body||document;
          var key=${jsonString(spec.key)};
          var code=${jsonString(spec.code)};
          var codeNum=${spec.keyCode};
          var charNum=${spec.charCode};
          var init={key:key,code:code,keyCode:codeNum,which:codeNum,charCode:charNum,
            bubbles:true,cancelable:true,ctrlKey:${spec.ctrlKey},shiftKey:${spec.shiftKey},
            altKey:${spec.altKey},metaKey:${spec.metaKey}};
          var printable=key.length===1||key==='Enter';
          function fire(type){var e;try{e=new KeyboardEvent(type,init);}catch(x){
            e=document.createEvent('Event');e.initEvent(type,true,true);
            for(var k in init)e[k]=init[k];}el.dispatchEvent(e);}
          if('${action.name.lowercase()}'!=='up'){fire('keydown');if(printable)fire('keypress');}
          if('${action.name.lowercase()}'!=='down'){fire('keyup');}
          return '1';
        })()
    """.trimIndent()

    suspend fun read(chunk: Int = 0, visibleOnly: Boolean = true): BrowserReadResult = actionLock.withLock {
        markChatBrowserWork()
        val document = if (renderer != null && activeUrl.isNotBlank()) {
            // A live DOM may have changed since the last read; never serve a stale cache
            // over it just because the URL stayed the same.
            readRenderedDocument(visibleOnly)
        } else {
            val cached = readerCache.cached(visibleOnly)
            cached ?: readerCache.rawHtml()?.let { raw ->
                ReaderExtractor.extract(raw, activeUrl, visibleOnly).also {
                    readerCache.record(readerCache.navigationToken, it, visibleOnly)
                }
            } ?: throw BrowserFailure("browser_not_open",
                "Open a page first, or retry with visible_only=${!visibleOnly} after a text open.")
        }
        if (document.needsRenderer) throw BrowserFailure("browser_needs_renderer",
            "This page needs the renderer for readable text. Rivet will start a quiet visual session.")
        if (document.chunks.isEmpty()) throw BrowserFailure("browser_empty",
            "This page has no readable text.")
        if (chunk !in document.chunks.indices) {
            throw BrowserFailure("browser_chunk_out_of_range",
                "Chunk ${chunk} is outside 0..${document.chunks.lastIndex}.")
        }
        val selected = document.chunks[chunk]
        BrowserReadResult(document.url, document.title, chunk, document.chunks.size,
            selected.anchor, selected.text, chunk == document.chunks.lastIndex, elementRefs)
    }

    suspend fun find(text: String): List<ReaderHit> = actionLock.withLock {
        markChatBrowserWork()
        val document = if (renderer != null && activeUrl.isNotBlank()) {
            readRenderedDocument(visibleOnly = true)
        } else {
            readerCache.cached(visibleOnly = true) ?: readerCache.rawHtml()?.let { raw ->
                ReaderExtractor.extract(raw, activeUrl, true).also {
                    readerCache.record(readerCache.navigationToken, it, visibleOnly = true)
                }
            } ?: throw BrowserFailure("browser_not_open", "Open a page first.")
        }
        if (document.needsRenderer) throw BrowserFailure("browser_needs_renderer",
            "This page needs the renderer for searchable text. Rivet will start a quiet visual session.")
        ReaderExtractor.find(document.text, text)
    }

    /** Text read found a DOM-only page: start a quiet visual session, no card yet. */
    suspend fun escalateForRead() = actionLock.withLock {
        val view = ensureRenderer(showWatch = false)
        loadForInteraction(view, activeUrl)
    }

    /** The explicit in-browser Reader toggle; this is allowed to change screen state. */
    suspend fun showReader() = actionLock.withLock {
        if (activeUrl.isBlank()) throw BrowserFailure("browser_not_open", "Open a page first.")
        val cached = readerCache.cached()
        val document = if (cached != null && cached.url == activeUrl) cached
        else if (renderer != null && rendererFailure == null && !_state.value.teardownRequested) readRenderedDocument()
        else readerService.fetch(activeUrl).also {
            readerCache.record(readerCache.navigationToken, it)
        }
        val first = document.chunks.firstOrNull()
        _state.update {
            it.copy(readerMode = true, readerText = first?.text.orEmpty(),
                chunkIndex = 0, chunkCount = document.chunks.size,
                chunkAnchor = first?.anchor.orEmpty(), needsRenderer = document.needsRenderer,
                url = document.url, title = document.title)
        }
        cancelRendererRecovery()
        requestRendererTeardown()
    }

    suspend fun hideReader() {
        openVisual(activeUrl, userInitiated = true, restart = null, restoreSnapshot = true)
    }

    internal var paintJsEvaluator: suspend (WebView, String) -> String = { view, script ->
        evaluate(view, script)
    }
    internal var paintCapture: suspend (WebView) -> BrowserShot? = { view ->
        runCatching { captureScreenshot(view) }.getOrNull()
    }

    internal suspend fun samplePaint(sampledUrl: String? = null): PaintVerdict? {
        val view = renderer ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val dom = paintJsEvaluator(view, "document.body?document.body.innerText.length:0")
            .trim().trim('"').toIntOrNull() ?: 0
        val firstShot = paintCapture(view)
            ?: return PaintVerdict.Unknown
        val firstHash = runCatching { fileHash(firstShot.path) }.getOrNull()
        if (firstHash == null) {
            File(firstShot.path).delete()
            return PaintVerdict.Unknown
        }
        try {
            val startY = scrollY(view)
            var scrolledY = startY
            var secondHash = firstHash
            if (startY != null) {
                runCatching { paintJsEvaluator(view, "window.scrollBy(0,1)") }
                delay(80)
                scrolledY = scrollY(view) ?: startY
                try {
                    // Capture at the scrolled offset before restoring scrollY so healthy pages
                    // actually show shifted pixels instead of always matching firstShot.
                    val secondShot = paintCapture(view)
                    if (secondShot == null) return PaintVerdict.Unknown
                    secondHash = runCatching { fileHash(secondShot.path) }.getOrNull().orEmpty()
                    File(secondShot.path).delete()
                } finally {
                    val delta = scrolledY - startY
                    if (delta != 0 && renderer === view && !pageLoading) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        runCatching { paintJsEvaluator(view, "window.scrollBy(0,${-delta})") }
                    }
                }
            }
            val bitmap = android.graphics.BitmapFactory.decodeFile(firstShot.path) ?: return PaintVerdict.Unknown
            try {
                if (!sampledUrl.isNullOrBlank() &&
                    PaintStaleness.isNavigationStale(lastPaintedFrame, NavigationFrame(sampledUrl, firstHash))) {
                    return PaintVerdict.Stale
                }
                val scrolled = scrolledY
                if (startY != null && scrolled != null && scrolled != startY && secondHash.isNotEmpty() &&
                    PaintStaleness.isStale(FrameProbe(startY, firstHash),
                        FrameProbe(scrolled, secondHash))) return PaintVerdict.Stale
                val left = bitmap.width / 8
                val right = bitmap.width - left
                val top = bitmap.height / 4
                val bottom = bitmap.height - bitmap.height / 8
                val sampleStartX = left.coerceAtLeast(0)
                val sampleStartY = top.coerceAtLeast(0)
                val cols = if (right > sampleStartX) (right - sampleStartX + 7) / 8 else 0
                val rows = if (bottom > sampleStartY) (bottom - sampleStartY + 7) / 8 else 0
                val pixels = IntArray(cols * rows)
                var total = 0
                var y = sampleStartY
                while (y < bottom) {
                    var x = sampleStartX
                    while (x < right) {
                        pixels[total++] = bitmap.getPixel(x, y)
                        x += 8
                    }
                    y += 8
                }
                val sampledPixels = if (total == pixels.size) pixels else pixels.copyOf(total)
                val verdict = PaintVerdict.classifyPixels(dom, sampledPixels)
                if (verdict == PaintVerdict.Painted && !sampledUrl.isNullOrBlank()) {
                    lastPaintedFrame = NavigationFrame(sampledUrl, firstHash)
                }
                return verdict
            } finally {
                bitmap.recycle()
            }
        } finally {
            File(firstShot.path).delete()
        }
    }

    private suspend fun scrollY(view: WebView): Int? =
        runCatching { BrowserJsParsers.parseScrollYValue(paintJsEvaluator(view, "window.scrollY")) }.getOrNull()

    private fun fileHash(path: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        File(path).inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun canCheckPaint(view: WebView, navigation: Long, url: String): Boolean {
        val current = _state.value
        return renderer === view && navigationGeneration == navigation && !pageLoading &&
            current.visible && current.pageReady && !current.readerMode && !current.teardownRequested &&
            current.url == url
    }

    private fun armPaintWatchdog(view: WebView, url: String) {
        if (url.isBlank() || url == "about:blank") return
        val navigation = navigationGeneration
        if (!canCheckPaint(view, navigation, url)) return
        paintWatchdogJob?.cancel()
        paintWatchdogJob = scope.launch {
            delay(1_500)
            if (!canCheckPaint(view, navigation, url)) return@launch
            val verdict = withTimeoutOrNull(3_000) { samplePaint(url) } ?: return@launch
            if (!canCheckPaint(view, navigation, url)) return@launch
            if (verdict == PaintVerdict.BlankWithDom || verdict == PaintVerdict.Stale) {
                if (!queueRendererRestart(view, url, PAINT_WATCHDOG_ERROR)) {
                    while (canCheckPaint(view, navigation, url) && _state.value.error == PAINT_WATCHDOG_ERROR) {
                        delay(2_000)
                        if (!canCheckPaint(view, navigation, url)) break
                        val next = withTimeoutOrNull(3_000) { samplePaint(url) } ?: continue
                        if (canCheckPaint(view, navigation, url) && shouldClearPaintError(_state.value, url, next)) {
                            _state.update { it.copy(error = null, errorRetry = false) }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun queueRendererRestart(view: WebView, url: String, message: String): Boolean {
        val current = _state.value
        if (view !== renderer || current.teardownRequested || current.readerMode) return false
        if (pendingRestart != null && pendingRestart?.generation == rendererGeneration) return true
        if (url.isBlank() || url == "about:blank" || lastRecoveryUrl == url ||
            rendererRecoveryJob?.isActive == true ||
            (!current.visible && !current.watchVisible && !current.agentRendererUse)) {
            _state.update { it.copy(error = message, errorRetry = url.isNotBlank()) }
            return false
        }
        lastRecoveryUrl = url
        pendingRestart = RendererRestart(rendererGeneration, url, activeWindowId,
            activeWindowId?.let { windows[it] })
        requestRendererTeardown()
        return true
    }

    fun showError(failure: BrowserFailure, retry: Boolean = false) {
        val copy = browserNoticeCopy(failure.code, failure.message)
        _state.update { it.copy(error = copy.text, errorRetry = retry || copy.retry) }
    }

    suspend fun reloadFromError() {
        val target = _state.value.url.ifBlank { activeUrl }
        if (target.isBlank() || target == "about:blank") return
        cancelRendererRecovery()
        try {
            openVisual(target, userInitiated = true, restart = null, forceLoad = true)
        } catch (failure: BrowserFailure) {
            showError(failure, retry = true)
        }
    }

    private fun cancelRendererRecovery() {
        pendingRestart = null
        rendererRecoveryJob?.cancel()
        rendererRecoveryJob = null
    }

    private data class RendererRestart(
        val generation: Long,
        val url: String,
        val windowId: String?,
        val snapshot: BrowserSnapshot?,
    )

    private fun renderedHtmlScript(visibleOnly: Boolean): String {
        if (!visibleOnly || !_state.value.allowJs) return "document.documentElement.outerHTML"
        return """
            (function(){
              var marked=[];
              try{
                var nodes=document.body?document.body.querySelectorAll('*'):[];
                for(var i=0;i<nodes.length;i++){
                  var el=nodes[i]; var cs=getComputedStyle(el);
                  if(cs.display==='none'||cs.visibility==='hidden'||cs.visibility==='collapse'||
                    parseFloat(cs.opacity)===0||el.hasAttribute('hidden')||
                    el.getAttribute('aria-hidden')==='true'){
                    el.setAttribute('data-rivet-hidden','1'); marked.push(el);
                  }
                }
                return document.documentElement.outerHTML;
              } finally {
                for(var j=0;j<marked.length;j++) marked[j].removeAttribute('data-rivet-hidden');
              }
            })()
        """.trimIndent()
    }

    private suspend fun readRenderedDocument(visibleOnly: Boolean = true): ReaderDocument {
        val token = readerCache.navigationToken
        val view = renderer ?: throw BrowserFailure("browser_renderer_unavailable",
            "Open the visual browser so the page renderer can start.")
        val pageUrl = view.url ?: activeUrl
        val rawHtml = evaluate(view, renderedHtmlScript(visibleOnly))
        val html = runCatching { org.json.JSONTokener(rawHtml).nextValue() as? String }
            .getOrNull().orEmpty()
        val document = if (html.length in 80..2_000_000) {
            runCatching { ReaderExtractor.extract(html, pageUrl, visibleOnly) }
                .getOrNull()?.takeIf { it.text.isNotBlank() }?.copy(needsRenderer = false)
        } else null
        val fallbackText = if (document == null) {
            val raw = evaluate(view, if (visibleOnly) "document.body.innerText" else "document.body.textContent")
            runCatching { org.json.JSONTokener(raw).nextValue() as? String }
                .getOrNull()?.trim().orEmpty()
        } else ""
        val result = document ?: ReaderDocument(pageUrl, view.title.orEmpty().ifBlank { pageUrl },
            fallbackText, ReaderExtractor.chunk(fallbackText), needsRenderer = false)
        elementRefs = runCatching {
            val raw = evaluate(view, """
                JSON.stringify([].slice.call(document.querySelectorAll(
                  'a,button,input,[role=button]')).slice(0,40).map(function(e){
                  return {label:(e.innerText||e.value||e.getAttribute('aria-label')||'').trim(),tag:(e.tagName||'').toLowerCase()};
                }))
            """.trimIndent())
            BrowserElementRefs.fromJson(raw)
        }.getOrDefault(emptyList())
        if (result.text.isBlank()) throw BrowserFailure("browser_empty",
            "This page has no readable text. If the page is still loading, retry browser_read after it finishes.")
        if (!readerCache.record(token, result, visibleOnly)) {
            throw BrowserFailure("browser_page_loading",
                "The page changed while Rivet was reading it. Retry browser_read after the page finishes loading.")
        }
        reader = result
        recordVisit(pageUrl, publish = false)
        return result
    }

    fun currentUrl(): String = _state.value.url
    fun changeLine(beforeUrl: String, beforeTitle: String): String {
        val state = _state.value
        val url = if (state.url != beforeUrl) "URL ${state.url}" else "URL unchanged"
        val title = if (state.title != beforeTitle && state.title.isNotBlank()) "title ${state.title}" else ""
        return listOf(url, title).filter { it.isNotBlank() }.joinToString(" · ")
    }

    suspend fun classifyTap(x: Int, y: Int): BrowserClickTarget = actionLock.withLock {
        requireNoPageModal()
        val view = ensureRenderer()
        loadForInteraction(view, activeUrl)
        val px = BrowserCoordinates.pixel(x, view.width)
        val py = BrowserCoordinates.pixel(y, view.height)
        parseClickTarget(evaluate(view, tapTargetScript(px, py)))
    }

    suspend fun tap(x: Int, y: Int): String = actionLock.withLock {
        requireNoPageModal()
        val view = ensureRenderer()
        loadForInteraction(view, activeUrl)
        val px = BrowserCoordinates.pixel(x, view.width)
        val py = BrowserCoordinates.pixel(y, view.height)
        dispatchTouch(view, px, py)
        recordVisit(view.url ?: activeUrl)
        _state.update { it.copy(title = view.title.orEmpty(), url = view.url ?: activeUrl,
            mode = BrowserMode.Renderer, rendererVisible = true) }
        evaluate(view, "JSON.stringify({url:location.href,title:document.title})")
    }

    suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int): String = actionLock.withLock {
        requireNoPageModal()
        val view = ensureRenderer()
        loadForInteraction(view, activeUrl)
        val x1 = BrowserCoordinates.pixel(fromX, view.width)
        val y1 = BrowserCoordinates.pixel(fromY, view.height)
        val x2 = BrowserCoordinates.pixel(toX, view.width)
        val y2 = BrowserCoordinates.pixel(toY, view.height)
        dispatchSwipe(view, x1, y1, x2, y2)
        recordVisit(view.url ?: activeUrl)
        evaluate(view, "JSON.stringify({url:location.href,title:document.title,y:scrollY})")
    }

    private suspend fun dispatchTouch(view: WebView, x: Float, y: Float) {
        withContext(Dispatchers.Main) {
            val t = android.os.SystemClock.uptimeMillis()
            android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                .also { view.dispatchTouchEvent(it); it.recycle() }
            android.view.MotionEvent.obtain(t, t + 40, android.view.MotionEvent.ACTION_UP, x, y, 0)
                .also { view.dispatchTouchEvent(it); it.recycle() }
        }
    }

    private suspend fun dispatchSwipe(view: WebView, x1: Float, y1: Float, x2: Float, y2: Float) {
        withContext(Dispatchers.Main) {
            val down = android.os.SystemClock.uptimeMillis()
            android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN,
                x1, y1, 0).also { view.dispatchTouchEvent(it); it.recycle() }
            for (step in 1..8) {
                val t = step / 8f
                android.view.MotionEvent.obtain(down, down + step * 12L,
                    android.view.MotionEvent.ACTION_MOVE,
                    x1 + (x2 - x1) * t, y1 + (y2 - y1) * t, 0)
                    .also { view.dispatchTouchEvent(it); it.recycle() }
            }
            android.view.MotionEvent.obtain(down, down + 120L, android.view.MotionEvent.ACTION_UP,
                x2, y2, 0).also { view.dispatchTouchEvent(it); it.recycle() }
        }
    }

    suspend fun classifyClick(selector: String?, text: String?): BrowserClickTarget =
        actionLock.withLock {
            requireNoPageModal()
            val view = ensureRenderer()
            loadForInteraction(view, activeUrl)
            val script = clickTargetScript(selector, text)
            val raw = evaluate(view, script)
            parseClickTarget(raw)
        }

    suspend fun click(selector: String?, text: String?): String = actionLock.withLock {
        requireNoPageModal()
        val view = ensureRenderer()
        loadForInteraction(view, activeUrl)
        val target = parseClickTarget(evaluate(view, clickTargetScript(selector, text)))
        if (!target.found) throw BrowserFailure("browser_target", "Rivet couldn't find that control.")
        val result = evaluate(view, clickScript(selector, text))
        recordVisit(view.url ?: activeUrl)
        _state.update { it.copy(title = view.title.orEmpty(), url = view.url ?: activeUrl,
            mode = BrowserMode.Renderer, rendererVisible = true) }
        result
    }

    suspend fun type(field: String, text: String): String = actionLock.withLock {
        requireNoPageModal()
        val view = ensureRenderer()
        loadForInteraction(view, activeUrl)
        val raw = evaluate(view, typeScript(field, text))
        if (raw.contains("\"error\":\"sensitive\"")) {
            throw BrowserFailure("browser_sensitive_field",
                BrowserFormSafety.refusal(BrowserFormField(name = field, type = "password"))
                    ?: "Rivet will not type into this field.")
        }
        if (raw.contains("\"error\":\"not_found\"")) {
            throw BrowserFailure("browser_target", "Rivet couldn't find that field.")
        }
        recordVisit(view.url ?: activeUrl)
        raw
    }

    suspend fun scroll(direction: String, amount: Int = 1): String = actionLock.withLock {
        requireNoPageModal()
        val view = ensureRenderer()
        loadForInteraction(view, activeUrl)
        val pages = amount.coerceIn(1, 5)
        val dy = if (direction.lowercase() == "up") -pages else pages
        val raw = evaluate(view, "window.scrollBy(0, $dy * window.innerHeight); JSON.stringify({y:window.scrollY})")
        _state.update { it.copy(mode = BrowserMode.Renderer, rendererVisible = true) }
        raw
    }

    suspend fun back(): String = actionLock.withLock {
        val view = ensureRenderer()
        _state.update { it.copy(readerMode = false) }
        if (view.canGoBack()) {
            val ready = CompletableDeferred<Unit>()
            pageFinished = ready
            withContext(Dispatchers.Main) { navigate(view) { goBack() } }
            withTimeoutOrNull(10_000) { ready.await() }
            activeUrl = view.url ?: activeUrl
        } else {
            val windowId = activeWindowId
            val current = windowId?.let { windows[it] }
            val target = current?.backTarget()
            if (windowId == null || current == null || target == null) {
                throw BrowserFailure("browser_no_back", "There is no previous page in this window.")
            }
            windows[windowId] = current.copy(historyIndex = target.historyIndex,
                lastUsedAt = System.currentTimeMillis(), fidelity = BrowserFidelity.Reloaded)
            activeUrl = target.url
            val ready = CompletableDeferred<Unit>()
            pageFinished = ready
            withContext(Dispatchers.Main) { navigate(view) { loadUrl(target.url) } }
            withTimeoutOrNull(10_000) { ready.await() }
            activeUrl = view.url ?: target.url
            _state.update {
                it.copy(url = activeUrl, mode = BrowserMode.Renderer,
                    rendererVisible = true, fidelity = BrowserFidelity.Reloaded)
            }
        }
        activeUrl
    }

    /** Thin chrome action: forward through the WebView's own history. */
    suspend fun forward(): String = actionLock.withLock {
        val view = ensureRenderer()
        loadForInteraction(view, activeUrl)
        val moved = withContext(Dispatchers.Main) {
            if (view.canGoForward()) {
                navigate(view) { goForward() }
                true
            } else false
        }
        if (!moved) throw BrowserFailure("browser_no_forward", "There is no forward page in this window.")
        activeUrl
    }

    /** Thin chrome action: reload the live page without changing its history. */
    suspend fun reload(): String = actionLock.withLock {
        val view = ensureRenderer()
        loadForInteraction(view, activeUrl)
        _state.update { it.copy(pageReady = false) }
        withContext(Dispatchers.Main) { navigate(view) { reload() } }
        activeUrl
    }

    suspend fun screenshot(): BrowserShot = actionLock.withLock {
        if (!BrowserGovernor.canTakeScreenshot(budget.tier)) {
            throw BrowserFailure("browser_screenshot_blocked",
                "Screenshots are paused in the ${budget.tier.name.lowercase()} tier.")
        }
        val view = ensureRenderer()
        loadForInteraction(view, activeUrl)
        val shot = withContext(Dispatchers.Main) { captureScreenshot(view) }
        _state.update { it.copy(visualScreenshot = shot.path, watchVisible = true) }
        shot
    }

    /** Attaches the sheet-hosted WebView. Called on the main thread. */
    fun attach(view: WebView) {
        if (renderer === view) return
        if (BrowserAttachPolicy.onAttach(renderer != null) == BrowserAttachAction.ReleaseFieldOnly) {
            android.util.Log.w("BrowserHost",
                "Replacing a renderer field; the old view's onRelease owns cleanup")
        }
        val previousTeardown = teardown.prepareAttach()
        teardownWatchdog?.cancel()
        teardownWatchdog = null
        if (previousTeardown != RendererTeardown.State.Live &&
            previousTeardown != RendererTeardown.State.Destroyed) {
            android.util.Log.w("BrowserHost",
                "New renderer attach superseded pending teardown state=$previousTeardown")
            _state.update { it.copy(teardownRequested = false) }
        }
        rendererFailure = null
        resolvePageModal()
        releasedRenderer = null
        renderer = view
        rendererGeneration++
        recordRenderProcess(view)
        baselineAvailMem = availableMemBytes() ?: 0L
        baselineJob?.cancel()
        baselineJob = scope.launch(Dispatchers.Default) {
            baselineOwnPss = runCatching { memoryProbe.sample().ownPssBytes }.getOrDefault(0L)
        }
        view.setLayerType(android.view.View.LAYER_TYPE_NONE, null)
        configure(view)
        view.webChromeClient = chromeClientFor(view)
        view.webViewClient = clientFor(view)
        view.setDownloadListener { url, _, contentDisposition, mime, contentLength ->
            onDownloadRequested(url, contentDisposition, mime, contentLength)
        }
        _state.update { it.copy(rendererVisible = true) }
        lastRendererStart = System.currentTimeMillis()
        lastActivityAt = System.currentTimeMillis()
        periodic?.cancel()
        periodic = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(if (_state.value.visible) 3_000L else 5_000L)
                sampleAndRefreshBudget()
                if (_state.value.visible) lastActivityAt = System.currentTimeMillis()
            }
        }
        rendererReady?.complete(view)
        rendererReady = null
        val pendingLoad = pendingRendererLoad
        pendingRendererLoad = null
        if (pendingLoad != null) {
            navigate(view) { loadUrl(pendingLoad) }
            view.requestLayout()
            view.invalidate()
        }
    }

    internal suspend fun sampleAndRefreshBudget() {
        baselineJob?.join()
        val sample = withContext(Dispatchers.Default) { memoryProbe.sample() }
        val ownGrowth = if (baselineOwnPss > 0L) {
            memoryProbe.browserGrowthBytes(baselineOwnPss, sample)
        } else {
            sample.rendererPssBytes ?: 0L
        }
        withContext(Dispatchers.Main.immediate) {
            refreshBudget(ownGrowthBytes = ownGrowth)
        }
    }

    private fun schedulePostLoadBudgetChecks() {
        postLoadBudgetJob?.cancel()
        postLoadBudgetJob = scope.launch {
            // Sample immediately on page finish, then at 1.5s and 3.0s so heavy decode/JS
            // growth surfaces within the ~5s bar rather than waiting for a 5s poll tick.
            sampleAndRefreshBudget()
            delay(1_500L)
            sampleAndRefreshBudget()
            delay(1_500L)
            sampleAndRefreshBudget()
        }
    }

    fun detach(view: WebView?) {
        if (view != null && renderer !== view) return
        requestRendererTeardown()
    }

    fun setAllowJs(enabled: Boolean) {
        prefs.edit().putBoolean("allow_js", enabled).apply()
        _state.update { it.copy(allowJs = enabled) }
        renderer?.settings?.javaScriptEnabled = enabled
    }

    fun setBlockThirdParty(enabled: Boolean) {
        prefs.edit().putBoolean("block_third_party", enabled).apply()
        _state.update { it.copy(blockThirdParty = enabled) }
        renderer?.let { CookieManager.getInstance().setAcceptThirdPartyCookies(it, !enabled) }
    }

    fun setDesktopMode(enabled: Boolean) {
        prefs.edit().putBoolean("desktop", enabled).apply()
        _state.update { it.copy(desktopMode = enabled) }
        renderer?.settings?.useWideViewPort = enabled
        renderer?.settings?.loadWithOverviewMode = enabled
        renderer?.settings?.userAgentString =
            if (enabled) BrowserDesktopMode.DESKTOP_USER_AGENT else null
        renderer?.let { view -> navigate(view) { reload() } }
    }

    fun setFullPage(enabled: Boolean) {
        _state.update { it.copy(fullPage = enabled) }
        renderer?.let { view -> navigate(view) { reload() } }
    }

    fun console(level: String, sinceId: Long, limit: Int, clear: Boolean): BrowserConsoleSnapshot {
        val live = _state.value.url.ifBlank { activeUrl }
        if (BrowserAuthGuard.isAuthSurface(live) || _state.value.handoff != null) {
            throw BrowserFailure("browser_handoff",
                "Identity work moved to the phone's browser. Continue there, then share the page back.")
        }
        return BrowserConsoleSnapshot(
            result = consoleBuffer.query(level, sinceId, limit, clear),
            rendererAlive = renderer != null && !_state.value.teardownRequested,
            url = live,
        )
    }

    /**
     * A dialog is held open until reset (or an explicit resolution) clears it. Returning
     * true tells WebView that this client owns the dialog; never cancel it here, because
     * that would silently dismiss the page's alert before the agent can report the block.
     */
    private fun beginPageModal(kind: BrowserModalKind, message: String, result: JsResult) {
        val state = BrowserModalState(kind,
            message.trim().replace(Regex("\\s+"), " ").take(200))
        pendingModal = PendingModal(state, result)
        _state.update { it.copy(modal = state) }
    }

    /** Clears the modal state and resolves the held JsResult. Reset calls this on the way out. */
    internal fun resolvePageModal(confirm: Boolean = false, promptValue: String? = null) {
        val pending = pendingModal
        pendingModal = null
        _state.update { it.copy(modal = null) }
        if (pending == null) return
        runCatching {
            val result = pending.result
            when {
                result is JsPromptResult -> if (confirm) result.confirm(promptValue.orEmpty()) else result.cancel()
                confirm -> result.confirm()
                else -> result.cancel()
            }
        }
    }

    private fun pageModalFailure(): BrowserFailure? {
        val modal = _state.value.modal ?: return null
        val detail = modal.message.trim().replace(Regex("\\s+"), " ").take(160)
            .let { if (it.isEmpty()) "" else " (\"$it\")" }
        return BrowserFailure("page_modal_blocked",
            "page_modal_blocked: a JavaScript ${modal.kind.name.lowercase()} dialog$detail is blocking the page. " +
                "Call browser_reset(reopen=true) to clear it; key, click, type, scroll, swipe, and eval actions are refused while it is open.")
    }

    private fun requireNoPageModal() {
        pageModalFailure()?.let { throw it }
    }

    private data class PendingModal(val state: BrowserModalState, val result: JsResult)

    private fun chromeClientFor(view: WebView): WebChromeClient = object : WebChromeClient() {
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            beginPageModal(BrowserModalKind.Alert, message.orEmpty(), result)
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            beginPageModal(BrowserModalKind.Confirm, message.orEmpty(), result)
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?,
            defaultValue: String?, result: JsPromptResult): Boolean {
            beginPageModal(BrowserModalKind.Prompt, message.orEmpty(), result)
            return true
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val level = when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> BrowserConsoleLevel.Error
                ConsoleMessage.MessageLevel.WARNING -> BrowserConsoleLevel.Warn
                ConsoleMessage.MessageLevel.LOG -> BrowserConsoleLevel.Log
                ConsoleMessage.MessageLevel.TIP -> BrowserConsoleLevel.Info
                ConsoleMessage.MessageLevel.DEBUG -> BrowserConsoleLevel.Debug
                else -> BrowserConsoleLevel.Log
            }
            consoleBuffer.add(level, message.sourceId().orEmpty(), message.lineNumber(),
                message.message().orEmpty(), view.url.orEmpty())
            return false
        }
    }

    private fun installConsoleErrorHook(view: WebView) {
        if (!_state.value.allowJs) return
        view.evaluateJavascript("""
            (function(){
              if(!window.__rivetErrHook){
                window.__rivetErrHook=1;
                window.addEventListener('unhandledrejection',function(e){
                  console.error('UnhandledRejection: '+(e&&e.reason?(e.reason.stack||e.reason.message||String(e.reason)):'unknown'));
                });
              }
            })()
        """.trimIndent(), null)
    }

    /** Saves the old project, drops transient browser state, and loads the new project. */
    fun bindProject(key: String) {
        val nextKey = key.ifBlank { "unbound" }
        if (nextKey == projectKey) return
        // Keep the old project's tab even if the live snapshot callback cannot finish
        // before the renderer is torn down and windows are swapped.
        val oldId = activeWindowId
        oldId?.let { id -> windows[id]?.let { snapshot ->
            snapshots.save(snapshot.copy(title = activeTitle,
                lastUsedAt = System.currentTimeMillis()))
        } }
        saveActiveSnapshot()
        cancelRendererRecovery()
        requestRendererTeardown()
        consoleBuffer.clear()
        readerCache.invalidate()
        reader = null
        elementRefs = emptyList()
        trail.clear()
        pendingSnapshotRestore = null
        rotationScroll.clear()
        lastPaintedFrame = null
        lastRecoveryUrl = null
        pageLoading = false
        activeUrl = ""
        activeTitle = ""
        activeWindowId = null
        windows.clear()
        projectKey = nextKey
        shotStore = BrowserShotStore(app.filesDir, nextKey)
        snapshots = snapshotStoreFor(nextKey)
        snapshots.loadAll().forEach { windows[it.id] = it }
        _state.update {
            it.copy(activeWindowId = null, windows = windowSummaries(), domains = emptyList(),
                url = "", title = "", readerText = "", readerMode = false,
                chunkIndex = 0, chunkCount = 0, chunkAnchor = "",
                rendererVisible = false, pageReady = false, visualScreenshot = null,
                watchVisible = false, chatPage = null, chatBrowserWorking = false,
                handoff = null, authNotice = null)
        }
    }

    private fun snapshotStoreFor(key: String): BrowserSnapshotStore {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return BrowserSnapshotStore(File(app.filesDir, "browser-snapshots/${digest.take(24)}"))
    }

    fun clearBrowserData() {
        dismissChatPreview()
        resolvePageModal()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        renderer?.clearCache(true)
        renderer?.clearHistory()
        snapshots.clear()
        shotStore.cleanSession()
        shotStore.directory.deleteRecursively()
        trail.clear()
        consoleBuffer.clear()
        readerCache.navigate()
        windows.clear()
        activeWindowId = null
        activeUrl = ""
        activeTitle = ""
        reader = null
        pendingSnapshotRestore = null
        elementRefs = emptyList()
        _state.update {
            it.copy(
                activeWindowId = null,
                windows = emptyList(),
                domains = emptyList(),
                url = "",
                title = "",
                readerText = "",
                chunkIndex = 0,
                chunkCount = 0,
                chunkAnchor = "",
                pageReady = false,
                visualScreenshot = null,
                downloadPrompt = null,
                notice = "Browser data cleared.",
            )
        }
    }

    /** Reader-first gets one interaction step, then the renderer is destroyed. */
    fun afterInteraction() {
        if (budget.tier == BrowserTier.ReaderFirst) requestRendererTeardown()
    }

    fun takeControl() {
        if (!_state.value.agentRunActive || !_state.value.agentVisualActive) return
        lastActivityAt = System.currentTimeMillis()
        userControlAt = lastActivityAt
        _state.update { it.copy(driving = BrowserDriving.User) }
        releaseJob?.cancel()
        releaseJob = scope.launch {
            kotlinx.coroutines.delay(BrowserDrivingPolicy.RELEASE_CONTROL_AFTER_MS)
            if (_state.value.driving == BrowserDriving.User) handBack()
        }
    }

    fun setDriving(userDriving: Boolean) {
        if (userDriving) takeControl() else handBack()
    }

    fun handBack() {
        releaseJob?.cancel()
        releaseJob = null
        _state.update { it.copy(driving = BrowserDriving.Agent, notice = "Rivet can act again.") }
    }

    fun continueInBrowser() {
        val target = _state.value.handoff?.url ?: _state.value.url.takeIf { it.isNotBlank() } ?: return
        openExternal(target)
    }

    fun openExternal(url: String) {
        if (customTabOpener?.invoke(url) == true) return
        runCatching {
            app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun confirmDownload() {
        val prompt = _state.value.downloadPrompt ?: return
        enqueueDownload(prompt)
        _state.update { it.copy(downloadPrompt = null) }
    }

    fun dismissDownload() {
        _state.update { it.copy(downloadPrompt = null) }
    }

    fun selectWindow(id: String) {
        if (id == activeWindowId) return
        val target = windows[id] ?: snapshots.load(id) ?: return
        cancelRendererRecovery()
        lastRecoveryUrl = null
        windows[id] = target
        saveActiveSnapshot()
        activeWindowId = id
        activeUrl = target.currentUrl.orEmpty()
        activeTitle = target.title
        pendingSnapshotRestore = null
        refreshBudget()
        _state.update { it.copy(activeWindowId = id, url = activeUrl, title = activeTitle,
            fidelity = BrowserFidelity.Reloaded, readerMode = false, pageReady = false,
            windows = windowSummaries()) }
        val view = renderer
        if (activeUrl.isNotBlank() && activeUrl != "about:blank") {
            if (view != null && !_state.value.teardownRequested) {
                pendingSnapshotRestore = id to target
                navigate(view) { loadUrl(activeUrl) }
            } else {
                userOpen(activeUrl, restore = true)
            }
        }
    }

    fun newWindow() {
        cancelRendererRecovery()
        lastRecoveryUrl = null
        pendingSnapshotRestore = null
        activeUrl = ""
        activeTitle = ""
        val id = createWindow("about:blank")
        activeWindowId = id
        _state.update { it.copy(activeWindowId = id, windows = windowSummaries(), url = "", title = "",
            readerMode = false, readerText = "", pageReady = false) }
    }

    fun closeWindow(id: String) {
        val wasActive = id == activeWindowId
        val target = BrowserTabs.closeTarget(
            windowSummaries().map {
                BrowserWindowInfo(it.id, it.title, it.url, it.active)
            },
            activeWindowId,
            id,
        )
        if (wasActive) {
            cancelRendererRecovery()
            lastRecoveryUrl = null
        }
        windows.remove(id)
        snapshots.delete(id)
        if (wasActive) {
            activeWindowId = target.activeId
            activeUrl = target.url
            activeTitle = target.title
            _state.update {
                it.copy(
                    activeWindowId = activeWindowId,
                    url = activeUrl,
                    title = activeTitle,
                    readerMode = target.readerMode,
                    readerText = target.readerText,
                    pageReady = target.pageReady,
                    windows = windowSummaries(),
                )
            }
            val next = activeWindowId?.let { windows[it] }
            val view = renderer
            if (view != null && !_state.value.teardownRequested) {
                if (activeUrl.isNotBlank()) {
                    // Defer snapshot restoration to onPageFinished so closeWindow does not
                    // evaluate against the outgoing page or race the surviving tab's paint.
                    pendingSnapshotRestore = activeWindowId?.let { nextId -> next?.let { nextId to it } }
                    navigate(view) { loadUrl(activeUrl) }
                    view.requestLayout()
                    view.invalidate()
                } else {
                    pendingSnapshotRestore = null
                    navigate(view) { loadUrl("about:blank") }
                }
            } else if (activeUrl.isNotBlank() && activeUrl != "about:blank") {
                pendingSnapshotRestore = activeWindowId?.let { nextId -> next?.let { nextId to it } }
                if (_state.value.teardownRequested || releasedRenderer != null) {
                    // The old renderer is gone but the host is about to attach its
                    // replacement. Loading here would build a second hidden WebView.
                    pendingRendererLoad = activeUrl
                } else {
                    userOpen(activeUrl, restore = true)
                }
            }
        } else {
            _state.update { it.copy(windows = windowSummaries()) }
        }
    }

    suspend fun bookmark(): String {
        val writer = bookmarkWriter ?: throw BrowserFailure("browser_no_project", "Open a project to save a link.")
        val path = writer(activeTitle, activeUrl)
            ?: throw BrowserFailure("browser_no_project", "Open a project to save a link.")
        _state.update { it.copy(notice = "Saved link to $path.") }
        return path
    }

    override fun onTrimMemory(level: Int) {
        refreshBudget()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            renderer?.clearCache(true)
            val active = activeWindowId
            windows.keys.filter { it != active }.forEach { windows.remove(it) }
            _state.update { it.copy(windows = windowSummaries()) }
            if (budget.tier == BrowserTier.ReaderFirst) requestRendererTeardown()
        }
    }

    override fun onLowMemory() { onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE) }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        val view = renderer ?: return
        view.evaluateJavascript("window.scrollY") { raw ->
            val value = raw.trim().trim('"').toIntOrNull() ?: 0
            if (value > 0) rotationScroll.capture(value)
        }
    }

    private fun stateHandoff(url: String, reason: String) {
        val first = !authLimitStated
        authLimitStated = true
        _state.update {
            it.copy(visible = true, handoff = BrowserHandoffState(url, reason),
                authNotice = if (first) "Signing in your browser does not sign in Rivet's browser." else it.authNotice,
                error = null, notice = if (first) {
                    "Signing in your browser does not sign in Rivet's browser. Share the page back to continue."
                } else it.notice)
        }
    }

    private fun createWindow(url: String, publish: Boolean = true): String {
        val id = UUID.randomUUID().toString()
        val capped = BrowserSnapshotCodec.capWindows(windows.values.toList(), activeWindowId)
        if (capped.size >= BrowserSnapshotCodec.MAX_WINDOWS) {
            capped.filter { it.id != activeWindowId }.minByOrNull { it.lastUsedAt }?.let {
                windows.remove(it.id); snapshots.delete(it.id)
            }
        }
        windows[id] = BrowserSnapshot(id, historyUrls = listOf(url), historyIndex = 0)
        activeWindowId = id
        if (publish) _state.update { it.copy(activeWindowId = id, windows = windowSummaries()) }
        return id
    }

    private fun updateActiveWindow(url: String, title: String, publish: Boolean = true) {
        val id = activeWindowId ?: createWindow(url, publish)
        val current = windows[id] ?: BrowserSnapshot(id)
        val history = if (current.currentUrl == url) current.historyUrls
        else (current.historyUrls.take(current.historyIndex + 1) + url).takeLast(50)
        val updated = current.copy(title = title, historyUrls = history,
            historyIndex = history.lastIndex.coerceAtLeast(0), lastUsedAt = System.currentTimeMillis(),
            fidelity = BrowserFidelity.Full)
        windows[id] = updated
        snapshots.save(updated)
        if (publish) _state.update { it.copy(windows = windowSummaries()) }
    }

    private fun saveActiveSnapshot() {
        val targetStore = snapshots
        val id = activeWindowId ?: return
        val current = windows[id] ?: return
        val titleSnapshot = activeTitle
        val urlSnapshot = activeUrl
        val view = renderer
        if (view != null && rendererFailure == null && !_state.value.teardownRequested) {
            view.evaluateJavascript(snapshotScript()) { raw ->
                if (!windows.containsKey(id)) return@evaluateJavascript
                val latest = windows[id] ?: current
                val hasDomSnapshot = !raw.isNullOrBlank() && raw != "null" && raw != "undefined"
                val saved = latest.copy(
                    title = titleSnapshot,
                    lastUsedAt = System.currentTimeMillis(),
                    scrollY = if (hasDomSnapshot) parseScrollY(raw) else latest.scrollY,
                    formFields = if (hasDomSnapshot) parseSafeFields(raw) else latest.formFields,
                )
                windows[id] = saved
                targetStore.save(saved)
            }
            captureThumbnail(view, id)
        } else {
            // currentUrl is derived from historyUrls[historyIndex], not a stored
            // field, so record a changed URL by appending to the history.
            val newUrl = urlSnapshot.ifBlank { current.currentUrl.orEmpty() }
            val urlChanged = newUrl.isNotBlank() && current.currentUrl != newUrl
            val history = if (urlChanged) {
                (current.historyUrls.take(current.historyIndex + 1) + newUrl).takeLast(50)
            } else {
                current.historyUrls
            }
            val saved = current.copy(
                title = titleSnapshot,
                historyUrls = history,
                historyIndex = if (urlChanged) history.lastIndex.coerceAtLeast(0) else current.historyIndex,
                lastUsedAt = System.currentTimeMillis(),
            )
            windows[id] = saved
            targetStore.save(saved)
        }
    }

    private fun captureThumbnail(view: WebView, id: String) {
        scope.launch {
            val shot = runCatching { captureScreenshot(view) }.getOrNull() ?: return@launch
            val original = android.graphics.BitmapFactory.decodeFile(shot.path) ?: return@launch
            val thumb = try {
                val targetWidth = 320.coerceAtMost(original.width)
                val targetHeight = (original.height.toFloat() * targetWidth / original.width)
                    .toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)
            } finally { original.recycle() }
            try {
                val file = File(app.filesDir, "browser-snapshots/$id.webp")
                FileOutputStream(file).use { bitmap -> thumb.compress(Bitmap.CompressFormat.WEBP, 70, bitmap) }
                File(shot.path).delete()
                val snapshot = windows[id] ?: return@launch
                windows[id] = snapshot.copy(thumbnailFile = file.absolutePath)
                snapshots.save(snapshot.copy(thumbnailFile = file.absolutePath))
                _state.update { it.copy(windows = windowSummaries()) }
            } finally { thumb.recycle() }
        }
    }

    private fun snapshotScript(): String =
        "(function(){var f=[];[].forEach.call(document.querySelectorAll('input,textarea,select')," +
            "function(e){if(!e.name||e.type==='password'||e.type==='hidden')return;" +
            "f.push({name:e.name,type:e.type||'text',autocomplete:e.getAttribute('autocomplete')||''," +
            "value:String(e.value||'').slice(0,2000)});});" +
            "return JSON.stringify({scrollY:window.scrollY,fields:f});})()"

    private fun parseScrollY(raw: String): Int =
        BrowserJsParsers.parseSnapshotScrollY(raw)

    private fun parseSafeFields(raw: String): Map<String, String> {
        val clean = BrowserElementRefs.unwrapJsResult(raw)
        if (clean.isEmpty()) return emptyMap()
        val json = runCatching { org.json.JSONObject(clean) }.getOrNull() ?: return emptyMap()
        val array = json.optJSONArray("fields") ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val field = BrowserFormField(item.optString("name"), item.optString("type", "text"),
                item.optString("autocomplete"), "")
            if (!BrowserFormSafety.isSensitive(field)) result[field.name] = item.optString("value")
        }
        return result
    }

    private fun windowSummaries(): List<BrowserWindowSummary> {
        tabIcons.keys.retainAll(windows.keys)
        return windows.values.map {
            val url = it.currentUrl.orEmpty()
            val icon = tabIcons[it.id]?.takeIf { (loaded, _) -> loaded == url }?.second
            BrowserWindowSummary(it.id, it.title.ifBlank { it.currentUrl ?: "Untitled" },
                url, it.fidelity, it.id == activeWindowId, it.thumbnailFile, icon)
        }
    }

    private fun recordVisit(url: String, preview: Boolean = false, publish: Boolean = true) {
        if (url.isBlank() || url == "about:blank") return
        trail.record(url, preview)
        if (publish) _state.update { it.copy(domains = trail.visits) }
    }

    private fun configure(view: WebView) {
        val s = view.settings
        s.javaScriptEnabled = _state.value.allowJs
        s.domStorageEnabled = true
        s.useWideViewPort = _state.value.desktopMode
        s.loadWithOverviewMode = _state.value.desktopMode
        s.userAgentString = if (_state.value.desktopMode) BrowserDesktopMode.DESKTOP_USER_AGENT else null
        s.mediaPlaybackRequiresUserGesture = true
        s.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        s.textZoom = (app.resources.configuration.fontScale * 100).toInt().coerceIn(50, 200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.safeBrowsingEnabled = true
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, !_state.value.blockThirdParty)
    }

    private fun beginNavigation(view: WebView) {
        if (view !== renderer || _state.value.teardownRequested) return
        navigationGeneration++
        paintWatchdogJob?.cancel()
        paintWatchdogJob = null
        pageLoading = true
        pendingMainFrameError = null
        _state.update { it.copy(pageReady = false) }
    }

    private fun navigate(view: WebView, action: WebView.() -> Unit) {
        if (view !== renderer || _state.value.teardownRequested) return
        beginNavigation(view)
        view.action()
    }

    private fun clientFor(view: WebView): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            if (view !== renderer || _state.value.teardownRequested) return true
            if (BrowserAuthGuard.isAuthSurface(target)) {
                stateHandoff(target, "identity")
                return true
            }
            if (target.startsWith("http://") || target.startsWith("https://")) return false
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (view !== renderer || _state.value.teardownRequested) return
            recordRenderProcess(view)
            resolvePageModal()
            beginNavigation(view)
            installConsoleErrorHook(view)
            if (url != null && BrowserAuthGuard.isAuthSurface(url)) {
                stateHandoff(url, "identity")
                view.stopLoading()
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (view !== renderer || _state.value.teardownRequested) return
            if (url != null && view.url != null && url != view.url) return
            recordRenderProcess(view)
            pageLoading = false
            installConsoleErrorHook(view)
            installNetworkHook(view)
            val actual = url ?: view.url ?: activeUrl
            activeUrl = actual
            readerCache.navigate()
            if (actual == "about:blank" && activeWindowId == null) {
                activeTitle = ""
                pageFinished?.complete(Unit)
                pageFinished = null
                _state.update { it.copy(url = "", title = "", pageReady = false) }
                return
            }
            activeTitle = view.title.orEmpty()
            if (lastMainHttpStatus == null) lastMainHttpStatus = 200
            recordVisit(actual)
            updateActiveWindow(actual, activeTitle, publish = false)
            activeWindowId?.let { id ->
                val favicon = view.favicon
                if (favicon != null && !favicon.isRecycled && actual != "about:blank") {
                    tabIcons[id] = actual to android.graphics.Bitmap.createScaledBitmap(favicon, 32, 32, true)
                } else tabIcons.remove(id)
            }
            val finished = pageFinished
            pageFinished = null
            _state.update { it.copy(url = actual, title = activeTitle,
                chatPage = it.chatPage?.let { page ->
                    if (page.url == it.url && actual != "about:blank") {
                        if (actual == page.url) BrowserChatPage(actual, activeTitle.ifBlank { page.title }, page.excerpt)
                        else BrowserChatPage(actual, activeTitle)
                    } else page
                },
                mode = BrowserMode.Renderer, rendererVisible = true, pageReady = true,
                fidelity = BrowserFidelity.Reloaded, windows = windowSummaries(), activeWindowId = activeWindowId) }
            refreshBudget()
            schedulePostLoadBudgetChecks()
            val pending = pendingSnapshotRestore?.takeIf { (windowId, _) -> windowId == activeWindowId }?.second
            pendingSnapshotRestore = null
            if (pending != null) {
                loadSnapshotState(view, pending)
            } else {
                val windowScroll = activeWindowId?.let { windows[it]?.scrollY } ?: 0
                if (windowScroll > 0 || rotationScroll.target > 0) {
                    restoreScroll(view, maxOf(windowScroll, rotationScroll.target))
                }
            }
            if (pendingMainFrameError == null) armPaintWatchdog(view, actual)
            finished?.complete(Unit)
            if (view === renderer && !pageLoading && !BrowserAuthGuard.isLoopback(actual)) {
                view.evaluateJavascript("document.querySelector('input[type=password]') !== null") { raw ->
                    if (view === renderer && !pageLoading && activeUrl == actual &&
                        raw.trim('"') == "true") stateHandoff(actual, "sign-in-wall")
                }
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Never intercept or replace main-frame document navigations under any diet tier.
            if (request.isForMainFrame) return null
            val rules = BrowserDiet.rulesFor(budget.tier, _state.value.fullPage)
            val url = request.url.toString()
            val tracker = BrowserDiet.isTrackerHost(request.url.host.orEmpty())
            val kind = BrowserDiet.classifyResource(
                url = url,
                isForMainFrame = request.isForMainFrame,
                acceptHeader = request.requestHeaders["Accept"],
                secFetchDest = request.requestHeaders["Sec-Fetch-Dest"],
            )
            val declared = request.requestHeaders["Content-Length"]?.toLongOrNull() ?: 0L
            if (BrowserDiet.shouldBlock(url, kind, declared, rules, isForMainFrame = request.isForMainFrame)) {
                if (kind == BrowserResourceKind.Image && !tracker) {
                    return WebResourceResponse("image/png", "binary",
                        ByteArrayInputStream(BrowserDiet.placeholderPng()))
                }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: android.webkit.WebResourceError,
        ) {
            if (view !== renderer || _state.value.teardownRequested) return
            recordRenderProcess(view)
            val description = error.description?.toString().orEmpty()
            if (request.isForMainFrame) {
                pageLoading = false
                pendingMainFrameError = if (description.contains("CLEARTEXT", ignoreCase = true)) {
                    BrowserFailure("browser_cleartext_blocked",
                        "Cleartext http:// was blocked for ${request.url}. Use https://, or pass preview_root/preview_entry for local files.")
                } else {
                    BrowserFailure("browser_load_failed",
                        "Failed to load ${request.url}: $description. Check the URL, network, or server process.")
                }
            }
            consoleBuffer.add(BrowserConsoleLevel.NetworkError, "webview", 0,
                "$description ${request.url}", request.url.toString())
            super.onReceivedError(view, request, error)
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (view !== renderer || _state.value.teardownRequested) return
            recordRenderProcess(view)
            if (request.isForMainFrame) lastMainHttpStatus = errorResponse.statusCode
            if (errorResponse.statusCode >= 400) {
                consoleBuffer.add(BrowserConsoleLevel.NetworkError, "http", 0,
                    "HTTP ${errorResponse.statusCode} ${request.url}", request.url.toString())
            }
            super.onReceivedHttpError(view, request, errorResponse)
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
            processRegistry.onProcessGoneForView(view)
            if (view === pendingGoneView) {
                completeGoneRenderer(view)
                return true
            }
            if (view !== renderer || view in releasedViews) return true
            if (!_state.value.teardownRequested) {
                val message = "The page renderer stopped."
                rendererFailure = message
                pageFinished?.completeExceptionally(BrowserFailure("browser_renderer_unavailable", message))
                pageFinished = null
                if (!queueRendererRestart(view, activeUrl, message)) requestRendererTeardown()
            } else {
                rendererFailure = "The page renderer stopped."
            }
            return true
        }
    }

    private suspend fun awaitRendererTeardown() {
        if (!_state.value.teardownRequested) return
        val released = withTimeoutOrNull(5_000) {
            state.first { !it.teardownRequested }
        }
        if (released == null) throw BrowserFailure("browser_teardown_pending",
            "The browser is still resetting. Try again in a moment.")
    }

    private suspend fun ensureRenderer(showWatch: Boolean = true,
                                       allowUserDriving: Boolean = false,
                                       agentAction: Boolean = true): WebView {
        refreshBudget()
        if (!BrowserGovernor.canOpenRenderer(budget.tier, interaction = true)) {
            throw BrowserFailure("browser_renderer_blocked", BrowserGovernor.refusalReason(budget))
        }
        if (!allowUserDriving && _state.value.driving == BrowserDriving.User) {
            withTimeoutOrNull(2_000) {
                while (_state.value.driving == BrowserDriving.User) kotlinx.coroutines.delay(200)
            }
            if (_state.value.driving == BrowserDriving.User) {
                throw BrowserFailure("browser_user_driving",
                    "The user is driving the browser. Ask them to hand it back.")
            }
        }
        if (_state.value.handoff != null) {
            throw BrowserFailure("browser_handoff",
                "Identity work moved to the phone's browser. Read-only pages still work; interactive agent actions are paused.")
        }
        idleJob?.cancel()
        idleJob = null
        idleArming.onActivity()
        lastActivityAt = System.currentTimeMillis()
        awaitRendererTeardown()
        if (agentAction) markChatBrowserWork()
        _state.update { it.copy(rendererVisible = true,
            watchVisible = it.watchVisible || showWatch || (agentAction && it.agentRunActive),
            agentRendererUse = it.agentRendererUse || agentAction) }
        val existing = renderer
        if (existing != null) return existing
        val ready = CompletableDeferred<WebView>()
        rendererReady = ready
        val attached = withTimeoutOrNull(8_000) { ready.await() }
        if (attached == null) {
            throw BrowserFailure("browser_renderer_unavailable",
                "The visual browser could not start. Try opening it again.")
        }
        awaitLaidOut(attached)
        return attached
    }

    private suspend fun loadForInteraction(view: WebView, url: String, forceLoad: Boolean = false) {
        if (_state.value.readerMode) _state.update { it.copy(readerMode = false) }
        if (activeUrl.isBlank()) throw BrowserFailure("browser_not_open", "Open a page first.")
        val samePage = view.url == url
        if (samePage && !pageLoading && !forceLoad) return
        val ready = CompletableDeferred<Unit>()
        pageFinished = ready
        try {
            withContext(Dispatchers.Main) {
                navigate(view) { if (samePage && forceLoad) reload() else loadUrl(url) }
            }
            withTimeout(30_000) { ready.await() }
        } catch (_: TimeoutCancellationException) {
            throw BrowserFailure("browser_timeout", "The page took too long to load.")
        } finally {
            if (pageFinished === ready) pageFinished = null
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String = withContext(Dispatchers.Main) {
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value -> continuation.resumeWith(Result.success(value ?: "")) }
        }
    }

    private fun parseClickTarget(raw: String): BrowserClickTarget =
        BrowserJsParsers.parseClickTarget(raw)

    private fun tapTargetScript(x: Float, y: Float): String = """
        (function(){
          var el=document.elementFromPoint($x,$y);
          if(!el) return JSON.stringify({found:false,risky:false,description:''});
          var form=el.closest('form'); var tag=(el.tagName||'').toLowerCase();
          var type=(el.getAttribute('type')||'').toLowerCase();
          var label=(el.innerText||el.value||el.getAttribute('aria-label')||'').trim();
          var risky=type==='submit'||(form&&(el.type==='submit'||/submit|send|publish|pay|buy|post|delete|confirm/i.test(label)));
          return JSON.stringify({found:true,risky:!!risky,inForm:!!form,tag:tag,description:label||tag});
        })()
    """.trimIndent()

    private fun clickTargetScript(selector: String?, text: String?): String = """
        (function(){
          var sel=${jsonString(selector)};
          var txt=${jsonString(text)};
          var ref=sel?/^#(\d+)$/.exec(sel):null;
          var el=ref?[].slice.call(document.querySelectorAll('a,button,input,[role=button]'))[parseInt(ref[1],10)-1]||null:(sel?document.querySelector(sel):null);
          if(!el && txt){var all=[].slice.call(document.querySelectorAll('a,button,input,[role=button]'));
            el=all.filter(function(e){return (e.innerText||e.value||'').toLowerCase().indexOf(txt.toLowerCase())>=0})[0]||null;}
          if(!el) return JSON.stringify({found:false,risky:false,description:''});
          var form=el.closest('form'); var tag=(el.tagName||'').toLowerCase();
          var type=(el.getAttribute('type')||'').toLowerCase();
          var label=(el.innerText||el.value||el.getAttribute('aria-label')||'').trim();
          var risky=type==='submit'||(form&&(el.type==='submit'||/submit|send|publish|pay|buy|post|delete|confirm/i.test(label)));
          return JSON.stringify({found:true,risky:!!risky,inForm:!!form,tag:tag,description:label||tag});
        })()
    """.trimIndent()

    private fun clickScript(selector: String?, text: String?): String = clickTargetScript(selector, text)
        .replace("return JSON.stringify({found:true,risky:!!risky,inForm:!!form,tag:tag,description:label||tag});",
            "el.click(); return JSON.stringify({clicked:true,description:label||tag});")

    private fun typeScript(field: String, value: String): String = """
        (function(){
          var name=${jsonString(field)};
          var el=null;
          try { el=document.querySelector(name); } catch(e) { el=null; }
          if(!el) {
            var inputs=[].slice.call(document.querySelectorAll('input,textarea,select'));
            el=inputs.filter(function(e){return e.name===name||e.id===name;})[0]||null;
          }
          if(!el) return JSON.stringify({error:'not_found'});
          var type=(el.getAttribute('type')||el.type||'text').toLowerCase();
          var ac=(el.getAttribute('autocomplete')||'').toLowerCase();
          var hint=(el.getAttribute('name')||'')+' '+(el.getAttribute('placeholder')||'')+' '+
            (el.getAttribute('aria-label')||'')+' '+ac;
          if(type==='password'||type==='hidden'||/one-time-code|cc-|current-password|new-password/.test(ac)||
            /pass(word|cod)|otp|one.?time|card|cvv|cvc|security.?code/i.test(hint))
            return JSON.stringify({error:'sensitive'});
          el.focus(); el.value=${jsonString(value)};
          el.dispatchEvent(new Event('input',{bubbles:true}));
          el.dispatchEvent(new Event('change',{bubbles:true}));
          return JSON.stringify({typed:true});
        })()
    """.trimIndent()

    private fun jsonString(value: String?): String =
        if (value == null) "null" else org.json.JSONObject.quote(value)

    /** A small, read-only frame of the same WebView owned by BrowserHost. */
    internal fun chatPreviewFrame(): Bitmap? {
        val state = _state.value
        if (state.chatPage?.url != state.url || state.teardownRequested || !state.pageReady ||
            state.handoff != null || state.error != null || !BrowserGovernor.canTakeScreenshot(state.tier)) return null
        val view = renderer ?: return null
        if (view.width == 0 || view.height == 0) return null
        val scale = minOf(960f / view.width, 1f)
        val height = minOf(view.height.toFloat(), view.width * 9f / 16f)
        val bitmap = Bitmap.createBitmap((view.width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            view.draw(canvas)
            bitmap
        } catch (_: RuntimeException) {
            bitmap.recycle()
            null
        }
    }

    private suspend fun captureScreenshot(view: WebView): BrowserShot = withContext(Dispatchers.Main) {
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val location = IntArray(2).also { view.getLocationInWindow(it) }
            val activity = findActivity(view.context)
            val ok = if (_state.value.visible && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                val done = CompletableDeferred<Boolean>()
                PixelCopy.request(activity.window,
                    android.graphics.Rect(location[0], location[1],
                        location[0] + width, location[1] + height), bitmap,
                    { result -> done.complete(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()))
                withTimeoutOrNull(5_000) { done.await() } == true
            } else false
            if (!ok) Canvas(bitmap).also { canvas -> view.draw(canvas) }
            val file = shotStore.newShot(System.currentTimeMillis())
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.WEBP, 80, it) }
            BrowserShot(file.absolutePath, width, height)
        } finally { bitmap.recycle() }
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is android.content.ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }

    private fun restoreScroll(view: WebView, target: Int) {
        if (target <= 0) return
        if (rotationScroll.target != target) rotationScroll.capture(target)
        applyScrollRestore(view)
    }

    private fun applyScrollRestore(view: WebView) {
        val offset = rotationScroll.attempt(view.contentHeight)
        if (offset != null) {
            view.scrollTo(0, offset)
            rotationScroll.clear()
            scrollLayoutListener?.let {
                view.removeOnLayoutChangeListener(it)
                scrollLayoutListener = null
            }
            return
        }
        if (scrollLayoutListener == null) {
            lateinit var listener: android.view.View.OnLayoutChangeListener
            listener = android.view.View.OnLayoutChangeListener {
                    changed, _, _, _, _, _, _, _, _ ->
                if (view.contentHeight >= rotationScroll.target) {
                    val landed = rotationScroll.attempt(view.contentHeight)
                    if (landed != null) {
                        changed.scrollTo(0, landed)
                        rotationScroll.clear()
                    }
                    changed.removeOnLayoutChangeListener(listener)
                    if (scrollLayoutListener === listener) scrollLayoutListener = null
                }
            }
            scrollLayoutListener = listener
            view.addOnLayoutChangeListener(listener)
            view.post { applyScrollRestore(view) }
        }
    }

    private fun loadSnapshotState(view: WebView, snapshot: BrowserSnapshot) {
        view.post {
            restoreScroll(view, snapshot.scrollY)
            // Form restoration is deliberately name/value only; sensitive fields were
            // filtered out when the snapshot was made.
            val fields = snapshot.formFields
            if (fields.isNotEmpty()) {
                val script = "(function(){var f=${org.json.JSONObject(fields)};" +
                    "var els=document.querySelectorAll('input,textarea,select');" +
                    "for(var k in f){if(!Object.prototype.hasOwnProperty.call(f,k))continue;" +
                    "for(var i=0;i<els.length;i++){var e=els[i];" +
                    "if(e.name===k){var t=(e.type||'').toLowerCase();" +
                    "if(t!=='password'&&t!=='hidden'&&t!=='file'){e.value=f[k];}break;}}}})()"
                view.evaluateJavascript(script, null)
            }
        }
    }

    /**
     * The official WebView teardown order, plus the feature-gated renderer terminate.
     * The handle must be captured while the WebView is alive: no WebView method may
     * be called after destroy(), and terminate-after-destroy is undocumented.
     */
    private fun recordRenderProcess(view: WebView) {
        runCatching { renderProcessHandleProvider(view) }.getOrNull()?.let { (process, terminate) ->
            processRegistry.record(process, ownerView = view, terminate = terminate)
        }
    }

    fun releaseRenderer(view: WebView): Throwable? {
        if (!releasedViews.add(view)) return null
        releasingView = view
        return try {
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            if (view === renderer && rendererFailure == null) {
                recordRenderProcess(view)
                runCatching { view.stopLoading() }
                pendingGoneView = view
                val terminated = processRegistry.terminateForView(view, rendererTerminateSupported())
                if (pendingGoneView === view) {
                    if (terminated > 0) {
                        pendingGoneJob = scope.launch {
                            delay(2_000)
                            if (pendingGoneView === view) completeGoneRenderer(view)
                        }
                    } else {
                        pendingGoneView = null
                        view.destroy()
                    }
                }
            } else {
                view.destroy()
            }
            null
        } catch (error: Throwable) {
            if (pendingGoneView === view) pendingGoneView = null
            error
        } finally {
            processRegistry.onProcessGoneForView(view)
            releasingView = null
        }
    }

    private fun completeGoneRenderer(view: WebView) {
        pendingGoneJob?.cancel()
        pendingGoneJob = null
        pendingGoneView = null
        val failure = runCatching { view.destroy() }.exceptionOrNull()
        if (view !== releasingView && view === renderer) completeRendererRelease(failure)
    }

    fun requestRendererTeardown() {
        if (_state.value.teardownRequested) return
        resolvePageModal()
        periodic?.cancel()
        periodic = null
        postLoadBudgetJob?.cancel()
        postLoadBudgetJob = null
        paintWatchdogJob?.cancel()
        paintWatchdogJob = null
        if (rendererFailure == null && !pageLoading) saveActiveSnapshot()
        pageLoading = false
        pageFinished?.completeExceptionally(
            BrowserFailure("browser_renderer_unavailable", "The browser view closed before the page loaded."))
        pageFinished = null
        if (renderer == null) {
            finishRendererTeardown(null)
            return
        }
        if (!teardown.requestDetach()) teardown.forceRequestDetach()
        _state.update { it.copy(teardownRequested = true, pageReady = false) }
        armTeardownWatchdog()
    }

    private fun armHangValve() {
        if (hangArmed) return
        hangArmed = true
        hangValve.reset()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!hangArmed) return
                if (hangValve.onFrame(SystemClock.uptimeMillis())) {
                    hangArmed = false
                    hangCallback = null
                    android.util.Log.w("BrowserPaint",
                        "loopback hang valve tripped; stopping the renderer")
                    _state.update {
                        it.copy(loadValveNotice = LOAD_VALVE_NOTICE,
                            notice = LOAD_VALVE_NOTICE,
                            error = LOAD_VALVE_NOTICE)
                    }
                    requestRendererTeardown()
                    return
                }
                if (hangArmed) Choreographer.getInstance().postFrameCallback(this)
            }
        }
        hangCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun disarmHangValve() {
        hangArmed = false
        hangCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        hangCallback = null
        hangValve.reset()
    }

    private fun armTeardownWatchdog() {
        teardownWatchdog?.cancel()
        val held = renderer ?: return
        teardownWatchdog = scope.launch {
            delay(2_000)
            if (renderer === held && _state.value.teardownRequested && pendingGoneView !== held) {
                onRendererReleased(held) { releaseRenderer(held) }
            }
        }
    }

    fun onRendererReleased(view: WebView, cleanup: () -> Throwable?) {
        if (releasedViews.contains(view)) return
        if (view !== renderer) {
            cleanup()?.let { android.util.Log.w("BrowserHost", "Stale renderer cleanup failed", it) }
            return
        }
        if (teardown.state == RendererTeardown.State.Live) teardown.requestDetach()
        val released = teardown.release()
        val failure = cleanup()
        if (pendingGoneView !== view) {
            completeRendererRelease(failure ?: if (released) null
                else IllegalStateException("Renderer released out of order"))
        }
    }

    private fun completeRendererRelease(failure: Throwable?) {
        val destroyed = teardown.destroy()
        finishRendererTeardown(failure ?: if (destroyed) null
            else IllegalStateException("Renderer could not reach Destroyed"))
    }

    private fun finishRendererTeardown(failure: Throwable?) {
        teardownWatchdog?.cancel()
        teardownWatchdog = null
        scrollLayoutListener?.let { renderer?.removeOnLayoutChangeListener(it) }
        scrollLayoutListener = null
        renderer?.let { releasedRenderer = it }
        renderer = null
        pageLoading = false
        paintWatchdogJob?.cancel()
        paintWatchdogJob = null
        lastPaintedFrame = null
        baselineOwnPss = 0L
        baselineAvailMem = 0L
        lastOwnGrowthBytes = 0L
        observedBrowserBytes = 0L
        rendererReady?.cancel()
        rendererReady = null
        periodic?.cancel()
        periodic = null
        postLoadBudgetJob?.cancel()
        postLoadBudgetJob = null
        if (pendingRestart == null && rendererFailure != null &&
            _state.value.chatPage?.excerpt.isNullOrBlank()) dismissChatPreview()
        val restart = pendingRestart
        val message = if (restart != null && failure == null) null else
            rendererFailure ?: failure?.let { "The browser view could not be closed cleanly." }
        _state.update {
            val combined = listOfNotNull(it.error, message).distinct().joinToString(" ")
            it.copy(teardownRequested = false, rendererVisible = false, watchVisible = false,
                rendererHostGeneration = it.rendererHostGeneration + 1,
                pageReady = false, mode = BrowserMode.Reader, budgetCloseAction = false,
                agentRendererUse = false, error = combined.ifBlank { null },
                errorRetry = it.errorRetry || (message != null && activeUrl.isNotBlank()))
        }
        if (failure != null) android.util.Log.w("BrowserHost", "Renderer cleanup failed", failure)
        if (restart != null && failure == null) {
            rendererRecoveryJob = scope.launch(start = CoroutineStart.LAZY) {
                restartRenderer(restart)
            }
            rendererRecoveryJob?.start()
        } else {
            pendingRestart = null
        }
    }

    private suspend fun restartRenderer(restart: RendererRestart) {
        try {
            val result = openVisual(restart.url, userInitiated = false, agentAction = false, restart = restart)
            lastRecoveryUrl = result.url
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (activeWindowId == restart.windowId && activeUrl == restart.url &&
                rendererGeneration <= restart.generation + 1) {
                _state.update { it.copy(error = "The browser view could not restart.", errorRetry = true) }
            }
        }
    }

    private fun onDownloadRequested(url: String, disposition: String?, mime: String?, length: Long) {
        if (!BrowserDownloadPolicy.isSupportedDownloadUrl(url)) {
            _state.update { it.copy(error = "Only http:// and https:// downloads are supported.") }
            return
        }
        val name = BrowserDownloadPolicy.extractFileName(disposition, url)
        val size = length.coerceAtLeast(0)
        val metered = runCatching {
            val cm = app.getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }.getOrDefault(false)
        if (BrowserDownloadPolicy.asksFirst(size, metered)) {
            _state.update { it.copy(downloadPrompt = BrowserDownloadPrompt(url, name, size, true)) }
        } else {
            enqueueDownload(BrowserDownloadPrompt(url, name, size, metered))
        }
    }

    private fun enqueueDownload(prompt: BrowserDownloadPrompt) {
        if (!BrowserDownloadPolicy.isSupportedDownloadUrl(prompt.url)) {
            _state.update { it.copy(error = "Only http:// and https:// downloads are supported.") }
            return
        }
        val safeName = BrowserDownloadPolicy.sanitizeFileName(prompt.fileName)
        runCatching {
            val request = DownloadManager.Request(Uri.parse(prompt.url))
                .setTitle(safeName)
                .setDescription("Downloaded by Rivet's browser")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, safeName)
            (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            _state.update { it.copy(notice = "Downloading $safeName (${prompt.sizeBytes / 1024}KB).") }
        }.onFailure {
            _state.update { state -> state.copy(error = "The download could not start.") }
        }
    }

    fun settingsSnapshot(): Pair<Boolean, Boolean> = _state.value.allowJs to _state.value.blockThirdParty
}

data class BrowserShot(val path: String, val width: Int, val height: Int)
