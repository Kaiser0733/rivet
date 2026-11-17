package com.kaiser.rivet.runtime

import com.kaiser.rivet.workspace.WorkspacePath

internal fun previewEntryUrl(baseUrl: String, root: WorkspacePath, entry: WorkspacePath): String {
    val relative = if (root.isRoot) entry.value
    else entry.value.removePrefix(root.value.trimEnd('/') + "/")
    return baseUrl.trimEnd('/') + "/" + relative
}
