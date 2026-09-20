package com.kaiser.rivet.workspace

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SafWorkspace(private val resolver: ContentResolver, val tree: Uri) {
    private val mutations = Mutex()
    private val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

    private fun requireGrant(): Boolean {
        if (tree.scheme != "content" || !DocumentsContract.isTreeUri(tree)) fail(WorkspaceFailure.Reason.PERMISSION)
        val grant = resolver.persistedUriPermissions.firstOrNull { it.uri == tree && it.isReadPermission }
            ?: fail(WorkspaceFailure.Reason.PERMISSION)
        return grant.isWritePermission
    }
    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    private fun rootId(): String = DocumentsContract.getTreeDocumentId(tree)

    suspend fun stat(path: WorkspacePath): WorkspaceEntry = io { resolve(path) }
    suspend fun listDirectory(path: WorkspacePath): List<WorkspaceEntry> = io { children(resolve(path), 5000) }
    suspend fun readTextFile(path: WorkspacePath): TextSnapshot = io {
        val entry = resolve(path)
        WorkspaceText.snapshot(path, read(entry, WorkspaceText.MAX_BYTES), entry.modifiedTime)
    }

    suspend fun writeTextFile(path: WorkspacePath, content: String, expectedHash: String): TextSnapshot = io {
        mutations.withLock { write(path, content, expectedHash) }
    }
    suspend fun applyTextPatch(path: WorkspacePath, expectedHash: String, edits: List<TextEdit>): TextSnapshot = io {
        mutations.withLock {
            val entry = resolve(path)
            val bytes = read(entry, WorkspaceText.MAX_BYTES)
            WorkspaceText.requireHash(bytes, expectedHash)
            val replacement = WorkspaceText.patch(WorkspaceText.decode(bytes), edits)
            write(path, replacement, expectedHash)
        }
    }

    private suspend fun write(path: WorkspacePath, content: String, expectedHash: String): TextSnapshot {
        val bytes = WorkspaceText.encode(content)
        val entry = resolve(path)
        if (entry.directory || !entry.capabilities.write) fail(WorkspaceFailure.Reason.UNSUPPORTED)
        WorkspaceText.requireHash(read(entry, WorkspaceText.MAX_BYTES), expectedHash)
        currentCoroutineContext().ensureActive()
        // SAF has no portable compare-and-swap or atomic replace. Do not interrupt a commit after truncation.
        return withContext(NonCancellable) {
            try {
                val descriptor = resolver.openFileDescriptor(uri(entry.documentId), "rwt")
                    ?: fail(WorkspaceFailure.Reason.WRITE)
                ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { it.write(bytes); it.flush() }
                val updated = resolve(path)
                val actual = read(updated, WorkspaceText.MAX_BYTES)
                if (!actual.contentEquals(bytes)) fail(WorkspaceFailure.Reason.WRITE)
                WorkspaceText.snapshot(path, actual, updated.modifiedTime)
            } catch (e: Exception) {
                throw WorkspaceFailure(WorkspaceFailure.Reason.WRITE)
            }
        }
    }

    suspend fun createFile(path: WorkspacePath): WorkspaceEntry = create(path, "text/plain")
    suspend fun createDirectory(path: WorkspacePath): WorkspaceEntry = create(path, Document.MIME_TYPE_DIR)

    private suspend fun create(path: WorkspacePath, mime: String): WorkspaceEntry = io {
        mutations.withLock {
            requireNonRoot(path)
            val parent = resolve(path.parent())
            if (!parent.directory) fail(WorkspaceFailure.Reason.NOT_DIRECTORY)
            if (!parent.capabilities.create) fail(WorkspaceFailure.Reason.UNSUPPORTED)
            absent(parent, path.name)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val created = DocumentsContract.createDocument(resolver, uri(parent.documentId), mime, path.name)
                    ?: fail(WorkspaceFailure.Reason.PROVIDER)
                returnedChild(parent, created)
            }
        }
    }

    suspend fun delete(path: WorkspacePath) = io {
        mutations.withLock {
            requireNonRoot(path)
            val entry = resolve(path)
            if (!entry.capabilities.delete) fail(WorkspaceFailure.Reason.UNSUPPORTED)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                if (!DocumentsContract.deleteDocument(resolver, uri(entry.documentId))) fail(WorkspaceFailure.Reason.PROVIDER)
                if (children(resolve(path.parent()), 5000).any { it.documentId == entry.documentId }) {
                    fail(WorkspaceFailure.Reason.PROVIDER)
                }
            }
        }
    }

    suspend fun rename(path: WorkspacePath, newName: String): WorkspaceEntry = io {
        mutations.withLock {
            requireNonRoot(path)
            WorkspacePath.validateName(newName)
            val entry = resolve(path)
            if (!entry.capabilities.rename) fail(WorkspaceFailure.Reason.UNSUPPORTED)
            val parent = resolve(path.parent())
            absent(parent, newName)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val renamed = DocumentsContract.renameDocument(resolver, uri(entry.documentId), newName)
                    ?: fail(WorkspaceFailure.Reason.PROVIDER)
                returnedChild(parent, renamed)
            }
        }
    }

    // Destination is an existing directory; the provider preserves the source name.
    suspend fun move(source: WorkspacePath, destination: WorkspacePath): WorkspaceEntry = io {
        mutations.withLock {
            requireNonRoot(source)
            if (destination.isWithin(source) || destination == source.parent()) fail(WorkspaceFailure.Reason.INVALID_PATH)
            val entry = resolve(source)
            val target = resolve(destination)
            if (!target.directory) fail(WorkspaceFailure.Reason.NOT_DIRECTORY)
            if (!entry.capabilities.move || !target.capabilities.create) fail(WorkspaceFailure.Reason.UNSUPPORTED)
            val parent = resolve(source.parent())
            // Reject provider aliases as well as lexical descendants.
            for (depth in 0..destination.segments.size) {
                val ancestor = WorkspacePath.parse(destination.segments.take(depth).joinToString("/"))
                if (resolve(ancestor).documentId == entry.documentId) fail(WorkspaceFailure.Reason.INVALID_PATH)
            }
            absent(target, source.name)
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val moved = DocumentsContract.moveDocument(resolver, uri(entry.documentId), uri(parent.documentId), uri(target.documentId))
                    ?: fail(WorkspaceFailure.Reason.PROVIDER)
                val confirmed = returnedChild(target, moved)
                if (children(parent, 5000).any { it.documentId == entry.documentId }) fail(WorkspaceFailure.Reason.PROVIDER)
                confirmed
            }
        }
    }

    suspend fun search(query: String, startPath: WorkspacePath = WorkspacePath.ROOT,
        limits: SearchLimits = SearchLimits()): SearchReport = io {
        searchWorkspace(query, startPath, limits,
            { path, cap -> children(resolve(path), cap) },
            { entry, cap -> read(entry, cap) })
    }

    private suspend fun absent(parent: WorkspaceEntry, name: String) {
        if (children(parent, 5000).any { it.path.name == name }) fail(WorkspaceFailure.Reason.DUPLICATE)
    }
    private suspend fun returnedChild(parent: WorkspaceEntry, returned: Uri): WorkspaceEntry {
        if (returned.authority != tree.authority) fail(WorkspaceFailure.Reason.PROVIDER)
        val id = DocumentsContract.getDocumentId(returned)
        return children(parent, 5000).singleOrNull { it.documentId == id }
            ?: fail(WorkspaceFailure.Reason.PROVIDER)
    }

    private suspend fun resolve(path: WorkspacePath): WorkspaceEntry {
        val root = query(uri(rootId()), WorkspacePath.ROOT, false, 1).singleOrNull()
            ?: fail(WorkspaceFailure.Reason.MISSING)
        var entry = root
        val seen = mutableSetOf(root.documentId)
        for (segment in path.segments) {
            val matches = children(entry, 5000).filter { it.path.name == segment }
            if (matches.isEmpty()) fail(WorkspaceFailure.Reason.MISSING)
            if (matches.size != 1) fail(WorkspaceFailure.Reason.DUPLICATE)
            entry = matches.single()
            if (!seen.add(entry.documentId)) fail(WorkspaceFailure.Reason.INVALID_PATH)
        }
        return entry
    }
    private suspend fun children(parent: WorkspaceEntry, cap: Int): List<WorkspaceEntry> {
        if (!parent.directory) fail(WorkspaceFailure.Reason.NOT_DIRECTORY)
        return query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent.documentId), parent.path, true, cap)
            .sortedWith(WorkspaceEntry.ORDER)
    }

    private suspend fun query(target: Uri, path: WorkspacePath, childQuery: Boolean, cap: Int): List<WorkspaceEntry> {
        val writable = requireGrant()
        val context = currentCoroutineContext()
        return signalled { signal ->
            resolver.query(target, projection, null, null, null, signal)?.use { cursor ->
                val entries = mutableListOf<WorkspaceEntry>()
                while (cursor.moveToNext()) {
                    context.ensureActive()
                    if (entries.size >= cap) fail(WorkspaceFailure.Reason.LIMIT)
                    val id = cursor.getString(0) ?: fail(WorkspaceFailure.Reason.PROVIDER)
                    val name = cursor.getString(1) ?: fail(WorkspaceFailure.Reason.PROVIDER)
                    val mime = cursor.getString(2) ?: "application/octet-stream"
                    val flags = if (cursor.isNull(5)) 0 else cursor.getInt(5)
                    fun flag(value: Int) = writable && flags and value != 0
                    val entryPath = if (childQuery) path.child(name) else path
                    val directory = mime == Document.MIME_TYPE_DIR
                    entries.add(WorkspaceEntry(entryPath, id, directory, mime,
                        if (cursor.isNull(3)) null else cursor.getLong(3).takeIf { it >= 0 },
                        if (cursor.isNull(4)) null else cursor.getLong(4).takeIf { it > 0 },
                        WorkspaceCapabilities(
                            create = directory && flag(Document.FLAG_DIR_SUPPORTS_CREATE),
                            write = !directory && flag(Document.FLAG_SUPPORTS_WRITE),
                            delete = !entryPath.isRoot && flag(Document.FLAG_SUPPORTS_DELETE),
                            rename = !entryPath.isRoot && flag(Document.FLAG_SUPPORTS_RENAME),
                            move = !entryPath.isRoot && flag(Document.FLAG_SUPPORTS_MOVE))))
                }
                entries
            } ?: fail(WorkspaceFailure.Reason.PROVIDER)
        }
    }

    private suspend fun read(entry: WorkspaceEntry, limit: Int): ByteArray {
        requireGrant()
        if (entry.directory) fail(WorkspaceFailure.Reason.BINARY)
        if (entry.size != null && entry.size > limit) fail(WorkspaceFailure.Reason.TOO_LARGE)
        val context = currentCoroutineContext()
        return signalled { signal ->
            val descriptor = resolver.openFileDescriptor(uri(entry.documentId), "r", signal)
                ?: fail(WorkspaceFailure.Reason.PROVIDER)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                WorkspaceText.readBounded(input, limit) { context.ensureActive() }
            }
        }
    }

    private suspend fun <T> signalled(block: (CancellationSignal) -> T): T = coroutineScope {
        val signal = CancellationSignal()
        val cancellation = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { signal.cancel() }
        }
        try { block(signal) } finally { cancellation.cancel() }
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        try {
            requireGrant()
            block()
        } catch (e: CancellationException) { throw e
        } catch (e: WorkspaceFailure) { throw e
        } catch (_: SecurityException) { fail(WorkspaceFailure.Reason.PERMISSION)
        } catch (_: FileNotFoundException) { fail(WorkspaceFailure.Reason.MISSING)
        } catch (_: UnsupportedOperationException) { fail(WorkspaceFailure.Reason.UNSUPPORTED)
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            fail(WorkspaceFailure.Reason.PROVIDER)
        }
    }

    private fun requireNonRoot(path: WorkspacePath) {
        if (path.isRoot) fail(WorkspaceFailure.Reason.ROOT)
    }

    private fun fail(reason: WorkspaceFailure.Reason): Nothing = throw WorkspaceFailure(reason)
}
