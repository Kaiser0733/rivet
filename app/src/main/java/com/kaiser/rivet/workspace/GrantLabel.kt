package com.kaiser.rivet.workspace

/**
 * The folder path the user picked, read from the SAF tree URI (e.g.
 * "Download/rivet-test"). The internal grant id stays internal; when the URI
 * carries no usable path (a volume root) the stored display label is used.
 */
internal fun grantDisplayPath(treeUri: String, label: String): String {
    val document = treeUri.substringAfter("/tree/", "").substringBefore("/document/")
    val decoded = runCatching { java.net.URLDecoder.decode(document, "UTF-8") }.getOrDefault(document)
    val path = decoded.substringAfter(':', "").trim('/')
    return path.ifBlank { label }
}
