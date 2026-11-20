package com.kaiser.rivet.replay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

internal class GifTooLarge : IOException("This GIF is over 8 MB. Try video.")

internal class GifWriter(
    private val width: Int,
    private val height: Int,
    maxBytes: Int = MAX_BYTES,
) {
    private val bytes: BoundedBuffer
    private var durationCs = 0
    private var finished = false

    init {
        require(width in 1..MAX_WIDTH && height in 1..MAX_HEIGHT)
        require(maxBytes in 1..MAX_BYTES)
        bytes = BoundedBuffer(maxBytes)
        bytes.write("GIF89a".encodeToByteArray())
        bytes.short(width)
        bytes.short(height)
        bytes.write(byteArrayOf(0xf7.toByte(), 0, 0))
        bytes.write(palette)
    }

    fun addFrame(indexedPixels: ByteArray, delayMs: Long) {
        check(!finished)
        require(indexedPixels.size == width * height)
        require(delayMs in 100L..60_000L)
        val delayCs = (delayMs / 10).toInt()
        require(delayCs <= 6000 - durationCs)
        bytes.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 4))
        bytes.short(delayCs)
        bytes.write(byteArrayOf(0, 0, 0x2c))
        bytes.short(0)
        bytes.short(0)
        bytes.short(width)
        bytes.short(height)
        bytes.write(0)
        bytes.write(8)
        compress(indexedPixels, LzwBlocks(bytes))
        durationCs += delayCs
    }

    fun writeTo(output: OutputStream) {
        check(durationCs > 0)
        if (!finished) {
            bytes.write(0x3b)
            finished = true
        }
        bytes.writeTo(output)
    }

    private fun compress(pixels: ByteArray, output: LzwBlocks) {
        val dictionary = HashMap<Int, Int>(4096)
        var next = 258
        var bits = 9
        var prefix = pixels[0].toInt() and 255
        output.code(256, bits)
        for (offset in 1 until pixels.size) {
            val value = pixels[offset].toInt() and 255
            val key = (prefix shl 8) or value
            val match = dictionary[key]
            if (match != null) {
                prefix = match
                continue
            }
            output.code(prefix, bits)
            // The decoder adds the previous dictionary entry after reading this code.
            if (bits < 12 && next == 1 shl bits) bits++
            if (next == 4096) {
                output.code(256, bits)
                dictionary.clear()
                next = 258
                bits = 9
            } else {
                dictionary[key] = next++
            }
            prefix = value
        }
        output.code(prefix, bits)
        if (bits < 12 && next == 1 shl bits) bits++
        output.code(257, bits)
        output.finish()
    }

    private class LzwBlocks(private val output: BoundedBuffer) {
        private val block = ByteArray(255)
        private var count = 0
        private var bitBuffer = 0
        private var bits = 0

        fun code(code: Int, width: Int) {
            bitBuffer = bitBuffer or (code shl bits)
            bits += width
            while (bits >= 8) {
                push(bitBuffer)
                bitBuffer = bitBuffer ushr 8
                bits -= 8
            }
        }

        fun finish() {
            if (bits > 0) push(bitBuffer)
            if (count > 0) flush()
            output.write(0)
        }

        private fun push(byte: Int) {
            block[count++] = byte.toByte()
            if (count == block.size) flush()
        }

        private fun flush() {
            output.write(count)
            output.write(block, 0, count)
            count = 0
        }
    }

    private class BoundedBuffer(private val limit: Int) : ByteArrayOutputStream() {
        private var exceeded = false

        fun short(value: Int) {
            write(value and 255)
            write(value ushr 8)
        }

        override fun write(b: Int) {
            reserve(1)
            super.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            reserve(len)
            super.write(b, off, len)
        }

        private fun reserve(length: Int) {
            if (exceeded || length > limit - count) {
                exceeded = true
                throw GifTooLarge()
            }
        }
    }

    companion object {
        const val MAX_WIDTH = 540
        const val MAX_HEIGHT = 960
        const val MAX_BYTES = 8_000_000

        private val palette = ByteArray(256 * 3).apply {
            for (index in 0 until 216) {
                this[index * 3] = (index / 36 * 51).toByte()
                this[index * 3 + 1] = (index / 6 % 6 * 51).toByte()
                this[index * 3 + 2] = (index % 6 * 51).toByte()
            }
            for (index in 0 until 40) {
                val gray = ((index * 255 + 19) / 39).toByte()
                for (channel in 0..2) this[(216 + index) * 3 + channel] = gray
            }
        }

        fun indexPixels(colors: IntArray): ByteArray = ByteArray(colors.size) { index ->
            val color = colors[index]
            val r = (color ushr 16) and 255
            val g = (color ushr 8) and 255
            val b = color and 255
            val cubeR = (r + 25) / 51
            val cubeG = (g + 25) / 51
            val cubeB = (b + 25) / 51
            val cube = cubeR * 36 + cubeG * 6 + cubeB
            val grayIndex = ((r + g + b) * 39 + 382) / 765
            val gray = (grayIndex * 255 + 19) / 39
            val cubeError = squared(r - cubeR * 51) + squared(g - cubeG * 51) + squared(b - cubeB * 51)
            val grayError = squared(r - gray) + squared(g - gray) + squared(b - gray)
            (if (grayError < cubeError) 216 + grayIndex else cube).toByte()
        }

        private fun squared(value: Int) = value * value
    }
}
