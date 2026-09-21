package com.kaiser.rivet.workspace

@JvmInline
value class WorkspacePath private constructor(val value: String) {
    val isRoot: Boolean get() = value.isEmpty()
    val name: String get() = value.substringAfterLast('/')
    val segments: List<String> get() = if (isRoot) emptyList() else value.split('/')
    fun parent(): WorkspacePath = if ('/' in value) parse(value.substringBeforeLast('/')) else ROOT
    fun child(name: String): WorkspacePath {
        validateName(name)
        return parse(if (isRoot) name else "$value/$name")
    }
    fun isWithin(parent: WorkspacePath): Boolean =
        parent.isRoot || this == parent || value.startsWith(parent.value + "/")

    companion object {
        val ROOT = WorkspacePath("")
        fun parse(value: String): WorkspacePath {
            if (value.isEmpty()) return ROOT
            if (value.length > 4096 || value.split('/').size > 64) invalid()
            value.split('/').forEach(::validateName)
            return WorkspacePath(value)
        }
        fun validateName(name: String) {
            if (name.isEmpty() || name == "." || name == ".." || name.length > 255 ||
                name.any { it == '/' || it == '\\' || it.isISOControl() }) invalid()
        }
        private fun invalid(): Nothing = throw WorkspaceFailure(WorkspaceFailure.Reason.INVALID_PATH)
    }
}
