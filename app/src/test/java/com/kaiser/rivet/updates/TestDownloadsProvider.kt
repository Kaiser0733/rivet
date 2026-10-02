package com.kaiser.rivet.updates

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

/** Disposable MediaStore rows; only this provider's generated files are touched. */
class TestDownloadsProvider : ContentProvider() {
    data class Row(val values: ContentValues, val file: File)
    val rows = linkedMapOf<Long, Row>()
    var normalizeName = false
    var failWrite = false
    var queriesUnavailable = false
    private var nextId = 1L
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/vnd.android.package-archive"
    private fun id(uri: Uri) = uri.lastPathSegment?.toLongOrNull()
    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val copy = ContentValues(values!!)
        copy.put(MediaStore.Downloads.RELATIVE_PATH, "Download/")
        copy.put(MediaStore.Downloads.OWNER_PACKAGE_NAME, context!!.packageName)
        if (normalizeName) copy.put(MediaStore.Downloads.DISPLAY_NAME,
            copy.getAsString(MediaStore.Downloads.DISPLAY_NAME).removeSuffix(".apk") + " (1).apk")
        val id = nextId++
        rows[id] = Row(copy, File.createTempFile("update-download", ".test", context!!.cacheDir))
        return android.content.ContentUris.withAppendedId(uri, id)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (queriesUnavailable) return null
        val columns = projection ?: arrayOf(MediaStore.Downloads._ID)
        return MatrixCursor(columns).apply {
            rows.forEach { (rowId, row) ->
                if (id(uri) != null && id(uri) != rowId) return@forEach
                if (selection?.contains("LIKE") == true) {
                    if (row.values.getAsString(MediaStore.Downloads.OWNER_PACKAGE_NAME) != selectionArgs!![0] ||
                        row.values.getAsInteger(MediaStore.Downloads.IS_PENDING) != 1 ||
                        !row.values.getAsString(MediaStore.Downloads.DISPLAY_NAME).startsWith("Rivet-v")) return@forEach
                } else if (selectionArgs != null && selectionArgs.isNotEmpty()) {
                    if (row.values.getAsString(MediaStore.Downloads.DISPLAY_NAME) != selectionArgs[0] ||
                        row.values.getAsString(MediaStore.Downloads.RELATIVE_PATH) != selectionArgs[1]) return@forEach
                }
                addRow(columns.map { if (it == MediaStore.Downloads._ID) rowId else row.values[it] }.toTypedArray())
            }
        }
    }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val row = rows[id(uri)] ?: return 0
        row.values.putAll(values!!)
        return 1
    }
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val row = rows.remove(id(uri)) ?: return 0
        row.file.delete()
        return 1
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failWrite) throw java.io.FileNotFoundException("Test write refused")
        return ParcelFileDescriptor.open(rows[id(uri)]!!.file, ParcelFileDescriptor.parseMode(mode))
    }
}
