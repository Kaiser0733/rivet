package com.kaiser.rivet.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHeaderPolicyTest {

    @Test fun rejectsMalformedHeaderNames() {
        assertTrue(ProviderHeaderPolicy.isValidHeaderName("X-Custom-1"))
        assertTrue(ProviderHeaderPolicy.isValidHeaderName("Accept-Encoding"))
        assertFalse(ProviderHeaderPolicy.isValidHeaderName(""))
        assertFalse(ProviderHeaderPolicy.isValidHeaderName("   "))
        assertFalse(ProviderHeaderPolicy.isValidHeaderName("Bad Header"))
        assertFalse(ProviderHeaderPolicy.isValidHeaderName("X:Colon"))
        assertFalse(ProviderHeaderPolicy.isValidHeaderName("X-Ctrl\n"))
        assertFalse(ProviderHeaderPolicy.isValidHeaderName("X-Unicode-\u2603"))
    }

    @Test fun rejectsControlAndNonAsciiHeaderValues() {
        assertTrue(ProviderHeaderPolicy.isValidHeaderValue(""))
        assertTrue(ProviderHeaderPolicy.isValidHeaderValue("token-123;\tvalue"))
        assertFalse(ProviderHeaderPolicy.isValidHeaderValue("line1\r\nX-Evil: 1"))
        assertFalse(ProviderHeaderPolicy.isValidHeaderValue("null\u0000byte"))
        assertFalse(ProviderHeaderPolicy.isValidHeaderValue("del\u007fchar"))
        assertFalse(ProviderHeaderPolicy.isValidHeaderValue("emoji-\u2603"))
    }

    @Test fun permittedCustomHeaderCombinesReservedAndValidityChecks() {
        assertTrue(ProviderHeaderPolicy.isPermittedCustomHeader("X-Custom", "abc"))
        assertFalse(ProviderHeaderPolicy.isPermittedCustomHeader("Authorization", "Bearer x"))
        assertFalse(ProviderHeaderPolicy.isPermittedCustomHeader("x-api-key", "secret"))
        assertFalse(ProviderHeaderPolicy.isPermittedCustomHeader("Bad Header", "abc"))
        assertFalse(ProviderHeaderPolicy.isPermittedCustomHeader("X-Custom", "bad\nval"))
    }
}
