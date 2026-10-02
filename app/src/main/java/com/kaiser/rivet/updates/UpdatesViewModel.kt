package com.kaiser.rivet.updates

import android.app.Application
import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object Current : UpdateUiState
    data class Available(val release: ReleaseUpdate) : UpdateUiState
    data class Downloading(val release: ReleaseUpdate, val bytes: Long, val total: Long?) : UpdateUiState
    data class Verifying(val release: ReleaseUpdate) : UpdateUiState
    data class AwaitingSave(val release: ReleaseUpdate, val pickerOpen: Boolean = false) : UpdateUiState
    data class Saving(val release: ReleaseUpdate) : UpdateUiState
    data class Complete(val release: ReleaseUpdate, val location: String, val notice: String? = null) : UpdateUiState
    data class Error(val message: String) : UpdateUiState
}

internal class UpdatesViewModel internal constructor(
    app: Application,
    private val updates: GitHubUpdates,
    private val apks: UpdateApks,
    private val installedVersion: String,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app,
        GitHubUpdates(File(app.cacheDir, "updates")), UpdateApks(app),
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty())

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var verified: UpdateApks.Verified? = null
    private val preparation = viewModelScope.launch(Dispatchers.IO) {
        try {
            updates.discardPartials()
            apks.discardPendingExports()
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) { /* Stale private/pending exports are never presented as completed updates. */ }
    }

    fun check() {
        if (job?.isActive == true || verified != null) return
        _state.value = UpdateUiState.Checking
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                preparation.join()
                val release = updates.check(installedVersion)
                _state.value = if (release == null) UpdateUiState.Current else UpdateUiState.Available(release)
            } catch (e: CancellationException) { _state.value = UpdateUiState.Idle; throw e
            } catch (e: Exception) { fail(e, "Rivet couldn't check for updates. Try again.") }
        }
    }

    fun download() {
        val release = (_state.value as? UpdateUiState.Available)?.release ?: return
        if (job?.isActive == true) return
        _state.value = UpdateUiState.Downloading(release, 0, null)
        job = viewModelScope.launch(Dispatchers.IO) {
            var staged: File? = null
            try {
                preparation.join()
                staged = updates.download(release) { bytes, total ->
                    _state.value = UpdateUiState.Downloading(release, bytes, total)
                }
                _state.value = UpdateUiState.Verifying(release)
                verified = apks.verify(staged, release)
                if (Build.VERSION.SDK_INT >= 29) {
                    _state.value = UpdateUiState.Saving(release)
                    apks.saveToDownloads(requireNotNull(verified))
                    _state.value = UpdateUiState.Complete(release, "Saved to Downloads")
                } else {
                    _state.value = UpdateUiState.AwaitingSave(release)
                }
            } catch (e: CancellationException) { _state.value = UpdateUiState.Idle; throw e
            } catch (e: Exception) { fail(e, "Rivet couldn't save the update. Try again.")
            } finally {
                if (_state.value !is UpdateUiState.AwaitingSave) {
                    verified?.close(); verified = null; staged?.delete()
                }
            }
        }
    }

    fun beginSavePicker(): Intent? {
        val waiting = _state.value as? UpdateUiState.AwaitingSave ?: return null
        val file = verified ?: return null
        if (waiting.pickerOpen || job?.isActive == true) return null
        _state.value = waiting.copy(pickerOpen = true)
        return UpdateApks.saveIntent(file)
    }

    fun saveDocument(uri: Uri?) {
        val file = verified ?: return // A process restart discards the private download.
        if (_state.value !is UpdateUiState.AwaitingSave || job?.isActive == true) return
        if (uri == null) { file.close(); verified = null; _state.value = UpdateUiState.Available(file.release); return }
        _state.value = UpdateUiState.Saving(file.release)
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                apks.saveDocument(file, uri)
                _state.value = UpdateUiState.Complete(file.release, "Saved to the location you chose")
            } catch (e: CancellationException) { _state.value = UpdateUiState.Idle; throw e
            } catch (e: Exception) { fail(e, "Rivet couldn't save the update to that location. Try again.")
            } finally { file.close(); verified = null }
        }
    }

    fun cancel() {
        if (job?.isActive == true) job?.cancel()
        else { verified?.close(); verified = null; _state.value = UpdateUiState.Idle }
    }

    fun pickerUnavailable() {
        verified?.close(); verified = null
        _state.value = UpdateUiState.Error("Rivet couldn't open the save picker. Try again.")
    }

    fun openDownloads() {
        val complete = _state.value as? UpdateUiState.Complete ?: return
        try {
            getApplication<Application>().startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            _state.value = complete.copy(notice = "The update is saved. Open your file manager to find it.")
        }
    }

    private fun fail(error: Exception, fallback: String) {
        _state.value = UpdateUiState.Error((error as? UpdateFailure)?.message ?: fallback)
    }

    override fun onCleared() {
        job?.cancel()
        verified?.close()
        verified = null
        super.onCleared()
    }
}
