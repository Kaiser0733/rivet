package com.kaiser.rivet.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GrantRegistryTest {
    @Test fun addsListsRevokesAndRemovesWithProviderPermissions() = runBlocking {
        val prefs = mutableMapOf<String, FolderGrant>()
        val permissions = mutableMapOf<String, FolderAccess>()
        val released = mutableListOf<String>()
        val registry = GrantedFolders(prefs, { permissions.toMap() },
            take = { uri, requested -> permissions[uri] = requested },
            verify = { "Downloads" },
            release = { uri, _ -> released += uri; permissions.remove(uri); Unit },
        )
        val first = registry.add("tree:one", FolderAccess(read = true, write = true))
        val second = registry.add("tree:two", FolderAccess(read = true, write = false))
        assertEquals("Downloads", first.id)
        assertEquals("Downloads (2)", second.id)
        assertFalse(registry.list().last().write)
        permissions.remove(first.treeUri)
        assertTrue(registry.list().first().revoked)
        registry.remove(first.id)
        registry.remove(second.id)
        assertEquals(listOf(second.treeUri), released)
        assertTrue(prefs.isEmpty())
    }

    @Test fun failedVerificationReleasesNewGrantAndPreservesSharedProjectGrant() = runBlocking {
        val permissions = mutableMapOf("tree:project" to FolderAccess(true, false))
        val released = mutableListOf<Pair<String, FolderAccess>>()
        val registry = GrantedFolders(mutableMapOf(), { permissions.toMap() },
            take = { uri, requested -> permissions[uri] = requested },
            verify = { uri -> if (uri == "tree:project") "Project" else throw WorkspaceFailure(WorkspaceFailure.Reason.NOT_DIRECTORY) },
            release = { uri, access -> released += uri to access; Unit },
            projectTree = { "tree:project" },
        )
        assertEquals(WorkspaceFailure.Reason.NOT_DIRECTORY,
            (runCatching { registry.add("tree:file", FolderAccess(true, false)) }.exceptionOrNull() as WorkspaceFailure).reason)
        val project = registry.add("tree:project", FolderAccess(true, false))
        registry.remove(project.id)
        assertEquals(listOf("tree:file" to FolderAccess(true, false)), released)
    }
}
