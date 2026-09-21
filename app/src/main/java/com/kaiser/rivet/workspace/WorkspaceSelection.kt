package com.kaiser.rivet.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class WorkspaceSelection(context: Context) {
    private val persistence = Mutex()
    private val app = context.applicationContext
    private val preferences by lazy { app.getSharedPreferences("workspace", Context.MODE_PRIVATE) }
    private val resolver get() = app.contentResolver

    suspend fun restore(): Triple<SafWorkspace, WorkspacePath, WorkspacePath?>? = withContext(Dispatchers.IO) {
        val stored = preferences.getString("tree", null) ?: return@withContext null
        val path = try { WorkspacePath.parse(preferences.getString("directory", "") ?: "") }
            catch (_: WorkspaceFailure) { WorkspacePath.ROOT }
        val file = try { preferences.getString("file", null)?.let(WorkspacePath::parse) }
            catch (_: WorkspaceFailure) { null }
        Triple(SafWorkspace(resolver, Uri.parse(stored)), path, file)
    }

    suspend fun select(uri: Uri, returnedFlags: Int): SafWorkspace = withContext(Dispatchers.IO) {
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri) ||
            returnedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 ||
            returnedFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) {
            throw WorkspaceFailure(WorkspaceFailure.Reason.PERMISSION)
        }
        val flags = returnedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            resolver.takePersistableUriPermission(uri, flags)
            val candidate = SafWorkspace(resolver, uri)
            if (!candidate.stat(WorkspacePath.ROOT).directory) throw WorkspaceFailure(WorkspaceFailure.Reason.NOT_DIRECTORY)
            withContext(NonCancellable) {
                persistence.withLock {
                    val previous = preferences.getString("tree", null)
                    if (!preferences.edit().putString("tree", uri.toString()).putString("directory", "").remove("file").commit()) {
                        throw WorkspaceFailure(WorkspaceFailure.Reason.PROVIDER)
                    }
                    if (previous != null && previous != uri.toString()) {
                        try {
                            resolver.persistedUriPermissions.firstOrNull { it.uri.toString() == previous }?.let {
                                val previousFlags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                                    (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                                resolver.releasePersistableUriPermission(it.uri, previousFlags)
                            }
                        } catch (_: Exception) { /* Releasing an obsolete grant cannot undo the new selection. */ }
                    }
                }
            }
            candidate
        } catch (e: CancellationException) { throw e
        } catch (e: WorkspaceFailure) { throw e
        } catch (_: Exception) { throw WorkspaceFailure(WorkspaceFailure.Reason.PERMISSION) }
    }

    suspend fun rememberLocation(workspace: SafWorkspace, directory: WorkspacePath, file: WorkspacePath? = null) = withContext(Dispatchers.IO) {
        persistence.withLock {
            currentCoroutineContext().ensureActive()
            if (preferences.getString("tree", null) == workspace.tree.toString()) {
                if (!preferences.edit().putString("directory", directory.value).putString("file", file?.value).commit()) {
                    throw WorkspaceFailure(WorkspaceFailure.Reason.PROVIDER)
                }
            }
        }
    }
}
