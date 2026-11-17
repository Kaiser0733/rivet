package com.kaiser.rivet.provider

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ImageAttachmentEncoderTest {

    @Test fun downscalesToTheLongEdgeAndEncodesJpeg() {
        val file = writePng(width = 1600, height = 1200)
        val encoded = ImageAttachmentEncoder.encodeForWire(file.absolutePath)!!
        assertEquals("image/jpeg", encoded.mime)
        val bytes = Base64.decode(encoded.base64, Base64.NO_WRAP)
        assertTrue(bytes.size <= ImageAttachmentEncoder.MAX_RAW_BYTES)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        try {
            assertTrue(maxOf(bitmap.width, bitmap.height) <= ImageAttachmentEncoder.MAX_EDGE)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun noisyImageStaysWithinTheEncodedBounds() {
        val file = writeNoise(width = 2000, height = 1500)
        val encoded = ImageAttachmentEncoder.encodeForWire(file.absolutePath)!!
        val bytes = Base64.decode(encoded.base64, Base64.NO_WRAP)
        assertTrue(bytes.size <= ImageAttachmentEncoder.MAX_RAW_BYTES)
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        try {
            assertTrue(maxOf(bitmap.width, bitmap.height) <= ImageAttachmentEncoder.MAX_EDGE)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun aDeletedScreenshotReturnsNullInsteadOfThrowing() {
        assertNull(ImageAttachmentEncoder.encodeForWire("/tmp/does-not-exist-${System.nanoTime()}.webp"))
    }

    private fun writePng(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(32, 96, 160))
        return writeWebp(bitmap)
    }

    private fun writeNoise(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        var seed = 0x12345678
        for (i in pixels.indices) {
            seed = seed * 1664525 + 1013904223
            pixels[i] = 0xff000000.toInt() or (seed and 0x00ffffff)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return writeWebp(bitmap)
    }

    private fun writeWebp(bitmap: Bitmap): File {
        val file = File.createTempFile("vision-shot-", ".webp")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP, 90, out)
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}
