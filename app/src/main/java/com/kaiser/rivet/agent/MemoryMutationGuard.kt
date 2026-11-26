package com.kaiser.rivet.agent

internal object MemoryMutationGuard {
    private const val MEMORY_PATH = MemoryFormat.PATH
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

    fun isReservedMemoryMutation(
        toolName: String,
        path: String?,
        newName: String? = null,
        destination: String? = null,
    ): Boolean {
        if (toolName !in MUTATION_TOOLS) return false
        val normPath = normalize(path)
        val normNewName = normalize(newName)
        val normDest = normalize(destination)
        if (normPath == MEMORY_PATH || normNewName == MEMORY_PATH || normDest == MEMORY_PATH) {
            return true
        }
        if (toolName in ANCESTOR_PROTECTED_TOOLS && normPath == RIVET_DIR) return true
        if (toolName == "rename_path" && normPath != null && normNewName != null) {
            val parent = if ('/' in normPath) normPath.substringBeforeLast('/') else ""
            val renamedTarget = if (parent.isEmpty()) normNewName else "$parent/$normNewName"
            if (renamedTarget == MEMORY_PATH || renamedTarget == RIVET_DIR) return true
        }
        if (toolName == "move_path" && normPath != null && normDest != null) {
            val baseName = normPath.substringAfterLast('/')
            val movedTarget = if (normDest.isEmpty()) baseName else "$normDest/$baseName"
            if (movedTarget == MEMORY_PATH || movedTarget == RIVET_DIR) return true
        }
        return false
    }
}
