package com.kaiser.rivet.local

import java.io.File
import java.io.RandomAccessFile

/** Pure local ceiling rules, kept separate from Android so they stay testable. */
internal object LocalLimitPolicy {
    fun effectiveContext(deviceBudget: Int, trainingContext: Int?): Int =
        minOf(deviceBudget.coerceAtLeast(512), trainingContext?.takeIf { it > 0 } ?: Int.MAX_VALUE)

    fun effectiveOutput(context: Int): Int = (context / 4).coerceIn(256, 1_024)
}

/** The training context a GGUF declares, not the runtime KV size. */
data class GgufTrainingContext(
    val tokens: Int,
    val architecture: String?,
    val source: String,
)

/**
 * Minimal GGUF metadata reader. It reads only the key/value header and skips
 * tensors and large arrays without materialising them. If the header is
 * truncated, malformed, or the context key is beyond the read window, it
 * returns null and the caller keeps the device budget instead of guessing.
 */
internal object GgufMetadata {
    private const val MAGIC = "GGUF"
    private const val MAX_HEADER_BYTES = 32L * 1024 * 1024
    private const val MAX_STRING_BYTES = 4 * 1024 * 1024
    private const val MAX_ARRAY_ITEMS = 20_000_000L
    private const val MAX_KV = 200_000L

    fun read(file: File): GgufTrainingContext? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val size = minOf(raf.length(), MAX_HEADER_BYTES).toInt()
            val bytes = ByteArray(size)
            raf.readFully(bytes)
            parse(bytes)
        }
    }.getOrNull()

    fun parse(bytes: ByteArray): GgufTrainingContext? = runCatching {
        val reader = Reader(bytes)
        if (reader.rawString(4) != MAGIC) return null
        val version = reader.u32()
        if (version !in 2..3) return null
        reader.u64() // tensor count
        val kvCount = reader.u64() ?: return null
        if (kvCount < 0 || kvCount > MAX_KV) return null
        var architecture: String? = null
        var context: Int? = null
        var original: Int? = null
        var index = 0L
        while (index < kvCount && reader.hasMore()) {
            val key = reader.string(1_024) ?: break
            val type = reader.u32() ?: break
            when {
                key == "general.architecture" && type == TYPE_STRING -> {
                    architecture = reader.string(MAX_STRING_BYTES)
                }
                key.endsWith(".context_length") && key != "general.context_length" -> {
                    val value = reader.integer(type)
                    if (value != null && value in 512..10_000_000 && context == null) {
                        context = value.toInt()
                    }
                }
                key.endsWith(".rope.scaling.original_context_length") -> {
                    val value = reader.integer(type)
                    if (value != null && value in 512..10_000_000 && original == null) {
                        original = value.toInt()
                    }
                }
                else -> reader.skipValue(type, 0)
            }
            index++
        }
        val tokens = context ?: original ?: return null
        GgufTrainingContext(
            tokens = tokens,
            architecture = architecture,
            source = if (context != null) "gguf-metadata" else "gguf-rope",
        )
    }.getOrNull()

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    private class Reader(private val bytes: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < bytes.size

        fun u8(): Int? {
            if (pos >= bytes.size) return null
            return bytes[pos++].toInt() and 0xFF
        }

        fun u32(): Int? {
            if (pos + 4 > bytes.size) return null
            var value = 0
            for (i in 0 until 4) value = value or ((bytes[pos + i].toInt() and 0xFF) shl (8 * i))
            pos += 4
            return value
        }

        fun u64(): Long? {
            if (pos + 8 > bytes.size) return null
            var value = 0L
            for (i in 0 until 8) value = value or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += 8
            return value
        }

        fun rawString(length: Int): String? {
            if (length < 0 || length > bytes.size - pos) return null
            val value = String(bytes, pos, length, Charsets.UTF_8)
            pos += length
            return value
        }

        fun string(max: Int): String? {
            val length = u64() ?: return null
            if (length < 0 || length > max || length > bytes.size - pos) return null
            val value = String(bytes, pos, length.toInt(), Charsets.UTF_8)
            pos += length.toInt()
            return value
        }

        fun integer(type: Int): Long? = when (type) {
            TYPE_UINT8 -> u8()?.toLong()
            TYPE_INT8 -> u8()?.toByte()?.toLong()
            TYPE_UINT16 -> {
                val value = u16()
                value?.toLong()
            }
            TYPE_INT16 -> u16()?.toShort()?.toLong()
            TYPE_UINT32 -> u32()?.toLong()?.and(0xFFFF_FFFFL)
            TYPE_INT32 -> u32()?.toLong()
            TYPE_UINT64 -> u64()
            TYPE_INT64 -> u64()
            else -> null
        }

        private fun u16(): Int? {
            if (pos + 2 > bytes.size) return null
            val value = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return value
        }

        fun skipValue(type: Int, depth: Int) {
            if (depth > 8) return
            when (type) {
                TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> pos += 1
                TYPE_UINT16, TYPE_INT16 -> pos += 2
                TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> pos += 4
                TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> pos += 8
                TYPE_STRING -> {
                    val length = u64() ?: return
                    if (length < 0 || length > MAX_STRING_BYTES || length > bytes.size - pos) {
                        pos = bytes.size
                    } else {
                        pos += length.toInt()
                    }
                }
                TYPE_ARRAY -> {
                    val itemType = u32() ?: return
                    val count = u64() ?: return
                    if (count < 0 || count > MAX_ARRAY_ITEMS) {
                        pos = bytes.size
                        return
                    }
                    var i = 0L
                    while (i < count && pos < bytes.size) {
                        skipValue(itemType, depth + 1)
                        i++
                    }
                }
                else -> pos = bytes.size
            }
            if (pos > bytes.size) pos = bytes.size
        }
    }
}
