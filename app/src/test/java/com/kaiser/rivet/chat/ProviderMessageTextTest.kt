package com.kaiser.rivet.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMessageTextTest {

    @Test fun aPlainExplanationIsShownInTheProvidersOwnWords() {
        assertEquals("The provider said: Insufficient balance for this request.",
            providerMessageText("Insufficient balance for this request."))
    }

    @Test fun markupIsRemovedInsteadOfRendered() {
        assertEquals("The provider said: Bad request: model overloaded",
            providerMessageText("<html><body><p>Bad request: model overloaded</p></body></html>"))
    }

    @Test fun entitiesAreDecoded() {
        assertEquals("The provider said: quota > limit & frozen",
            providerMessageText("quota &gt; limit &amp; frozen"))
    }

    @Test fun whitespaceIsCollapsedSoTheLineStaysReadable() {
        assertEquals("The provider said: upstream connect error",
            providerMessageText("  upstream   connect\n\terror  "))
    }

    @Test fun aLongMessageIsCapped() {
        val text = providerMessageText("x".repeat(1000))!!
        assertTrue(text.length <= 320)
        assertTrue(text.endsWith("…"))
    }

    @Test fun nothingUsableFallsBackToTheGenericLine() {
        assertNull(providerMessageText(""))
        assertNull(providerMessageText("   \n\t "))
        assertNull(providerMessageText("<div></div>"))
    }
}
