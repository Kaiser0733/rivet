package com.kaiser.rivet.workspace

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.kaiser.rivet.agent.AgentWorkspaceFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class AndroidGrantedFolders(context: Context) {
    private val app = context.applicationContext
    private val resolver get() = app.contentResolver
    private val prefs get() = app.getSharedPreferences("phone_grants", Context.MODE_PRIVATE)
    private val projectTree get() = app.getSharedPreferences("workspace", Context.MODE_PRIVATE).getString("tree", null)

    suspend fun list(): List<FolderGrant> = withContext(Dispatchers.IO) {
        permissionLock.withLock { registry().list() }
    }

    suspend fun add(uri: Uri, resultFlags: Int): FolderGrant = withContext(Dispatchers.IO) {
        if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri) ||
            resultFlags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION == 0) {
            throw WorkspaceFailure(WorkspaceFailure.Reason.PERMISSION)
        }
        val access = FolderAccess(resultFlags and READ != 0, resultFlags and WRITE != 0)
        permissionLock.withLock { registry().add(uri.toString(), access) }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        permissionLock.withLock { registry().remove(id) }
    }

    suspend fun resolve(id: String, write: Boolean = false, expectedTree: String? = null): SafWorkspace {
        val grant = list().firstOrNull { it.id == id } ?: throw AgentWorkspaceFailure("grant_missing")
        if (expectedTree != null && grant.treeUri != expectedTree) throw AgentWorkspaceFailure("grant_revoked")
        if (grant.revoked) throw AgentWorkspaceFailure("grant_revoked")
        if (write && !grant.write) throw AgentWorkspaceFailure("grant_read_only")
        return SafWorkspace(resolver, Uri.parse(grant.treeUri))
    }

    internal fun owns(tree: String): Boolean = stored().values.any { it.treeUri == tree }

    private fun stored(): MutableMap<String, FolderGrant> = prefs.all.mapNotNull { (id, value) ->
        runCatching {
            val entry = JSONObject(value as String)
            id to FolderGrant(id, entry.getString("treeUri"), entry.getString("label"),
                entry.getBoolean("read"), entry.getBoolean("write"))
        }.getOrNull()
    }.toMap(linkedMapOf())

    private fun registry() = GrantedFolders(stored(),
        persisted = { resolver.persistedUriPermissions.associate {
            it.uri.toString() to FolderAccess(it.isReadPermission, it.isWritePermission)
        } },
        take = { uri, access -> resolver.takePersistableUriPermission(Uri.parse(uri), flags(access)) },
        verify = { raw ->
            val uri = Uri.parse(raw)
            val workspace = SafWorkspace(resolver, uri)
            val root = workspace.stat(WorkspacePath.ROOT)
            if (!root.directory) throw WorkspaceFailure(WorkspaceFailure.Reason.NOT_DIRECTORY)
            workspace.displayName()
        },
        release = { uri, access ->
            try { resolver.releasePersistableUriPermission(Uri.parse(uri), flags(access)) }
            catch (_: SecurityException) { /* The provider already revoked access. */ }
        },
        projectTree = { projectTree },
        save = { grants ->
            val edit = prefs.edit().clear()
            grants.values.forEach { grant ->
                edit.putString(grant.id, JSONObject().put("treeUri", grant.treeUri).put("label", grant.label)
                    .put("read", grant.read).put("write", grant.write).toString())
            }
            if (!edit.commit()) throw WorkspaceFailure(WorkspaceFailure.Reason.PROVIDER)
        },
    )

    private fun flags(access: FolderAccess) = (if (access.read) READ else 0) or (if (access.write) WRITE else 0)

    companion object {
        internal val permissionLock = Mutex()
        private const val READ = Intent.FLAG_GRANT_READ_URI_PERMISSION
        private const val WRITE = Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        fun pickerIntent() = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            READ or WRITE or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
    }
}
