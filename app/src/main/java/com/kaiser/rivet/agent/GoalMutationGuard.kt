package com.kaiser.rivet.agent

/**
 * Guard preventing agent mutations from modifying, moving, renaming,
 * or deleting the user-owned `.rivet/goal.md` document (or its `.rivet` parent).
 */
internal object GoalMutationGuard {
    const val GOAL_PATH = ".rivet/goal.md"
    private const val RIVET_DIR = ".rivet"

    private val MUTATION_TOOLS = setOf(
        "write_file",
        "apply_patch",
        "create_file",
        "create_directory",
        "delete_path",
        "rename_path",
        "move_path",
        "download_file",
    )

    private val ANCESTOR_PROTECTED_TOOLS = setOf(
        "delete_path",
        "rename_path",
        "move_path",
    )

    private fun normalize(raw: String?): String? {
        if (raw == null) return null
        val parts = raw.replace('\\', '/').trim()
            .split('/')
            .filter { it.isNotEmpty() && it != "." }
        return parts.joinToString("/")
    }

    fun isReservedMutation(
        toolName: String,
        path: String?,
        newName: String? = null,
        destination: String? = null,
    ): Boolean {
        if (toolName !in MUTATION_TOOLS) return false
        val normPath = normalize(path)
        val normNewName = normalize(newName)
        val normDest = normalize(destination)
        if (normPath == GOAL_PATH || normNewName == GOAL_PATH || normDest == GOAL_PATH) {
            return true
        }
        if (toolName in ANCESTOR_PROTECTED_TOOLS && normPath == RIVET_DIR) {
            return true
        }
        if (toolName == "rename_path" && normPath != null && normNewName != null) {
            val parent = if ('/' in normPath) normPath.substringBeforeLast('/') else ""
            val renamedTarget = if (parent.isEmpty()) normNewName else "$parent/$normNewName"
            if (renamedTarget == GOAL_PATH || renamedTarget == RIVET_DIR) return true
        }
        if (toolName == "move_path" && normPath != null && normDest != null) {
            val baseName = normPath.substringAfterLast('/')
            val movedTarget = if (normDest.isEmpty()) baseName else "$normDest/$baseName"
            if (movedTarget == GOAL_PATH || movedTarget == RIVET_DIR) return true
        }
        return false
    }
}
