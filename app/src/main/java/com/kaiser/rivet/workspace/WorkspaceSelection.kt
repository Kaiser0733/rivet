package com.kaiser.rivet.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class WorkspaceSelection(context: Context, private val viewedTree: StateFlow<String?>? = null) {
    private val persistence = AndroidGrantedFolders.permissionLock
    private val app = context.applicationContext
    private val preferences by lazy { app.getSharedPreferences("workspace", Context.MODE_PRIVATE) }
    private val resolver get() = app.contentResolver

    suspend fun restore(): Triple<SafWorkspace, WorkspacePath, WorkspacePath?>? = withContext(Dispatchers.IO) {
        val stored = currentIdentity() ?: return@withContext null
        if (viewedTree != null) return@withContext Triple(openTree(Uri.parse(stored)), WorkspacePath.ROOT, null)
        val path = try { WorkspacePath.parse(preferences.getString("directory", "") ?: "") }
            catch (_: WorkspaceFailure) { WorkspacePath.ROOT }
        val file = try { preferences.getString("file", null)?.let(WorkspacePath::parse) }
            catch (_: WorkspaceFailure) { null }
        Triple(openTree(Uri.parse(stored)), path, file)
    }

    suspend fun currentIdentity(): String? = withContext(Dispatchers.IO) {
        if (viewedTree != null) viewedTree.value else preferences.getString("tree", null)
    }

    suspend fun awaitIdentityChange(expected: String) {
        if (viewedTree != null) {
            viewedTree.first { it != expected }
            return
        }
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            lateinit var listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener
            fun check() {
                if (preferences.getString("tree", null) != expected && completed.compareAndSet(false, true)) {
                    preferences.unregisterOnSharedPreferenceChangeListener(listener)
                    continuation.resume(Unit)
                }
            }
            listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "tree") check()
            }
            preferences.registerOnSharedPreferenceChangeListener(listener)
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    preferences.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }
            check()
        }
    }

    suspend fun select(
        uri: Uri, returnedFlags: Int, prepare: (suspend (SafWorkspace) -> Unit)? = null,
    ): SafWorkspace = withContext(Dispatchers.IO) {
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri) ||
            returnedFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0 ||
            returnedFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) {
            throw WorkspaceFailure(WorkspaceFailure.Reason.PERMISSION)
        }
        val flags = returnedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            persistence.withLock {
                val alreadyGranted = if (prepare == null) 0 else grantFlags(uri)
                try {
                    resolver.takePersistableUriPermission(uri, flags)
                } catch (_: SecurityException) {
                    throw WorkspaceFailure(WorkspaceFailure.Reason.PERMISSION)
                }
                val candidate = SafWorkspace(resolver, uri)
                var selected = false
                try {
                    if (!candidate.stat(WorkspacePath.ROOT).directory) {
                        throw WorkspaceFailure(WorkspaceFailure.Reason.NOT_DIRECTORY)
                    }
                    prepare?.invoke(candidate)
                    withContext(NonCancellable) {
                        if (viewedTree == null && !preferences.edit().putString("tree", uri.toString())
                                .putString("directory", "").remove("file").commit()) {
                            throw WorkspaceFailure(WorkspaceFailure.Reason.PROVIDER)
                        }
                        selected = true
                        // Grants are per tree and can coexist. Selecting another project must
                        // never release a grant a live run still uses; Android drops grants when
                        // the user clears them or the tree disappears.
                    }
                    candidate
                } finally {
                    if (prepare != null && !selected) {
                        runCatching { releaseGrant(uri, flags and alreadyGranted.inv()) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: WorkspaceFailure) {
            throw e
        } catch (e: Exception) {
            if (prepare != null) throw e
            throw WorkspaceFailure(WorkspaceFailure.Reason.PERMISSION)
        }
    }

    private fun grantFlags(uri: Uri): Int {
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return 0
        return (if (grant.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (grant.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
    }

    private fun releaseGrant(uri: Uri, flags: Int) {
        if (flags != 0) resolver.releasePersistableUriPermission(uri, flags)
    }

    internal suspend fun openTree(uri: Uri): SafWorkspace = withContext(Dispatchers.IO) {
        val readable = resolver.persistedUriPermissions.filter { it.isReadPermission }
        val granted = readable.firstOrNull { it.uri == uri }
            ?: readable.firstOrNull { sameWorkspaceTree(it.uri.toString(), uri.toString()) }
        SafWorkspace(resolver, granted?.uri ?: uri)
    }

    suspend fun rememberLocation(workspace: SafWorkspace, directory: WorkspacePath, file: WorkspacePath? = null) = withContext(Dispatchers.IO) {
        if (viewedTree != null) return@withContext
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

/** Document IDs are opaque and scoped to their authority. URI escaping is interchangeable. */
internal fun sameWorkspaceTree(first: String?, second: String?): Boolean {
    if (first == null || second == null) return false
    if (first == second) return true
    return try {
        val a = URI(first)
        val b = URI(second)
        a.scheme == "content" && b.scheme == "content" && a.rawAuthority != null &&
            a.rawAuthority == b.rawAuthority && a.isDocumentTree() && b.isDocumentTree() && a.path == b.path
    } catch (_: URISyntaxException) {
        false
    }
}

private fun URI.isDocumentTree(): Boolean {
    val id = rawPath?.removePrefix("/tree/") ?: return false
    return rawPath.startsWith("/tree/") && id.isNotEmpty() && '/' !in id
}
