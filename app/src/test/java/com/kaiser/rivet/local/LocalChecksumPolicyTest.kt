package com.kaiser.rivet.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalChecksumPolicyTest {
    private val validHash = "a".repeat(64)

    @Test
    fun httpEtagAloneNeverMarksModelChecksumVerified() {
        assertFalse(
            LocalChecksumPolicy.isPublishedChecksumVerified(
                expectedSha256 = null,
                catalogSha256 = null,
                etagSha256 = validHash,
                byo = false,
                actualSha256Matched = true,
            ),
        )
        assertFalse(
            LocalChecksumPolicy.isPublishedChecksumVerified(
                expectedSha256 = null,
                catalogSha256 = validHash,
                etagSha256 = validHash,
                byo = true,
                actualSha256Matched = true,
            ),
        )
        assertTrue(
            LocalChecksumPolicy.isPublishedChecksumVerified(
                expectedSha256 = null,
                catalogSha256 = validHash,
                etagSha256 = null,
                byo = false,
                actualSha256Matched = true,
            ),
        )
        assertTrue(
            LocalChecksumPolicy.isPublishedChecksumVerified(
                expectedSha256 = validHash,
                catalogSha256 = null,
                etagSha256 = null,
                byo = true,
                actualSha256Matched = true,
            ),
        )
    }

    @Test
    fun sanitizeRelativeGgufPathRejectsTraversalAbsoluteBackslashAndNul() {
        assertNull(LocalChecksumPolicy.sanitizeRelativeGgufPath("../evil.gguf"))
        assertNull(LocalChecksumPolicy.sanitizeRelativeGgufPath("sub/../../evil.gguf"))
        assertNull(LocalChecksumPolicy.sanitizeRelativeGgufPath("/abs/evil.gguf"))
        assertNull(LocalChecksumPolicy.sanitizeRelativeGgufPath("sub\\evil.gguf"))
        assertNull(LocalChecksumPolicy.sanitizeRelativeGgufPath("bad\u0000.gguf"))
        assertEquals("Q4_K_M/model.gguf", LocalChecksumPolicy.sanitizeRelativeGgufPath("Q4_K_M/model.gguf"))
    }
}
