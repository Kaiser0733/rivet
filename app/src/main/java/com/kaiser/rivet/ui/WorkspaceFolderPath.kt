package com.kaiser.rivet.ui

import android.net.Uri
import android.provider.DocumentsContract

internal fun workspaceFolderPath(identity: String?): String? {
    val uri = identity?.let(Uri::parse) ?: return null
    val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return identity
    if (uri.authority == "com.android.externalstorage.documents") {
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (relative.isEmpty()) root else "$root/$relative"
    }
    return identity
}
