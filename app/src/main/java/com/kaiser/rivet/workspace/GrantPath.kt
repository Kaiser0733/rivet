package com.kaiser.rivet.workspace

data class GrantPath(val grantId: String?, val path: WorkspacePath, val readOnly: Boolean = false) {
    val prefix: String? get() = if (readOnly) "inbox" else grantId?.let { "grants/$it" }

    fun qualify(relative: String): String =
        prefix?.let { if (relative.isEmpty()) it else "$it/$relative" } ?: relative

    companion object {
        fun isExternal(path: String): Boolean = path.substringBefore('/') in setOf("grants", "inbox")

        fun parse(toolPath: String): GrantPath {
            val parsed = WorkspacePath.parse(toolPath)
            return when (parsed.segments.firstOrNull()) {
                "grants" -> {
                    val id = parsed.segments.getOrNull(1) ?: throw WorkspaceFailure(WorkspaceFailure.Reason.INVALID_PATH)
                    GrantPath(id, WorkspacePath.parse(parsed.segments.drop(2).joinToString("/")))
                }
                "inbox" -> GrantPath("inbox", WorkspacePath.parse(parsed.segments.drop(1).joinToString("/")), readOnly = true)
                else -> GrantPath(null, parsed)
            }
        }
    }
}
