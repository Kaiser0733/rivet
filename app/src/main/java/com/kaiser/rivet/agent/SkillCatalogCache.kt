package com.kaiser.rivet.agent

internal data class SkillCatalogCacheKey(
    val workspaceId: String,
    val hasWorkspace: Boolean,
)

internal class SkillCatalogCache<T> {
    private var entry: Pair<SkillCatalogCacheKey, T>? = null

    fun get(workspaceId: String, hasWorkspace: Boolean): T? {
        val (key, value) = entry ?: return null
        return if (key.workspaceId == workspaceId && key.hasWorkspace == hasWorkspace) value else null
    }

    fun put(workspaceId: String, hasWorkspace: Boolean, value: T) {
        entry = SkillCatalogCacheKey(workspaceId, hasWorkspace) to value
    }
}
