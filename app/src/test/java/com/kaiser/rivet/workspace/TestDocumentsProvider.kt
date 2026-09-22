package com.kaiser.rivet.workspace

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import java.io.File

/** Disposable test documents only; no real user storage is accessed. */
class TestDocumentsProvider : DocumentsProvider() {
    data class Node(var name: String, var parent: String?, val directory: Boolean, val bytes: File)
    val nodes = linkedMapOf<String, Node>()
    var writable = true
    var rejectDelete = false
    var reportMetadata = true
    var childQueries = 0
    var createCalls = 0
    var normalizeTextFileNames = false
    var normalizeDirectoryNames = false
    var normalizeRenamedNames = false
    private var nextId = 0
    private val allFlags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or
        Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE or Document.FLAG_DIR_SUPPORTS_CREATE

    override fun onCreate(): Boolean {
        nodes["root"] = Node("project", null, true, File.createTempFile("workspace-root", ".test", context!!.cacheDir))
        return true
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: emptyArray())
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = rows(listOf(documentId), projection)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        childQueries++
        return rows(nodes.filterValues { it.parent == parentDocumentId }.keys.toList(), projection)
    }
    private fun rows(ids: List<String>, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)
        return MatrixCursor(columns).apply {
            ids.forEach { id -> nodes[id]?.let { node ->
                addRow(columns.map { column -> when (column) {
                    Document.COLUMN_DOCUMENT_ID -> id
                    Document.COLUMN_DISPLAY_NAME -> node.name
                    Document.COLUMN_MIME_TYPE -> if (node.directory) Document.MIME_TYPE_DIR else "text/plain"
                    Document.COLUMN_FLAGS -> if (writable) allFlags else 0
                    Document.COLUMN_SIZE -> if (reportMetadata) node.bytes.length() else null
                    Document.COLUMN_LAST_MODIFIED -> if (reportMetadata) node.bytes.lastModified() else null
                    else -> null
                } }.toTypedArray())
            } }
        }
    }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val node = nodes[documentId] ?: throw java.io.FileNotFoundException()
        return ParcelFileDescriptor.open(node.bytes, ParcelFileDescriptor.parseMode(mode))
    }
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        var parent = nodes[documentId]?.parent
        while (parent != null) {
            if (parent == parentDocumentId) return true
            parent = nodes[parent]?.parent
        }
        return false
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        check(writable)
        createCalls++
        val id = "doc-${++nextId}"
        val actualName = when {
            normalizeTextFileNames && mimeType == "text/plain" -> "$displayName.txt"
            normalizeDirectoryNames && mimeType == Document.MIME_TYPE_DIR -> "$displayName.folder"
            else -> displayName
        }
        nodes[id] = Node(actualName, parentDocumentId, mimeType == Document.MIME_TYPE_DIR,
            File.createTempFile("workspace-document", ".test", context!!.cacheDir))
        return id
    }
    override fun deleteDocument(documentId: String) {
        if (rejectDelete) throw java.io.FileNotFoundException("rejected")
        nodes.filter { it.key == documentId || isChildDocument(documentId, it.key) }.keys.toList().forEach {
            nodes.remove(it)?.bytes?.delete()
        }
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        val node = nodes.remove(documentId)!!
        node.name = if (normalizeRenamedNames) "$displayName.txt" else displayName
        val id = "doc-${++nextId}"
        nodes[id] = node
        nodes.values.filter { it.parent == documentId }.forEach { it.parent = id }
        return id
    }
    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        nodes[sourceDocumentId]!!.parent = targetParentDocumentId
        return sourceDocumentId
    }
}
