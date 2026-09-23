package com.kaiser.rivet.ui.changes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaiser.rivet.runtime.CheckpointChange
import com.kaiser.rivet.runtime.CheckpointFailure
import com.kaiser.rivet.runtime.CheckpointRecord
import com.kaiser.rivet.runtime.MirrorFailure
import com.kaiser.rivet.runtime.MirrorSync
import com.kaiser.rivet.runtime.RepositoryStatus
import com.kaiser.rivet.runtime.RuntimeController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChangedPath(val path: String, val status: String)

data class ChangesUiState(
    val loading: Boolean = false,
    val repository: RepositoryStatus? = null,
    val checkpointAt: Long? = null,
    val files: List<ChangedPath> = emptyList(),
    val selectedPath: String? = null,
    val diff: String = "",
    val error: String? = null,
)

class ChangesViewModel : ViewModel() {
    private var runtime: RuntimeController? = null
    private var checkpoint: CheckpointRecord? = null
    private var generation = 0L
    private val mutable = MutableStateFlow(ChangesUiState())
    val state: StateFlow<ChangesUiState> = mutable.asStateFlow()

    fun attachRuntime(controller: RuntimeController) { runtime = controller }

    fun refresh() {
        val controller = runtime ?: return
        val ticket = ++generation
        mutable.value = mutable.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val repository = controller.gitStatus()
                val latest = controller.latestCheckpoint()
                val agentChanges = latest?.let { controller.checkpointChanges(it) }.orEmpty()
                val files = changedPaths(repository, agentChanges)
                if (ticket == generation) {
                    checkpoint = latest
                    mutable.value = ChangesUiState(repository = repository,
                        checkpointAt = latest?.createdAt, files = files)
                }
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (ticket == generation) mutable.value = ChangesUiState(error = display(error))
            }
        }
    }

    fun select(path: String) {
        val controller = runtime ?: return
        val ticket = ++generation
        mutable.value = mutable.value.copy(selectedPath = path, diff = "", loading = true, error = null)
        viewModelScope.launch {
            try {
                val git = if (mutable.value.repository?.present == true) controller.gitDiff(path) else null
                val detail = if (git != null && git.text.isNotBlank()) {
                    git.text + if (git.limited) "\n[diff limited]" else ""
                } else {
                    checkpoint?.let { controller.checkpointDiff(it, path).let { preview ->
                        preview.text + if (preview.limited) "\n[diff limited]" else ""
                    } } ?: "No text diff is available for this file."
                }
                if (ticket == generation) mutable.value = mutable.value.copy(loading = false, diff = detail)
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                if (ticket == generation) mutable.value = mutable.value.copy(loading = false, error = display(error))
            }
        }
    }

    fun undo() {
        val controller = runtime ?: return
        ++generation
        mutable.value = mutable.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val outcome = controller.undoLastCheckpoint()
                if (outcome.state != MirrorSync.Ok && outcome.state != MirrorSync.NoChanges) {
                    mutable.value = mutable.value.copy(loading = false,
                        error = "Undo could not sync to the workspace. Resolve the conflict before continuing.")
                } else refresh()
            } catch (error: CancellationException) { throw error
            } catch (error: Exception) {
                mutable.value = mutable.value.copy(loading = false, error = display(error))
            }
        }
    }

    private fun display(error: Exception): String = when (error) {
        is CheckpointFailure -> when (error.code) {
            "undo_conflict" -> "Undo stopped because the workspace changed after the agent turn. Your newer work was kept."
            "undo_unavailable" -> "No completed agent checkpoint is available to undo."
            else -> "Checkpoint operation failed (${error.code})."
        }
        is MirrorFailure -> when (error.code) {
            "terminal_active" -> "Stop the Terminal before reviewing or undoing changes."
            "sync_required", "mirror_dirty" -> "Sync or resolve pending Terminal changes first."
            "conflict" -> "The workspace changed outside Rivet. Undo stopped without overwriting it."
            else -> "Workspace operation failed (${error.code})."
        }
        else -> "Changes are unavailable (${error.javaClass.simpleName})."
    }

    companion object {
        internal fun changedPaths(repository: RepositoryStatus, agentChanges: List<CheckpointChange>): List<ChangedPath> {
            val states = linkedMapOf<String, MutableSet<String>>()
            fun add(paths: List<String>, label: String) {
                paths.forEach { states.getOrPut(it) { linkedSetOf() } += label }
            }
            if (repository.present) {
                add(repository.staged, "staged")
                add(repository.modified, "modified")
                add(repository.deleted, "deleted")
                add(repository.untracked, "untracked")
                add(repository.conflicts, "conflict")
            }
            agentChanges.forEach { change -> states.getOrPut(change.path) { linkedSetOf() } += change.kind }
            return states.map { (path, labels) -> ChangedPath(path, labels.joinToString(", ")) }
                .sortedBy { it.path }
        }
    }
}
