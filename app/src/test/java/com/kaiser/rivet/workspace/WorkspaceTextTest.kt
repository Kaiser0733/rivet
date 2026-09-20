package com.kaiser.rivet.workspace

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class WorkspaceTextTest {
    @Test fun readsStrictUtf8AndHashesOriginalBytes() {
        val bytes = "hello λ\r\n".toByteArray()
        val snapshot = WorkspaceText.snapshot(WorkspacePath.parse("a.kt"), bytes, 42L)
        assertEquals("hello λ\r\n", snapshot.text)
        assertEquals(bytes.size.toLong(), snapshot.size)
        assertEquals(42L, snapshot.modifiedTime)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", WorkspaceText.sha256(byteArrayOf()))
    }
    @Test fun binaryAndMalformedUtf8AreRejected() {
        listOf(byteArrayOf(0, 2, 3), byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(1, 2)).forEach {
            assertEquals(WorkspaceFailure.Reason.BINARY, assertThrows(WorkspaceFailure::class.java) {
                WorkspaceText.decode(it)
            }.reason)
        }
    }
    @Test fun sizeIsBoundedEvenWithoutProviderMetadata() {
        assertEquals(WorkspaceText.MAX_BYTES, WorkspaceText.readBounded(ByteArrayInputStream(ByteArray(WorkspaceText.MAX_BYTES))).size)
        assertEquals(WorkspaceFailure.Reason.TOO_LARGE, assertThrows(WorkspaceFailure::class.java) {
            WorkspaceText.readBounded(ByteArrayInputStream(ByteArray(WorkspaceText.MAX_BYTES + 1)))
        }.reason)
    }
    @Test fun currentBytesMustMatchExpectedHash() {
        assertThrows(WorkspaceFailure::class.java) { WorkspaceText.requireHash("new".toByteArray(), WorkspaceText.sha256("old".toByteArray())) }
        WorkspaceText.requireHash("old".toByteArray(), WorkspaceText.sha256("old".toByteArray()))
    }
    @Test fun exactPatchReturnsCompleteReplacement() {
        assertEquals("one TWO three!", WorkspaceText.patch("one two three", listOf(TextEdit("two", "TWO"), TextEdit("three", "three!"))))
    }
    @Test fun absentAndAmbiguousMatchesFail() {
        listOf("missing", "a").forEach { old ->
            assertThrows(WorkspaceFailure::class.java) { WorkspaceText.patch("a a", listOf(TextEdit(old, "b"))) }
        }
        assertThrows(WorkspaceFailure::class.java) { WorkspaceText.patch("aaa", listOf(TextEdit("aa", "b"))) }
        assertThrows(WorkspaceFailure::class.java) { WorkspaceText.patch("abc", listOf(TextEdit("", "b"))) }
    }
    @Test fun multiEditFailureNeverCallsWriter() {
        var wrote = false
        assertThrows(WorkspaceFailure::class.java) {
            val complete = WorkspaceText.patch("abc", listOf(TextEdit("a", "A"), TextEdit("missing", "B")))
            wrote = true
            assertEquals("Abc", complete)
        }
        assertFalse(wrote)
    }
    @Test fun encodedWritesCannotExceedLimit() {
        assertThrows(WorkspaceFailure::class.java) { WorkspaceText.encode("λ".repeat(WorkspaceText.MAX_BYTES)) }
    }
}
