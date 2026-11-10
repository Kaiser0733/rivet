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
enum class BrowserDriving { Agent, User }

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
    val tier: BrowserTier = BrowserTier.Normal,
    val capMb: Int = 250,
    val modelLoaded: Boolean = false,
    val budgetLine: String = "",
    val domains: List<BrowserVisit> = emptyList(),
    val windows: List<BrowserWindowSummary> = emptyList(),
    val activeWindowId: String? = null,
    val driving: BrowserDriving = BrowserDriving.Agent,
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
    private var activeUrl: String = ""
    private var activeTitle: String = ""
    private var authLimitStated = false
    private var renderer: WebView? = null
    private var rendererReady: CompletableDeferred<WebView>? = null
    private var pageFinished: CompletableDeferred<Unit>? = null
    private var budget = BrowserGovernor.decide(BrowserMemorySignals(0))
    private var lastRendererStart = 0L
    private var periodic: kotlinx.coroutines.Job? = null

    private val _state = MutableStateFlow(BrowserUiState(
        allowJs = prefs.getBoolean("allow_js", true),
        blockThirdParty = prefs.getBoolean("block_third_party", true),
        desktopMode = prefs.getBoolean("desktop", false),
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

    fun show() { _state.update { it.copy(visible = true) } }
    fun close() { _state.update { it.copy(visible = false) } }
    fun clearNotice() { _state.update { it.copy(notice = null, error = null) } }

    fun contextLine(): String {
        refreshBudget()
        val line = BrowserGovernor.contextLine(budget)
        return if (_state.value.authNotice != null) "$line Identity sign-in stays in your browser." else line
    }

    fun refreshBudget() {
        val manager = app.getSystemService(android.app.ActivityManager::class.java)
        val info = android.app.ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        val loaded = models?.loadedId?.value != null
        val footprint = if (loaded) models?.modelFootprintBytes?.value ?: 0L else 0L
        budget = BrowserGovernor.decide(BrowserMemorySignals(info.availMem, footprint))
        _state.update {
            it.copy(tier = budget.tier, capMb = budget.capMb, modelLoaded = budget.modelLoaded,
                budgetLine = BrowserGovernor.contextLine(budget))
        }
        if (!BrowserGovernor.canOpenRenderer(budget.tier, interaction = true) &&
            _state.value.rendererVisible) {
            destroyRenderer()
            _state.update {
                it.copy(mode = BrowserMode.Reader, rendererVisible = false,
                    notice = BrowserGovernor.refusalReason(budget))
            }
        }
    }

    suspend fun open(rawUrl: String, newWindow: Boolean = false, projectPreview: Boolean = false): BrowserOpenResult =
        actionLock.withLock {
            val url = BrowserUrlPolicy.validate(rawUrl)
            refreshBudget()
            if (BrowserAuthGuard.isAuthSurface(url)) {
                stateHandoff(url, "identity")
                return@withLock BrowserOpenResult(url, "", 0, false, budget.tier, budget.capMb, handoff = true)
            }
            if (newWindow || activeWindowId == null) createWindow(url)
            activeUrl = url
            _state.update { it.copy(visible = true, url = url, error = null, notice = null,
                handoff = null, authNotice = null) }
            try {
                val document = readerService.fetch(url)
                reader = document
                activeTitle = document.title
                recordVisit(url, projectPreview || BrowserAuthGuard.isLoopback(url))
                updateActiveWindow(url, document.title)
                _state.update {
                    it.copy(mode = BrowserMode.Reader, title = document.title,
                        readerText = document.chunks.firstOrNull()?.text.orEmpty(),
                        chunkIndex = 0, chunkCount = document.chunks.size,
                        chunkAnchor = document.chunks.firstOrNull()?.anchor.orEmpty(),
                        needsRenderer = document.needsRenderer, fidelity = BrowserFidelity.Full,
                        windows = windowSummaries())
                }
                BrowserOpenResult(url, document.title, document.chunks.size,
                    document.needsRenderer, budget.tier, budget.capMb)
            } catch (handoff: BrowserHandoff) {
                stateHandoff(handoff.url, handoff.reason)
                BrowserOpenResult(url, "", 0, false, budget.tier, budget.capMb, handoff = true)
            } catch (failure: BrowserFailure) {
                _state.update { it.copy(visible = true, error = failure.message, mode = BrowserMode.Reader) }
                throw failure
            }
        }

    suspend fun read(chunk: Int = 0): BrowserReadResult = actionLock.withLock {
        val document = if (renderer != null && activeUrl.isNotBlank() &&
            (reader?.url != activeUrl || reader?.chunks?.isEmpty() == true)) {
            readRenderedDocument()
        } else reader ?: throw BrowserFailure("browser_not_open", "Open a page first.")
        if (document.chunks.isEmpty()) throw BrowserFailure("browser_needs_renderer",
            "This page needs the renderer for readable text. Open it for interaction.")
        if (chunk !in document.chunks.indices) {
            throw BrowserFailure("browser_chunk_out_of_range",
                "Chunk ${chunk} is outside 0..${document.chunks.lastIndex}.")
        }
        val selected = document.chunks[chunk]
        _state.update { it.copy(chunkIndex = chunk, chunkCount = document.chunks.size,
            chunkAnchor = selected.anchor, readerText = selected.text) }
        BrowserReadResult(document.url, document.title, chunk, document.chunks.size,
            selected.anchor, selected.text, chunk == document.chunks.lastIndex)
    }

    suspend fun find(text: String): List<ReaderHit> = actionLock.withLock {
        val document = if (renderer != null && activeUrl.isNotBlank() &&
            (reader?.url != activeUrl || reader?.chunks?.isEmpty() == true)) {
            readRenderedDocument()
        } else reader ?: throw BrowserFailure("browser_not_open", "Open a page first.")
        ReaderExtractor.find(document.text, text)
    }

    private suspend fun readRenderedDocument(): ReaderDocument {
        val view = renderer ?: throw BrowserFailure("browser_renderer_unavailable",
            "Open the Browser card so the page renderer can start.")
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
        if (result.text.isBlank()) throw BrowserFailure("browser_empty", "This page has no readable text.")
        reader = result
        recordVisit(pageUrl)
        return result
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
            throw BrowserFailure("browser_no_back", "There is no previous page in this window.")
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
        withContext(Dispatchers.Main) { captureScreenshot(view) }
    }

    /** Attaches the sheet-hosted WebView. Called on the main thread. */
    fun attach(view: WebView) {
        if (renderer === view) return
        renderer?.let { old -> runCatching { old.destroy() } }
        renderer = view
        configure(view)
        rendererReady?.complete(view)
        view.webViewClient = clientFor(view)
        view.setDownloadListener { url, _, contentDisposition, mime, contentLength ->
            onDownloadRequested(url, contentDisposition, mime, contentLength)
        }
        _state.update { it.copy(rendererVisible = true) }
        lastRendererStart = System.currentTimeMillis()
        periodic?.cancel()
        periodic = scope.launch {
            while (true) {
                kotlinx.coroutines.delay(15_000)
                refreshBudget()
            }
        }
    }

    fun detach(view: WebView?) {
        if (view != null && renderer !== view) return
        destroyRenderer()
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
        renderer?.settings?.loadWithOverviewMode = !enabled
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
        if (budget.tier == BrowserTier.ReaderFirst) destroyRenderer()
    }

    fun setDriving(userDriving: Boolean) {
        _state.update { it.copy(driving = if (userDriving) BrowserDriving.User else BrowserDriving.Agent) }
    }

    fun handBack() {
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
            if (budget.tier == BrowserTier.ReaderFirst) destroyRenderer()
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

    private fun createWindow(url: String): String {
        val id = UUID.randomUUID().toString()
        val capped = BrowserSnapshotCodec.capWindows(windows.values.toList(), activeWindowId)
        if (capped.size >= BrowserSnapshotCodec.MAX_WINDOWS) {
            capped.filter { it.id != activeWindowId }.minByOrNull { it.lastUsedAt }?.let {
                windows.remove(it.id); snapshots.delete(it.id)
            }
        }
        windows[id] = BrowserSnapshot(id, historyUrls = listOf(url), historyIndex = 0)
        activeWindowId = id
        _state.update { it.copy(activeWindowId = id, windows = windowSummaries()) }
        return id
    }

    private fun updateActiveWindow(url: String, title: String) {
        val id = activeWindowId ?: createWindow(url)
        val current = windows[id] ?: BrowserSnapshot(id)
        val history = if (current.currentUrl == url) current.historyUrls
        else (current.historyUrls.take(current.historyIndex + 1) + url).takeLast(50)
        val updated = current.copy(title = title, historyUrls = history,
            historyIndex = history.lastIndex.coerceAtLeast(0), lastUsedAt = System.currentTimeMillis(),
            fidelity = BrowserFidelity.Full)
        windows[id] = updated
        snapshots.save(updated)
        _state.update { it.copy(windows = windowSummaries()) }
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
            it.currentUrl.orEmpty(), it.fidelity, it.id == activeWindowId)
    }

    private fun recordVisit(url: String, preview: Boolean = false) {
        if (url.isBlank() || url == "about:blank") return
        trail.record(url, preview)
        _state.update { it.copy(domains = trail.visits) }
    }

    private fun configure(view: WebView) {
        val s = view.settings
        s.javaScriptEnabled = _state.value.allowJs
        s.domStorageEnabled = true
        s.useWideViewPort = _state.value.desktopMode
        s.loadWithOverviewMode = !_state.value.desktopMode
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
                mode = BrowserMode.Renderer, rendererVisible = true, fidelity = BrowserFidelity.Reloaded) }
            if (!BrowserAuthGuard.isLoopback(actual)) {
                view.evaluateJavascript("document.querySelector('input[type=password]') !== null") { raw ->
                    if (raw.trim('"') == "true") stateHandoff(actual, "sign-in-wall")
                }
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val rules = BrowserDiet.rulesFor(budget.tier, _state.value.fullPage)
            val url = request.url.toString()
            val kind = when {
                request.requestHeaders["Accept"]?.contains("image", true) == true -> BrowserResourceKind.Image
                url.matches(Regex("(?i).*\\.(woff2?|ttf|otf)(\\?.*)?$")) -> BrowserResourceKind.Font
                url.matches(Regex("(?i).*\\.(mp4|webm|m4s|mp3)(\\?.*)?$")) -> BrowserResourceKind.Media
                BrowserDiet.isTrackerHost(request.url.host.orEmpty()) -> BrowserResourceKind.Tracker
                else -> BrowserResourceKind.Other
            }
            if (BrowserDiet.shouldBlock(url, kind, 0, rules)) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            return null
        }

        override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
            destroyRenderer()
            _state.update { it.copy(mode = BrowserMode.Reader, rendererVisible = false,
                error = "The page renderer stopped. Reader mode and a reload are available.") }
            return true
        }
    }

    private suspend fun ensureRenderer(): WebView {
        refreshBudget()
        if (!BrowserGovernor.canOpenRenderer(budget.tier, interaction = true)) {
            throw BrowserFailure("browser_renderer_blocked", BrowserGovernor.refusalReason(budget))
        }
        if (_state.value.driving == BrowserDriving.User) {
            throw BrowserFailure("browser_user_driving", "The user is driving the browser. Ask them to hand it back.")
        }
        if (_state.value.handoff != null) {
            throw BrowserFailure("browser_handoff",
                "Identity work moved to the phone's browser. Read-only pages still work; interactive agent actions are paused.")
        }
        _state.update { it.copy(visible = true, rendererVisible = true) }
        val existing = renderer
        if (existing != null) return existing
        val ready = CompletableDeferred<WebView>()
        rendererReady = ready
        val attached = withTimeoutOrNull(8_000) { ready.await() }
        if (attached == null) {
            throw BrowserFailure("browser_renderer_unavailable",
                "Open the Browser card so the page renderer can start.")
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

    private fun clickTargetScript(selector: String?, text: String?): String = """
        (function(){
          var sel=${jsonString(selector)};
          var txt=${jsonString(text)};
          var el=sel?document.querySelector(sel):null;
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

    private fun destroyRenderer() {
        periodic?.cancel(); periodic = null
        val view = renderer ?: return
        renderer = null
        rendererReady?.cancel()
        rendererReady = null
        runCatching { view.stopLoading(); view.destroy() }
        _state.update { it.copy(rendererVisible = false, mode = BrowserMode.Reader) }
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
