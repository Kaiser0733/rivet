package com.kaiser.rivet.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillCatalogCacheTest {
    @Test
    fun nullWorkspaceCacheDoesNotPoisonSubsequentWorkspaceLookupUnderSameId() {
        val cache = SkillCatalogCache<String>()
        cache.put(workspaceId = "ws-1", hasWorkspace = false, value = "global-only")

        assertEquals("global-only", cache.get(workspaceId = "ws-1", hasWorkspace = false))
        assertNull(cache.get(workspaceId = "ws-1", hasWorkspace = true))

        cache.put(workspaceId = "ws-1", hasWorkspace = true, value = "project+global")
        assertEquals("project+global", cache.get(workspaceId = "ws-1", hasWorkspace = true))
        assertNull(cache.get(workspaceId = "ws-1", hasWorkspace = false))
    }
}
