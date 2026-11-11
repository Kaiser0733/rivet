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
import android.webkit.CookieManager
import android.view.PixelCopy
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.DownloadManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import com.kaiser.rivet.local.LocalModelManager
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

data class BrowserHandoffState(val url: String, val reason: String)

data class BrowserDownloadPrompt(
    val url: String,
    val fileName: String,
    val sizeBytes: Long,
    val metered: Boolean,
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
    val teardownRequested: Boolean = false,
    val pageReady: Boolean = false,
    val readerMode: Boolean = false,
    val watchVisible: Boolean = false,
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
    val agentVisualActive: Boolean = false,
    val handoff: BrowserHandoffState? = null,
    val authNotice: String? = null,
    val downloadPrompt: BrowserDownloadPrompt? = null,
    val notice: String? = null,
    val error: String? = null,
    val fullPage: Boolean = false,
    val allowJs: Boolean = true,
    val blockThirdParty: Boolean = true,
    val desktopMode: Boolean = false,
    val fidelity: BrowserFidelity? = null,
)

data class BrowserOpenResult(
    val url: String,
    val title: String,
    val chunks: Int,
    val needsRenderer: Boolean,
    val tier: BrowserTier,
    val capMb: Int,
    val handoff: Boolean = false,
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

data class BrowserClickTarget(
    val found: Boolean,
    val risky: Boolean,
    val description: String,
    val inForm: Boolean = false,
    val tag: String = "",
)

/**
 * Session-scoped browser state and agent backend. The WebView is owned by the sheet
 * host and attached here; the session never creates a second renderer.
 */
class BrowserSession(
    private val app: Application,
    private val models: LocalModelManager?,
) : ComponentCallbacks2 {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val actionLock = Mutex()
    private val prefs = app.getSharedPreferences("browser", Context.MODE_PRIVATE)
    private val readerService = ReaderService()
    private val trail = BrowserDomainTrail()
    private val snapshots = BrowserSnapshotStore(File(app.filesDir, "browser-snapshots"))
    private val windows = linkedMapOf<String, BrowserSnapshot>().apply {
        snapshots.loadAll().forEach { put(it.id, it) }
    }
    private var activeWindowId: String? = null
    private var reader: ReaderDocument? = null
    private var readerReloaded = false
    private var readerSampleJob: kotlinx.coroutines.Job? = null
    private val paintWatchdog = PaintWatchdog()
    private var paintWatchdogJob: kotlinx.coroutines.Job? = null
    private var pendingRestart: PendingPaintRestart? = null
    private var activeUrl: String = ""
    private var activeTitle: String = ""
    private var authLimitStated = false
    private var renderer: WebView? = null
    private var teardown = RendererTeardown()
    private var rendererFailure: String? = null
    private var rendererReady: CompletableDeferred<WebView>? = null
    private var pageFinished: CompletableDeferred<Unit>? = null
    private var budget = BrowserGovernor.decide(BrowserMemorySignals(0))
    private var lastRendererStart = 0L
    private var lastActivityAt = 0L
    private var userControlAt = 0L
    private val memoryProbe = BrowserMemoryProbe()
    private var baselineOwnPss = 0L
    private var observedBrowserBytes = 0L
    private var elementRefs: List<BrowserElementRef> = emptyList()
    private var idleJob: kotlinx.coroutines.Job? = null
    private val idleArming = BrowserIdleArming()
    private var releaseJob: kotlinx.coroutines.Job? = null
    private var periodic: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow(BrowserUiState(
        allowJs = prefs.getBoolean("allow_js", true),
        blockThirdParty = prefs.getBoolean("block_third_party", true),
        desktopMode = prefs.getBoolean("desktop", BrowserDesktopMode.DEFAULT_DESKTOP),
    ))
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    var bookmarkWriter: (suspend (String, String) -> String?)? = null
    var previewStarter: (suspend (String, String) -> String?)? = null
    var customTabOpener: ((String) -> Unit)? = null

    init {
        app.registerComponentCallbacks(this)
        refreshBudget()
        models?.loadedId?.let { loaded -> scope.launch { loaded.collect { refreshBudget() } } }
        models?.modelFootprintBytes?.let { footprint ->
            scope.launch { footprint.collect { refreshBudget() } }
        }
    }

    fun beginAgentRun() {
        _state.update { it.copy(agentRunActive = true, driving = BrowserDriving.Agent) }
    }

    fun endAgentRun() {
        releaseJob?.cancel()
        releaseJob = null
        _state.update { it.copy(agentRunActive = false, agentVisualActive = false,
            driving = BrowserDriving.Agent) }
    }

    /** Human open: always the full-screen visual browser, never the reader face. */
    fun userOpen() {
        idleJob?.cancel(); idleJob = null
        idleArming.onActivity()
        lastActivityAt = System.currentTimeMillis()
        _state.update { it.copy(visible = true, readerMode = false, error = null) }
        val target = activeUrl.ifBlank { _state.value.url }
        if (target.isNotBlank() && renderer == null) {
            scope.launch { runCatching { openVisual(target, userInitiated = true) } }
        }
    }

    fun show() = userOpen()

    /** Watch an existing AI visual session without reloading it. */
    fun watch() {
        idleJob?.cancel(); idleJob = null
        idleArming.onActivity()
        lastActivityAt = System.currentTimeMillis()
        if (renderer != null || _state.value.rendererVisible) {
            _state.update { it.copy(visible = true, readerMode = false) }
        } else userOpen()
    }

    /** Closing the screen keeps the visual session alive for the watch card, then idle-expires it. */
    fun close() {
        _state.update { it.copy(visible = false, readerMode = false) }
        idleArming.onHidden()
        armIdle()
    }

    /** MainActivity.onStop: the same three-minute clock, not a second timer. */
    fun onAppBackground() {
        if (idleArming.onBackground(renderer != null || _state.value.rendererVisible)) armIdle()
    }

    /** MainActivity.onStart: returning to the app disarms the background clock. */
    fun onAppForeground() {
        if (idleArming.onForeground()) {
            idleJob?.cancel()
            idleJob = null
        }
    }

    private fun armIdle() {
        idleJob?.cancel()
        idleJob = scope.launch {
            kotlinx.coroutines.delay(BrowserIdleTimer.IDLE_MS)
            if (!_state.value.visible) endSession()
        }
    }

    /** The visible Close control and browser_close: save, destroy, free, clear the card. */
    fun endSession() {
        releaseJob?.cancel()
        releaseJob = null
        idleJob?.cancel()
        idleJob = null
        readerSampleJob?.cancel()
        readerSampleJob = null
        idleArming.onActivity()
        val id = activeWindowId
        val current = id?.let { windows[it] }
        if (id != null && current != null) {
            snapshots.save(current.copy(title = activeTitle,
                lastUsedAt = System.currentTimeMillis()))
        }
        saveActiveSnapshot()
        _state.update {
            it.copy(visible = false, readerMode = false, agentVisualActive = false,
                visualScreenshot = null, pageReady = false, driving = BrowserDriving.Agent)
        }
        requestRendererTeardown()
    }

    fun clearNotice() { _state.update { it.copy(notice = null, error = null) } }

    fun contextLine(): String {
        refreshBudget()
        val line = BrowserGovernor.contextLine(budget)
        val modes = " Browser text reads stay invisible; visual page is for the user."
        return if (_state.value.authNotice != null) "$line$modes Identity sign-in stays in your browser."
        else "$line$modes"
    }

    fun refreshBudget(publish: Boolean = true, observedBytes: Long? = null) {
        if (observedBytes != null) observedBrowserBytes = observedBytes.coerceAtLeast(0L)
        val manager = app.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        val loaded = models?.loadedId?.value != null
        val footprint = if (loaded) models?.modelFootprintBytes?.value ?: 0L else 0L
        val previousTier = budget.tier
        budget = BrowserGovernor.decide(BrowserMemorySignals(info.availMem, footprint,
            observedBrowserBytes = observedBrowserBytes))
        if (publish) {
            if (budget.tier.ordinal > previousTier.ordinal) {
                if (budget.tier.ordinal >= BrowserTier.StrictDiet.ordinal) {
                    val wasFullPage = _state.value.fullPage
                    _state.update { it.copy(fullPage = false) }
                    if (wasFullPage) renderer?.reload()
                }
                if (budget.tier.ordinal >= BrowserTier.ReaderFirst.ordinal) unloadInactiveWindows()
                _state.update {
                    it.copy(notice = "Browser memory: ${budget.observedMb}MB used; " +
                        "${budget.tier.name.lowercase()} mode.")
                }
            }
            _state.update {
                it.copy(tier = budget.tier, capMb = budget.capMb, modelLoaded = budget.modelLoaded,
                    budgetLine = BrowserGovernor.contextLine(budget))
            }
        }
        if (publish && !BrowserGovernor.canOpenRenderer(budget.tier, interaction = true) &&
            _state.value.rendererVisible) {
            requestRendererTeardown()
            _state.update {
                it.copy(mode = BrowserMode.Reader,
                    notice = BrowserGovernor.refusalReason(budget))
            }
        }
    }

    private fun unloadInactiveWindows() {
        val active = activeWindowId
        val farthest = windows.filterKeys { it != active }
            .minByOrNull { it.value.lastUsedAt }?.key ?: return
        windows.remove(farthest)
        _state.update { it.copy(windows = windowSummaries()) }
    }

    suspend fun open(rawUrl: String, newWindow: Boolean = false, projectPreview: Boolean = false): BrowserOpenResult =
        openText(rawUrl, newWindow, projectPreview)

    /** Invisible AI text path: fetch and parse without touching screen state. */
    suspend fun openText(rawUrl: String, newWindow: Boolean = false, projectPreview: Boolean = false): BrowserOpenResult =
        actionLock.withLock {
            val url = BrowserUrlPolicy.validate(rawUrl)
            refreshBudget(publish = false)
            if (BrowserAuthGuard.isAuthSurface(url)) {
                stateHandoff(url, "identity")
                return@withLock BrowserOpenResult(url, "", 0, false, budget.tier, budget.capMb, handoff = true)
            }
            if (newWindow || activeWindowId == null) createWindow(url, publish = false)
            activeUrl = url
            try {
                val document = readerService.fetch(url)
                reader = document
                activeTitle = document.title
                recordVisit(url, projectPreview || BrowserAuthGuard.isLoopback(url), publish = false)
                updateActiveWindow(url, document.title, publish = false)
                BrowserOpenResult(url, document.title, document.chunks.size,
                    document.needsRenderer, budget.tier, budget.capMb)
            } catch (handoff: BrowserHandoff) {
                stateHandoff(handoff.url, handoff.reason)
                BrowserOpenResult(url, "", 0, false, budget.tier, budget.capMb, handoff = true)
            }
        }

    /** Full visual load for a human or an agent interaction. */
    suspend fun openVisual(rawUrl: String, userInitiated: Boolean = true,
                           newWindow: Boolean = false, projectPreview: Boolean = false,
                           agentAction: Boolean = false): BrowserOpenResult =
        actionLock.withLock {
            idleJob?.cancel(); idleJob = null
            idleArming.onActivity()
            val url = BrowserUrlPolicy.validate(rawUrl)
            refreshBudget(publish = true)
            if (BrowserAuthGuard.isAuthSurface(url)) {
                stateHandoff(url, "identity")
                return@withLock BrowserOpenResult(url, "", 0, false, budget.tier, budget.capMb, handoff = true)
            }
            if (newWindow || activeWindowId == null) createWindow(url, publish = userInitiated)
            activeUrl = url
            reader = null
            _state.update {
                it.copy(visible = it.visible || userInitiated, rendererVisible = true,
                    pageReady = false, watchVisible = true, visualScreenshot = null,
                    url = url, mode = BrowserMode.Renderer,
                    readerMode = false, error = null, notice = null, handoff = null, authNotice = null)
            }
            val view = ensureRenderer(showWatch = !userInitiated,
                allowUserDriving = userInitiated, agentAction = agentAction)
            loadForInteraction(view, url)
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
            BrowserOpenResult(activeUrl, activeTitle, 0, false, budget.tier, budget.capMb)
        }

    suspend fun read(chunk: Int = 0): BrowserReadResult = actionLock.withLock {
        val document = if (renderer?.url == activeUrl && activeUrl.isNotBlank() &&
            (reader?.url != activeUrl || reader?.chunks?.isEmpty() == true ||
                reader?.needsRenderer == true)) {
            readRenderedDocument()
        } else reader ?: throw BrowserFailure("browser_not_open", "Open a page first.")
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
        val document = if (renderer?.url == activeUrl && activeUrl.isNotBlank() &&
            (reader?.url != activeUrl || reader?.chunks?.isEmpty() == true ||
                reader?.needsRenderer == true)) {
            readRenderedDocument()
        } else reader ?: throw BrowserFailure("browser_not_open", "Open a page first.")
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
        val document = if (renderer?.url == activeUrl && activeUrl.isNotBlank()) readRenderedDocument()
        else reader?.takeIf { it.url == activeUrl } ?: readerService.fetch(activeUrl)
        val first = document.chunks.firstOrNull()
        parkRenderer(ReaderPark.Mode.ReaderOn)
        readerReloaded = false
        _state.update {
            it.copy(readerMode = true, readerText = first?.text.orEmpty(),
                chunkIndex = 0, chunkCount = document.chunks.size,
                chunkAnchor = first?.anchor.orEmpty(), needsRenderer = document.needsRenderer,
                url = document.url, title = document.title)
        }
    }

    fun hideReader() {
        _state.update { it.copy(readerMode = false) }
        parkRenderer(ReaderPark.Mode.ReaderOff)
        verifyReaderPaint()
    }

    private fun parkRenderer(mode: ReaderPark.Mode) {
        val view = renderer ?: return
        ReaderPark.apply(object : RendererSurface {
            override fun pause() { view.onPause() }
            override fun setVisible(visible: Boolean) {
                view.visibility = if (visible) android.view.View.VISIBLE else android.view.View.INVISIBLE
            }
            override fun resume() { view.onResume() }
        }, mode)
    }

    private fun verifyReaderPaint() {
        readerSampleJob?.cancel()
        readerSampleJob = scope.launch {
            kotlinx.coroutines.delay(900)
            if (_state.value.readerMode || readerReloaded) return@launch
            val verdict = samplePaint() ?: return@launch
            if (verdict == PaintVerdict.BlankWithDom) {
                readerReloaded = true
                reloadForReaderPaint()
            }
        }
    }

    private suspend fun samplePaint(): PaintVerdict? {
        val view = renderer ?: return null
        if (view.width <= 0 || view.height <= 0) return null
        val dom = evaluate(view, "document.body?document.body.innerText.length:0")
            .trim().trim('"').toIntOrNull() ?: 0
        val shot = runCatching { captureScreenshot(view) }.getOrNull() ?: return PaintVerdict.Unknown
        val bitmap = android.graphics.BitmapFactory.decodeFile(shot.path)
            ?: return PaintVerdict.Unknown
        return try {
            val left = bitmap.width / 8
            val right = bitmap.width - left
            val top = bitmap.height / 4
            val bottom = bitmap.height - bitmap.height / 8
            var total = 0
            var nearWhite = 0
            var y = top.coerceAtLeast(0)
            while (y < bottom) {
                var x = left.coerceAtLeast(0)
                while (x < right) {
                    val color = bitmap.getPixel(x, y)
                    val r = android.graphics.Color.red(color)
                    val g = android.graphics.Color.green(color)
                    val b = android.graphics.Color.blue(color)
                    if (r >= 245 && g >= 245 && b >= 245) nearWhite++
                    total++
                    x += 8
                }
                y += 8
            }
            val fraction = if (total == 0) 0f else nearWhite.toFloat() / total
            PaintVerdict.decide(dom, sampled = true, nearWhiteFraction = fraction)
        } finally {
            bitmap.recycle()
            java.io.File(shot.path).delete()
        }
    }

    private suspend fun reloadForReaderPaint() {
        val view = renderer ?: return
        val snapshot = activeWindowId?.let { windows[it] }
        _state.update { it.copy(pageReady = false, notice = "Page reloaded to restore the view.") }
        val ready = CompletableDeferred<Unit>()
        pageFinished = ready
        withContext(Dispatchers.Main) { view.loadUrl(activeUrl) }
        withTimeoutOrNull(10_000) { ready.await() }
        if (snapshot != null) loadSnapshotState(view, snapshot)
    }

    private fun armPaintWatchdog(url: String) {
        if (!_state.value.visible) return
        if (paintWatchdog.onPageFinished(url) != PaintWatchdog.Directive.Arm) return
        paintWatchdogJob?.cancel()
        paintWatchdogJob = scope.launch {
            kotlinx.coroutines.delay(1_500)
            if (!_state.value.visible || _state.value.url != url || !_state.value.pageReady) return@launch
            val verdict = samplePaint() ?: return@launch
            when (paintWatchdog.onSample(url, verdict)) {
                PaintWatchdog.Directive.Restart -> restartForPaintWedge(url)
                PaintWatchdog.Directive.Stop -> _state.update {
                    it.copy(error = "The page stopped rendering. Close the browser to recover.")
                }
                else -> Unit
            }
        }
    }

    private fun restartForPaintWedge(url: String) {
        if (pendingRestart != null) return
        saveActiveSnapshot()
        pendingRestart = PendingPaintRestart(url,
            activeWindowId?.let { windows[it] })
        _state.update { it.copy(notice = "The page stopped rendering — Rivet restarted the browser view.",
            error = null) }
        requestRendererTeardown()
    }

    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    private data class PendingPaintRestart(val url: String, val snapshot: BrowserSnapshot?)

    private suspend fun readRenderedDocument(): ReaderDocument {
        val view = renderer ?: throw BrowserFailure("browser_renderer_unavailable",
            "Open the visual browser so the page renderer can start.")
        val pageUrl = view.url ?: activeUrl
        val rawHtml = evaluate(view, "document.documentElement.outerHTML")
        val html = runCatching { org.json.JSONTokener(rawHtml).nextValue() as? String }
            .getOrNull().orEmpty()
        val document = if (html.length in 80..2_000_000) {
            runCatching { ReaderExtractor.extract(html, pageUrl) }
                .getOrNull()?.takeIf { it.text.isNotBlank() }?.copy(needsRenderer = false)
        } else null
        val fallbackText = if (document == null) {
            val raw = evaluate(view, "document.body.innerText")
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
            BrowserElementRefs.fromJson(raw.trim().trim('"').replace("\\\"", "\""))
        }.getOrDefault(emptyList())
        if (result.text.isBlank()) throw BrowserFailure("browser_empty", "This page has no readable text.")
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
        val view = ensureRenderer()
        loadForInteraction(view, activeUrl)
        val px = BrowserCoordinates.pixel(x, view.width)
        val py = BrowserCoordinates.pixel(y, view.height)
        parseClickTarget(evaluate(view, tapTargetScript(px, py)))
    }

    suspend fun tap(x: Int, y: Int): String = actionLock.withLock {
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
            val view = ensureRenderer()
            loadForInteraction(view, activeUrl)
            val script = clickTargetScript(selector, text)
            val raw = evaluate(view, script)
            parseClickTarget(raw)
        }

    suspend fun click(selector: String?, text: String?): String = actionLock.withLock {
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
        if (view.canGoBack()) {
            val ready = CompletableDeferred<Unit>()
            pageFinished = ready
            withContext(Dispatchers.Main) { view.goBack() }
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
            withContext(Dispatchers.Main) { view.loadUrl(target.url) }
            withTimeoutOrNull(10_000) { ready.await() }
            activeUrl = view.url ?: target.url
            _state.update {
                it.copy(url = activeUrl, mode = BrowserMode.Renderer,
                    rendererVisible = true, fidelity = BrowserFidelity.Reloaded)
            }
        }
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
        renderer?.let { old ->
            android.util.Log.w("BrowserHost", "Attaching a new renderer while an old one is still attached")
            runCatching { old.stopLoading(); old.destroy() }
        }
        teardown = RendererTeardown()
        rendererFailure = null
        renderer = view
        scope.launch(Dispatchers.Default) {
            baselineOwnPss = runCatching { memoryProbe.sample().ownPssBytes }.getOrDefault(0L)
        }
        view.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        configure(view)
        rendererReady?.complete(view)
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
                kotlinx.coroutines.delay(5_000)
                val sample = withContext(Dispatchers.Default) { memoryProbe.sample() }
                val observed = if (baselineOwnPss > 0L) {
                    memoryProbe.browserGrowthBytes(baselineOwnPss, sample)
                } else 0L
                refreshBudget(observedBytes = observed)
                if (_state.value.visible) lastActivityAt = System.currentTimeMillis()
            }
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
        renderer?.reload()
    }

    fun setFullPage(enabled: Boolean) {
        _state.update { it.copy(fullPage = enabled) }
        renderer?.reload()
    }

    fun clearBrowserData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        renderer?.clearCache(true)
        renderer?.clearHistory()
        app.cacheDir.resolve("browser-snapshots").deleteRecursively()
        snapshots.clear()
        _state.update { it.copy(notice = "Browser data cleared.") }
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
        if (customTabOpener != null) customTabOpener!!.invoke(url)
        else runCatching {
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
        windows[id] = target
        saveActiveSnapshot()
        activeWindowId = id
        activeUrl = target.currentUrl.orEmpty()
        activeTitle = target.title
        _state.update { it.copy(activeWindowId = id, url = activeUrl, title = activeTitle,
            fidelity = BrowserFidelity.Reloaded, windows = windowSummaries()) }
        val view = renderer
        if (view != null && activeUrl.isNotBlank()) {
            view.loadUrl(activeUrl)
            loadSnapshotState(view, target)
        }
    }

    fun newWindow() {
        val id = createWindow("about:blank")
        activeWindowId = id
        _state.update { it.copy(activeWindowId = id, windows = windowSummaries(), url = "", title = "") }
    }

    fun closeWindow(id: String) {
        windows.remove(id)
        snapshots.delete(id)
        if (activeWindowId == id) {
            activeWindowId = windows.keys.lastOrNull()
            _state.update { it.copy(activeWindowId = activeWindowId,
                url = windows[activeWindowId]?.currentUrl.orEmpty(),
                title = windows[activeWindowId]?.title.orEmpty(), windows = windowSummaries()) }
        } else _state.update { it.copy(windows = windowSummaries()) }
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
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { }

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
        val id = activeWindowId ?: return
        val current = windows[id] ?: return
        val view = renderer
        if (view != null) {
            view.evaluateJavascript(snapshotScript()) { raw ->
                val saved = current.copy(title = activeTitle, lastUsedAt = System.currentTimeMillis(),
                    scrollY = parseScrollY(raw), formFields = parseSafeFields(raw))
                windows[id] = saved
                snapshots.save(saved)
            }
            captureThumbnail(view, id)
        } else {
            val saved = current.copy(title = activeTitle, lastUsedAt = System.currentTimeMillis())
            windows[id] = saved
            snapshots.save(saved)
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
        Regex("\"scrollY\"\\s*:\\s*(\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun parseSafeFields(raw: String): Map<String, String> {
        val json = runCatching {
            org.json.JSONObject(raw.trim('"').replace("\\\"", "\""))
        }.getOrNull() ?: return emptyMap()
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

    private fun windowSummaries(): List<BrowserWindowSummary> = windows.values.map {
        BrowserWindowSummary(it.id, it.title.ifBlank { it.currentUrl ?: "Untitled" },
            it.currentUrl.orEmpty(), it.fidelity, it.id == activeWindowId, it.thumbnailFile)
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

    private fun clientFor(view: WebView): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val target = request.url.toString()
            if (BrowserAuthGuard.isAuthSurface(target)) {
                stateHandoff(target, "identity")
                return true
            }
            if (target.startsWith("http://") || target.startsWith("https://")) return false
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (url != null && BrowserAuthGuard.isAuthSurface(url)) {
                stateHandoff(url, "identity")
                view.stopLoading()
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val actual = url ?: view.url ?: activeUrl
            activeUrl = actual
            activeTitle = view.title.orEmpty()
            recordVisit(actual)
            updateActiveWindow(actual, activeTitle)
            pageFinished?.complete(Unit)
            pageFinished = null
            _state.update { it.copy(url = actual, title = activeTitle,
                mode = BrowserMode.Renderer, rendererVisible = true, pageReady = true,
                fidelity = BrowserFidelity.Reloaded) }
            armPaintWatchdog(actual)
            if (!BrowserAuthGuard.isLoopback(actual)) {
                view.evaluateJavascript("document.querySelector('input[type=password]') !== null") { raw ->
                    if (raw.trim('"') == "true") stateHandoff(actual, "sign-in-wall")
                }
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val rules = BrowserDiet.rulesFor(budget.tier, _state.value.fullPage)
            val url = request.url.toString()
            val tracker = BrowserDiet.isTrackerHost(request.url.host.orEmpty())
            val kind = when {
                request.requestHeaders["Accept"]?.contains("image", true) == true ||
                    url.matches(Regex("(?i).*\\.(png|jpe?g|gif|webp|svg|avif)(\\?.*)?$")) ->
                    BrowserResourceKind.Image
                url.matches(Regex("(?i).*\\.(woff2?|ttf|otf)(\\?.*)?$")) -> BrowserResourceKind.Font
                url.matches(Regex("(?i).*\\.(mp4|webm|m4s|mp3)(\\?.*)?$")) -> BrowserResourceKind.Media
                tracker -> BrowserResourceKind.Tracker
                else -> BrowserResourceKind.Other
            }
            val declared = request.requestHeaders["Content-Length"]?.toLongOrNull() ?: 0L
            if (BrowserDiet.shouldBlock(url, kind, declared, rules)) {
                if (kind == BrowserResourceKind.Image && !tracker) {
                    return WebResourceResponse("image/png", "binary",
                        ByteArrayInputStream(BrowserDiet.placeholderPng()))
                }
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
            rendererFailure = "The page renderer stopped. Reader mode and a reload are available."
            requestRendererTeardown()
            return true
        }
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
        lastActivityAt = System.currentTimeMillis()
        _state.update { it.copy(rendererVisible = true,
            watchVisible = it.watchVisible || showWatch,
            agentVisualActive = it.agentVisualActive || (agentAction && showWatch)) }
        val existing = renderer
        if (existing != null) return existing
        val ready = CompletableDeferred<WebView>()
        rendererReady = ready
        val attached = withTimeoutOrNull(8_000) { ready.await() }
        if (attached == null) {
            throw BrowserFailure("browser_renderer_unavailable",
                "The visual browser could not start. Try opening it again.")
        }
        return attached
    }

    private suspend fun loadForInteraction(view: WebView, url: String) {
        if (activeUrl.isBlank()) throw BrowserFailure("browser_not_open", "Open a page first.")
        if (view.url == activeUrl) return
        val ready = CompletableDeferred<Unit>()
        pageFinished = ready
        withContext(Dispatchers.Main) { view.loadUrl(activeUrl) }
        try { withTimeout(30_000) { ready.await() } }
        catch (_: TimeoutCancellationException) {
            throw BrowserFailure("browser_timeout", "The page took too long to load.")
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String = withContext(Dispatchers.Main) {
        kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            view.evaluateJavascript(script) { value -> continuation.resumeWith(Result.success(value ?: "")) }
        }
    }

    private fun parseClickTarget(raw: String): BrowserClickTarget {
        val clean = raw.trim().trim('"').replace("\\\"", "\"")
        val risky = Regex("\"risky\"\\s*:\\s*true").containsMatchIn(clean) ||
            Regex("(?i)submit|send|publish|pay|buy|post|delete|confirm").containsMatchIn(clean)
        val found = !clean.contains("\"found\":false")
        val description = Regex("\"description\"\\s*:\\s*\"([^\"]*)\"").find(clean)?.groupValues?.get(1)
            ?: "page control"
        return BrowserClickTarget(found, risky, description)
    }

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
          var el=document.querySelector(name)||document.querySelector('[name="'+name.replace(/"/g,'')+'"]');
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

    private suspend fun captureScreenshot(view: WebView): BrowserShot = withContext(Dispatchers.Main) {
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val location = IntArray(2).also { view.getLocationInWindow(it) }
            val activity = findActivity(view.context)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
                val done = CompletableDeferred<Boolean>()
                PixelCopy.request(activity.window,
                    android.graphics.Rect(location[0], location[1],
                        location[0] + width, location[1] + height), bitmap,
                    { result -> done.complete(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()))
                withTimeoutOrNull(5_000) { done.await() } == true
            } else false
            if (!ok) Canvas(bitmap).also { canvas -> view.draw(canvas) }
            val dir = File(app.filesDir, "browser-shots").apply { mkdirs() }
            val file = File(dir, "shot-${System.currentTimeMillis()}.webp")
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

    private fun loadSnapshotState(view: WebView, snapshot: BrowserSnapshot) {
        view.post {
            view.scrollTo(0, snapshot.scrollY)
            // Form restoration is deliberately name/value only; sensitive fields were
            // filtered out when the snapshot was made.
            val fields = snapshot.formFields
            if (fields.isNotEmpty()) {
                val script = "(function(){var f=${org.json.JSONObject(fields)};" +
                    "for(var k in f){var e=document.querySelector('[name=\"'+k+'\"]');" +
                    "if(e&&e.type!=='password')e.value=f[k];}})()"
                view.evaluateJavascript(script, null)
            }
        }
    }

    /**
     * Session side only marks the hand-off and clears what must not keep running.
     * The view is not touched here. BrowserHost removes it from composition, then
     * calls onRendererReleased with the actual cleanup lambda.
     */
    fun requestRendererTeardown() {
        periodic?.cancel()
        periodic = null
        saveActiveSnapshot()
        if (renderer == null) {
            finishRendererTeardown(null)
            return
        }
        if (teardown.requestDetach()) {
            _state.update { it.copy(teardownRequested = true, pageReady = false) }
        }
    }

    /**
     * Host side: composition has removed the view. This is the only place that may
     * destroy it. The sequencer is advanced Released -> Destroyed around cleanup.
     */
    fun onRendererReleased(view: WebView, cleanup: () -> Throwable?) {
        if (view !== renderer) {
            // A stale AndroidView must still not leak; clean it, but do not let it
            // clear the live session's state.
            val failure = cleanup()
            if (failure != null) android.util.Log.w("BrowserHost", "Stale renderer cleanup failed", failure)
            return
        }
        if (teardown.state == RendererTeardown.State.Live) teardown.requestDetach()
        val released = teardown.release()
        if (!released) {
            val failure = cleanup()
            finishRendererTeardown(failure ?: IllegalStateException("renderer released out of order"))
            return
        }
        val failure = cleanup()
        if (!teardown.destroy()) {
            finishRendererTeardown(IllegalStateException("renderer could not reach Destroyed"))
            return
        }
        finishRendererTeardown(failure)
    }

    private fun finishRendererTeardown(failure: Throwable?) {
        renderer = null
        readerSampleJob?.cancel()
        readerSampleJob = null
        paintWatchdogJob?.cancel()
        paintWatchdogJob = null
        baselineOwnPss = 0L
        observedBrowserBytes = 0L
        rendererReady?.cancel()
        rendererReady = null
        periodic?.cancel()
        periodic = null
        val message = rendererFailure ?: failure?.let { "The browser view could not be closed cleanly." }
        rendererFailure = null
        _state.update {
            it.copy(teardownRequested = false, rendererVisible = false, watchVisible = false,
                pageReady = false, mode = BrowserMode.Reader,
                error = listOfNotNull(it.error, message).distinct().joinToString(" ").ifBlank { null })
        }
        if (failure != null) android.util.Log.w("BrowserHost", "Renderer cleanup failed", failure)
        val restart = pendingRestart
        pendingRestart = null
        if (restart != null && failure == null) {
            scope.launch { restartRenderer(restart) }
        }
    }

    private suspend fun restartRenderer(restart: PendingPaintRestart) {
        runCatching {
            openVisual(restart.url, userInitiated = false, agentAction = false)
            val view = renderer
            val snapshot = restart.snapshot
            if (view != null && snapshot != null) loadSnapshotState(view, snapshot)
        }.onFailure {
            _state.update { state ->
                state.copy(error = "The browser view could not restart. Close the browser to recover.")
            }
        }
    }

    private fun onDownloadRequested(url: String, disposition: String?, mime: String?, length: Long) {
        val name = disposition?.substringAfter("filename=", "")?.trim('"')
            ?.takeIf { it.isNotBlank() } ?: url.substringAfterLast('/').ifBlank { "download" }
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
        val request = DownloadManager.Request(Uri.parse(prompt.url))
            .setTitle(prompt.fileName)
            .setDescription("Downloaded by Rivet's browser")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, prompt.fileName)
        runCatching {
            (app.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            _state.update { it.copy(notice = "Downloading ${prompt.fileName} (${prompt.sizeBytes / 1024}KB).") }
        }.onFailure {
            _state.update { state -> state.copy(error = "The download could not start.") }
        }
    }

    fun settingsSnapshot(): Pair<Boolean, Boolean> = _state.value.allowJs to _state.value.blockThirdParty
}

data class BrowserShot(val path: String, val width: Int, val height: Int)
