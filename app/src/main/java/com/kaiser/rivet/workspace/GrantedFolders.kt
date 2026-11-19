package com.kaiser.rivet.workspace

data class FolderAccess(val read: Boolean, val write: Boolean)
data class FolderGrant(val id: String, val treeUri: String, val label: String, val read: Boolean, val write: Boolean) {
    val revoked: Boolean get() = !read
}

class GrantedFolders(
    private val entries: MutableMap<String, FolderGrant>,
    private val persisted: () -> Map<String, FolderAccess>,
    private val take: (String, FolderAccess) -> Unit,
    private val verify: suspend (String) -> String,
    private val release: (String, FolderAccess) -> Unit,
    private val projectTree: () -> String? = { null },
    private val save: (Map<String, FolderGrant>) -> Unit = {},
) {
    fun list(): List<FolderGrant> {
        val permissions = persisted()
        return entries.values.map { entry ->
            val live = permissions[entry.treeUri]
            val read = entry.read && live?.read == true
            entry.copy(read = read, write = read && entry.write && live?.write == true)
        }
    }

    suspend fun add(uri: String, requested: FolderAccess): FolderGrant {
        if (!requested.read) throw WorkspaceFailure(WorkspaceFailure.Reason.PERMISSION)
        val previous = persisted()[uri]
        take(uri, requested)
        try {
            val label = verify(uri).replace(Regex("[/\\\\\\p{Cntrl}]"), "_").trim().take(100).trimEnd('.')
                .ifBlank { "Folder" }
            val existing = entries.values.firstOrNull { it.treeUri == uri }
            val id = existing?.id ?: generateSequence(1) { it + 1 }
                .map { if (it == 1) label else "$label ($it)" }.first { it !in entries }
            val live = persisted()[uri]
            if (live?.read != true) throw WorkspaceFailure(WorkspaceFailure.Reason.PERMISSION)
            val grant = FolderGrant(id, uri, existing?.label ?: label, true, live.write && requested.write)
            save(entries + (id to grant))
            entries[id] = grant
            return grant
        } catch (e: Exception) {
            val acquired = FolderAccess(requested.read && previous?.read != true, requested.write && previous?.write != true)
            if (acquired.read || acquired.write) runCatching { release(uri, acquired) }
            throw e
        }
    }

    fun remove(id: String) {
        val grant = entries[id] ?: return
        save(entries - id)
        entries.remove(id)
        if (grant.treeUri != projectTree()) {
            persisted()[grant.treeUri]?.let { release(grant.treeUri, it) }
        }
    }
}
