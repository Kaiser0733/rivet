package com.kaiser.rivet.ui.files

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.rivet.workspace.SafWorkspace
import com.kaiser.rivet.workspace.WorkspaceEpoch
import com.kaiser.rivet.workspace.WorkspaceFailure
import com.kaiser.rivet.workspace.WorkspacePath
import com.kaiser.rivet.workspace.WorkspaceSelection
import com.kaiser.rivet.workspace.WorkspaceText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FilesViewModel(application: Application) : AndroidViewModel(application) {
    private val selection = WorkspaceSelection(application)
    private var workspace: SafWorkspace? = null
    private val mutable = MutableStateFlow(FilesState(loading = true))
    val state = mutable.asStateFlow()
    private val navigation = WorkspaceEpoch()
    private val searches = WorkspaceEpoch()
    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        loadJob = viewModelScope.launch {
            try {
                val restored = selection.restore()
                if (restored == null) mutable.value = FilesState()
                else {
                    workspace = restored.first
                    mutable.value = FilesState(selected = true)
                    navigate(restored.second)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutable.value = FilesState(error = message(e)) }
        }
    }

    fun select(uri: Uri, flags: Int) {
        if (mutable.value.mutating) return
        invalidate()
        mutable.update { it.copy(mutating = true, loading = false, error = null) }
        viewModelScope.launch {
            try {
                val selected = selection.select(uri, flags)
                ensureActive()
                workspace = selected
                mutable.value = FilesState(selected = true)
                navigate(WorkspacePath.ROOT)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutable.update { it.copy(error = message(e)) }
            } finally { mutable.update { it.copy(mutating = false) } }
        }
    }

    private fun invalidate() {
        navigation.next()
        loadJob?.cancel()
        cancelSearch()
    }

    fun navigate(path: WorkspacePath) {
        val selected = workspace ?: return
        if (mutable.value.mutating || mutable.value.dirty) return
        invalidate()
        val ticket = navigation.next()
        mutable.update { it.copy(directory = path, opened = null, snapshot = null, draft = "", fileNotice = null,
            entries = emptyList(), folder = null, loading = true, error = null, search = null) }
        loadJob = viewModelScope.launch {
            try {
                val folder = selected.stat(path)
                val entries = selected.listDirectory(path)
                ensureActive()
                if (navigation.isCurrent(ticket)) {
                    mutable.update { it.copy(folder = folder, entries = entries) }
                    selection.rememberDirectory(selected, path)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (navigation.isCurrent(ticket)) mutable.update { it.copy(error = message(e)) }
            } finally {
                if (navigation.isCurrent(ticket)) mutable.update { it.copy(loading = false) }
            }
        }
    }

    fun open(path: WorkspacePath) {
        val selected = workspace ?: return
        if (mutable.value.mutating || mutable.value.dirty) return
        invalidate()
        val ticket = navigation.next()
        mutable.update { it.copy(opened = null, snapshot = null, draft = "", fileNotice = null, loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val entry = selected.stat(path)
                if (!navigation.isCurrent(ticket)) return@launch
                if (entry.directory) { navigate(path); return@launch }
                mutable.update { it.copy(opened = entry) }
                val snapshot = selected.readTextFile(path)
                ensureActive()
                if (navigation.isCurrent(ticket)) mutable.update { it.copy(snapshot = snapshot, draft = snapshot.text) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (navigation.isCurrent(ticket)) mutable.update { it.copy(fileNotice = message(e), error = message(e)) }
            } finally {
                if (navigation.isCurrent(ticket)) mutable.update { it.copy(loading = false) }
            }
        }
    }

    fun edit(text: String) {
        if (mutable.value.mutating || mutable.value.snapshot == null || mutable.value.opened?.capabilities?.write != true) return
        if (text.length > WorkspaceText.MAX_BYTES) {
            mutable.update { it.copy(error = WorkspaceFailure.Reason.TOO_LARGE.message) }
        } else mutable.update { it.copy(draft = text) }
    }
    fun discard() {
        if (!mutable.value.mutating) mutable.update { it.copy(draft = it.snapshot?.text ?: "") }
    }

    fun save() {
        val selected = workspace ?: return
        val current = mutable.value
        val snapshot = current.snapshot ?: return
        if (current.mutating || current.loading || !current.dirty) return
        val ticket = navigation.next()
        mutable.update { it.copy(mutating = true, error = null) }
        viewModelScope.launch {
            try {
                val saved = selected.writeTextFile(snapshot.path, current.draft, snapshot.sha256)
                ensureActive()
                if (navigation.isCurrent(ticket)) mutable.update { it.copy(snapshot = saved, draft = saved.text) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (navigation.isCurrent(ticket)) mutable.update { it.copy(error = message(e)) }
            } finally {
                if (navigation.isCurrent(ticket)) mutable.update { it.copy(mutating = false) }
            }
        }
    }

    fun create(name: String, directory: Boolean) = mutate { selected ->
        val path = mutable.value.directory.child(name)
        if (directory) selected.createDirectory(path) else selected.createFile(path)
    }
    fun rename(path: WorkspacePath, name: String) = mutate { it.rename(path, name) }
    fun delete(path: WorkspacePath) = mutate { it.delete(path) }
    fun move(path: WorkspacePath, destination: String) = mutate { it.move(path, WorkspacePath.parse(destination)) }

    private fun mutate(operation: suspend (SafWorkspace) -> Unit) {
        val selected = workspace ?: return
        val current = mutable.value
        if (current.mutating || current.loading || current.dirty) return
        invalidate()
        mutable.update { it.copy(mutating = true, error = null) }
        viewModelScope.launch {
            try {
                operation(selected)
                ensureActive()
                mutable.update { it.copy(mutating = false) }
                navigate(current.directory)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { mutable.update { it.copy(error = message(e)) }
            } finally { mutable.update { it.copy(mutating = false) } }
        }
    }

    fun setQuery(query: String) {
        cancelSearch()
        mutable.update { it.copy(query = query.take(256), search = null) }
    }
    fun cancelSearch() {
        searches.next()
        searchJob?.cancel()
        mutable.update { it.copy(searching = false) }
    }
    fun search() {
        val selected = workspace ?: return
        val current = mutable.value
        if (current.query.isEmpty() || current.mutating || current.loading) return
        cancelSearch()
        val ticket = searches.next()
        mutable.update { it.copy(searching = true, search = null, error = null) }
        searchJob = viewModelScope.launch {
            try {
                val report = selected.search(current.query, current.directory)
                ensureActive()
                if (searches.isCurrent(ticket)) mutable.update { it.copy(search = report) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (searches.isCurrent(ticket)) mutable.update { it.copy(error = message(e)) }
            } finally {
                if (searches.isCurrent(ticket)) mutable.update { it.copy(searching = false) }
            }
        }
    }

    private fun message(error: Exception): String =
        (error as? WorkspaceFailure)?.reason?.message ?: WorkspaceFailure.Reason.PROVIDER.message
}
