package com.kaiser.rivet.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecretRecordCodecTest {

    @Test
    fun encryptedRecordRoundTrips() {
        val iv = ByteArray(12) { it.toByte() }
        val ciphertext = ByteArray(32) { (it + 20).toByte() }

        val decoded = decodeSecretRecord(encodeSecretRecord(iv, ciphertext))

        assertArrayEquals(iv, decoded?.iv)
        assertArrayEquals(ciphertext, decoded?.ciphertext)
    }

    @Test
    fun malformedRecordsAreRejected() {
        assertNull(decodeSecretRecord(""))
        assertNull(decodeSecretRecord("v2:AA==:AA=="))
        assertNull(decodeSecretRecord("v1:not-base64:also-bad"))
        assertNull(decodeSecretRecord("v1:AQID:AA=="))
    }

    @Test
    fun recordContainsOnlyVersionIvAndCiphertext() {
        val encoded = encodeSecretRecord(ByteArray(12), ByteArray(16))

        assertEquals(3, encoded.split(':').size)
        assertEquals("v1", encoded.substringBefore(':'))
    }
}
