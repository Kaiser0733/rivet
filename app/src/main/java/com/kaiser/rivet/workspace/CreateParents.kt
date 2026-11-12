package com.kaiser.rivet.workspace

/** Parent directories a create must make, shallowest first. */
object CreateParents {
    fun missing(path: WorkspacePath, exists: (WorkspacePath) -> Boolean): List<WorkspacePath> {
        val result = mutableListOf<WorkspacePath>()
        var parent = WorkspacePath.ROOT
        for (segment in path.segments.dropLast(1)) {
            parent = parent.child(segment)
            if (!exists(parent)) result += parent
        }
        return result
    }
}
