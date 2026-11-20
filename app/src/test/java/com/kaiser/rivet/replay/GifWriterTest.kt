package com.kaiser.rivet.replay

import java.io.ByteArrayOutputStream
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GifWriterTest {
    @Test fun writesGif89aAndStablePalette() {
        val first = encode(1, 1, byteArrayOf(0))
        val second = encode(1, 1, byteArrayOf(255.toByte()))
        assertEquals("GIF89a", first.copyOfRange(0, 6).decodeToString())
        assertEquals(0x3b.toByte(), first.last())
        val palette = first.copyOfRange(13, 13 + 256 * 3)
        assertArrayEquals(palette, second.copyOfRange(13, 13 + 256 * 3))
        assertArrayEquals(byteArrayOf(0, 0, 0), palette.copyOfRange(0, 3))
        assertArrayEquals(byteArrayOf(-1, -1, -1), palette.takeLast(3).toByteArray())
        assertArrayEquals(
            GifWriter.indexPixels(intArrayOf(0xff151517.toInt(), 0xff7aaaff.toInt())),
            GifWriter.indexPixels(intArrayOf(0xff151517.toInt(), 0xff7aaaff.toInt())),
        )
    }

    @Test fun lzwHandlesRunsCodeWidthChangesAndDictionaryResets() {
        val pixels = Random(917).nextBytes(240 * 128).also { it.fill(92, 0, 4096) }
        val bytes = encode(240, 128, pixels)
        val image = 13 + 768 + 8
        assertEquals(0x2c.toByte(), bytes[image])
        var offset = image + 10
        assertEquals(8.toByte(), bytes[offset++])
        val compressed = ByteArrayOutputStream()
        while (true) {
            val count = bytes[offset++].toInt() and 255
            if (count == 0) break
            compressed.write(bytes, offset, count)
            offset += count
        }
        assertArrayEquals(pixels, decodeLzw(compressed.toByteArray()))
    }

    @Test fun refusesCapsBeforeWritingToTheDestination() {
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { GifWriter(541, 960) }
        assertThrows(IllegalArgumentException::class.java) { GifWriter(540, 961) }
        val duration = GifWriter(1, 1)
        assertThrows(IllegalArgumentException::class.java) { duration.addFrame(byteArrayOf(0), 90) }
        duration.addFrame(byteArrayOf(0), 59_900)
        assertThrows(IllegalArgumentException::class.java) { duration.addFrame(byteArrayOf(0), 110) }
        assertThrows(GifTooLarge::class.java) {
            GifWriter(64, 64, maxBytes = 900).apply {
                addFrame(Random(5).nextBytes(4096), 30_000)
                writeTo(output)
            }
        }
        assertEquals(8_000_000, GifWriter.MAX_BYTES)
        assertThrows(IllegalArgumentException::class.java) { GifWriter(1, 1, GifWriter.MAX_BYTES + 1) }
        assertEquals(0, output.size())
    }

    private fun encode(width: Int, height: Int, pixels: ByteArray): ByteArray =
        ByteArrayOutputStream().also { dst ->
            GifWriter(width, height).apply {
                addFrame(pixels, 30_000)
                writeTo(dst)
            }
        }.toByteArray()

    private fun decodeLzw(input: ByteArray): ByteArray {
        var bit = 0
        var width = 9
        var table = MutableList(258) { if (it < 256) byteArrayOf(it.toByte()) else byteArrayOf() }
        var previous: ByteArray? = null
        val out = ByteArrayOutputStream()
        while (true) {
            var code = 0
            repeat(width) { shift ->
                code = code or (((input[bit / 8].toInt() ushr (bit % 8)) and 1) shl shift)
                bit++
            }
            if (code == 256) {
                table = MutableList(258) { if (it < 256) byteArrayOf(it.toByte()) else byteArrayOf() }
                width = 9
                previous = null
                continue
            }
            if (code == 257) return out.toByteArray()
            val entry = if (code == table.size) previous!! + previous.first() else table[code]
            out.write(entry)
            if (previous != null && table.size < 4096) {
                table.add(previous + entry.first())
                if (width < 12 && table.size == 1 shl width) width++
            }
            previous = entry
        }
    }
}
