package com.kaiser.rivet.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Screenshots are captured as WebP for the UI. Vision providers receive a transient
 * JPEG: it is broadly accepted by OpenAI-compatible gateways and stays under the
 * provider request budget without ever entering the persisted transcript.
 */
internal object ImageAttachmentEncoder {
    const val MAX_EDGE = 1024
    const val RETRY_EDGE = 768
    const val MAX_RAW_BYTES = 256 * 1024
    private const val QUALITY = 78
    private const val RETRY_QUALITY = 60

    fun encodeForWire(path: String): EncodedImage? {
        val file = File(path)
        if (!file.isFile) return null
        val source = try {
            BitmapFactory.decodeFile(path)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        return try {
            val first = jpeg(source, MAX_EDGE, QUALITY)
            val bytes = when {
                first == null -> null
                first.size <= MAX_RAW_BYTES -> first
                else -> jpeg(source, RETRY_EDGE, RETRY_QUALITY)?.takeIf { it.size <= MAX_RAW_BYTES }
            } ?: return null
            EncodedImage("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
        } finally {
            source.recycle()
        }
    }

    private fun jpeg(source: Bitmap, maxEdge: Int, quality: Int): ByteArray? {
        val longest = maxOf(source.width, source.height)
        val scaled = if (longest <= maxEdge) source else {
            val ratio = maxEdge.toFloat() / longest
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).toInt().coerceAtLeast(1),
                (source.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        }
        return try {
            ByteArrayOutputStream().use { out ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) null else out.toByteArray()
            }
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}
