package com.kaiser.rivet.local

import android.app.ActivityManager
import android.app.Application
import android.net.Uri
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelsUiState(
    val loading: Boolean = true,
    val catalogueError: String? = null,
    val entries: List<LocalModelEntry> = emptyList(),
    val recommendation: Recommendation = Recommendation(null, null, ""),
    val fits: Map<String, ModelFit> = emptyMap(),
    val totalRamMb: Int = 0,
    val availableRamMb: Int = 0,
    val freeStorage: Long = 0,
    val wifiOnly: Boolean = true,
    val notice: String? = null,
    val byoBusy: Boolean = false,
    val byoError: String? = null,
    val staleBytes: Long = 0,
) {
    fun fitFor(entry: LocalModelEntry): ModelFit = fits[entry.id] ?: ModelRecommender.fit(
        entry, totalRamMb, availableRamMb.toLong() * 1024 * 1024)
    val hasDeviceFacts: Boolean get() = totalRamMb > 0
}

class ModelsViewModel(app: Application) : AndroidViewModel(app) {
    private val manager = LocalRuntime.requireModels()
    private val downloads = LocalRuntime.downloads
    private val resolver = ByoModelResolver()
    private val _ui = MutableStateFlow(ModelsUiState(wifiOnly = downloads?.wifiOnly ?: true))
    val ui: StateFlow<ModelsUiState> = _ui.asStateFlow()
    val installed: StateFlow<List<InstalledLocalModel>> = manager.installed
    val jobs: StateFlow<Map<String, DownloadJob>> =
        downloads?.jobs ?: MutableStateFlow(emptyMap())

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, catalogueError = null)
            val entries = withContext(Dispatchers.IO) {
                runCatching { ModelCatalogue.load(getApplication()) }
            }.getOrElse {
                runCatching { ModelCatalogue.loadBundled(getApplication()) }
                    .getOrElse { error ->
                        _ui.value = _ui.value.copy(loading = false,
                            catalogueError = error.message ?: "The model catalogue could not be read.")
                        return@launch
                    }
            }
            val facts = deviceFacts()
            val recommendation = ModelRecommender.recommend(entries, facts.totalMb, facts.availableBytes)
            val fits = ModelRecommender.assess(entries, facts.totalMb, facts.availableBytes)
                .associateBy { it.entry.id }
            _ui.value = _ui.value.copy(
                loading = false,
                entries = entries,
                recommendation = recommendation,
                fits = fits,
                totalRamMb = facts.totalMb,
                availableRamMb = (facts.availableBytes / (1024 * 1024)).toInt(),
                freeStorage = facts.freeStorage,
                wifiOnly = downloads?.wifiOnly ?: true,
                staleBytes = downloads?.staleBytes() ?: 0,
            )
        }
    }

    /** A pre-start warning on metered networks, so a 2.5 GB tap is never silent. */
    fun mobileWarning(entry: LocalModelEntry): String? {
        if (!_ui.value.wifiOnly || entry.size <= DownloadPolicy.WIFI_DEFAULT_THRESHOLD) return null
        val manager = getApplication<Application>().getSystemService(
            android.net.ConnectivityManager::class.java) ?: return null
        val network = manager.activeNetwork ?: return null
        val caps = manager.getNetworkCapabilities(network) ?: return null
        if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return null
        val kind = when {
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkKind.Cellular
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
            else -> NetworkKind.Other
        }
        return DownloadPolicy.meteredWarning(kind, entry.size)
    }

    fun download(entry: LocalModelEntry, allowMobile: Boolean = false) {
        val fit = _ui.value.fitFor(entry)
        if (!fit.canDownload) {
            _ui.value = _ui.value.copy(notice = fit.reason.ifBlank {
                "This model is too big for this phone's safe memory ceiling."
            })
            return
        }
        val error = downloads?.start(entry, allowMobile = allowMobile)
        if (error != null) {
            _ui.value = _ui.value.copy(notice = error, wifiOnly = downloads?.wifiOnly ?: _ui.value.wifiOnly)
        }
    }

    fun resume(job: DownloadJob, allowMobile: Boolean = false) {
        downloads?.resume(job.entry.id, allowMobile)
    }

    fun pause(job: DownloadJob) {
        downloads?.pause(job.entry.id)
    }

    fun cancel(job: DownloadJob) {
        downloads?.cancel(job.entry.id)
    }

    fun remove(entry: LocalModelEntry) {
        viewModelScope.launch {
            runCatching { manager.remove(entry) }
                .onFailure { _ui.value = _ui.value.copy(notice = it.message) }
        }
    }

    fun activate(entry: LocalModelEntry) {
        viewModelScope.launch {
            runCatching { manager.activate(entry) }
                .onSuccess {
                    _ui.value = _ui.value.copy(notice = "Local · ${entry.name} is ready.")
                }
                .onFailure { _ui.value = _ui.value.copy(notice = it.message) }
        }
    }

    fun addByo(rawUrl: String) {
        if (rawUrl.isBlank()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(byoBusy = true, byoError = null)
            val resolved = withContext(Dispatchers.IO) {
                runCatching { resolver.resolve(rawUrl) }
            }
            _ui.value = _ui.value.copy(byoBusy = false)
            resolved.onFailure {
                _ui.value = _ui.value.copy(byoError = it.message ?: "That link could not be used.")
                return@launch
            }
            val entry = resolved.getOrThrow().entry
            val fit = ModelRecommender.fit(entry, _ui.value.totalRamMb,
                _ui.value.availableRamMb.toLong() * 1024 * 1024)
            if (!fit.canDownload) {
                _ui.value = _ui.value.copy(byoError = fit.reason.ifBlank {
                    "That model is too big for this phone's memory."
                })
                return@launch
            }
            val error = downloads?.start(entry, byo = true)
            if (error != null) {
                _ui.value = _ui.value.copy(byoError = error)
            } else {
                _ui.value = _ui.value.copy(notice = "Downloading ${entry.name}.")
            }
        }
    }

    fun export(entry: LocalModelEntry, uri: Uri) {
        viewModelScope.launch {
            runCatching { manager.export(entry, uri) }
                .onSuccess { _ui.value = _ui.value.copy(notice = "Exported ${entry.name}.") }
                .onFailure { _ui.value = _ui.value.copy(notice = it.message ?: "The model could not be exported.") }
        }
    }

    fun cleanupStale() {
        downloads?.cleanupStaleParts()
        _ui.value = _ui.value.copy(staleBytes = downloads?.staleBytes() ?: 0,
            notice = "Old partial downloads were removed.")
    }

    fun setWifiOnly(enabled: Boolean) {
        downloads?.setWifiOnlyPreference(enabled)
        _ui.value = _ui.value.copy(wifiOnly = enabled)
    }

    fun dismissNotice() {
        _ui.value = _ui.value.copy(notice = null)
    }

    fun dismissByoError() {
        _ui.value = _ui.value.copy(byoError = null)
    }

    fun storageWarning(job: DownloadJob): String? = downloads?.storageWarning(job)

    fun networkProblem(job: DownloadJob): String? = downloads?.networkProblem(job)

    private data class Facts(val totalMb: Int, val availableBytes: Long, val freeStorage: Long)

    private fun deviceFacts(): Facts {
        val activity = getApplication<Application>().getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        activity?.getMemoryInfo(info)
        val totalMb = (info.totalMem / (1024 * 1024)).toInt()
        val free = runCatching {
            StatFs(getApplication<Application>().filesDir.absolutePath).availableBytes
        }.getOrDefault(0L)
        return Facts(totalMb, info.availMem, free)
    }
}
