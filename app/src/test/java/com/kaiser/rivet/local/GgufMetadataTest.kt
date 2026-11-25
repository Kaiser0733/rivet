package com.kaiser.rivet.local

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GgufMetadataTest {

    private fun string(out: ByteArrayOutputStream, value: String) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value.length.toLong()).array())
        out.write(value.toByteArray(Charsets.UTF_8))
    }

    private fun u32(out: ByteArrayOutputStream, value: Int) {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun u64(out: ByteArrayOutputStream, value: Long) {
        out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array())
    }

    private fun gguf(vararg pairs: Pair<String, Long>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        u32(out, 3)
        u64(out, 0)
        u64(out, pairs.size.toLong() + 1)
        string(out, "general.architecture")
        u32(out, 8)
        string(out, "llama")
        for ((key, value) in pairs) {
            string(out, key)
            u32(out, 4) // uint32
            u32(out, value.toInt())
        }
        return out.toByteArray()
    }

    @Test
    fun readsTheTrainingContextFromLlamaMetadata() {
        val parsed = GgufMetadata.parse(gguf("llama.context_length" to 4096))
        assertEquals(4096, parsed!!.tokens)
        assertEquals("llama", parsed.architecture)
        assertEquals("gguf-metadata", parsed.source)
    }

    @Test
    fun fallsBackToRopeOriginalWhenContextIsMissing() {
        val parsed = GgufMetadata.parse(gguf("llama.rope.scaling.original_context_length" to 32768))
        assertEquals(32_768, parsed!!.tokens)
        assertEquals("gguf-rope", parsed.source)
    }

    @Test
    fun truncatedOrWrongMagicReturnsNull() {
        assertNull(GgufMetadata.parse(byteArrayOf(1, 2, 3, 4)))
        val bytes = gguf("llama.context_length" to 4096)
        assertNull(GgufMetadata.parse(bytes.copyOf(bytes.size - 3)))
    }

    @Test
    fun localCeilingIsTheSmallerOfTrainingAndDeviceBudget() {
        assertEquals(4_096, LocalLimitPolicy.effectiveContext(16_384, 4_096))
        assertEquals(8_192, LocalLimitPolicy.effectiveContext(8_192, 131_072))
        assertEquals(16_384, LocalLimitPolicy.effectiveContext(16_384, null))
        assertEquals(1_024, LocalLimitPolicy.effectiveOutput(8_192))
        assertEquals(256, LocalLimitPolicy.effectiveOutput(512))
    }

    @Test
    fun unknownKeysAreSkippedWithoutBreakingLaterValues() {
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        u32(out, 3); u64(out, 0); u64(out, 3)
        string(out, "general.architecture"); u32(out, 8); string(out, "qwen2")
        string(out, "some.array"); u32(out, 9); u32(out, 8)
        u64(out, 3); string(out, "a"); string(out, "b"); string(out, "c")
        string(out, "qwen2.context_length"); u32(out, 4); u32(out, 8192)
        val parsed = GgufMetadata.parse(out.toByteArray())
        assertEquals(8192, parsed!!.tokens)
        assertEquals("qwen2", parsed.architecture)
    }
}
