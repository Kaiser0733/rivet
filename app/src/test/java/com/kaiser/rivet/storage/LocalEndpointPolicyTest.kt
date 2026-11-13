package com.kaiser.rivet.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEndpointPolicyTest {
    @Test
    fun rejectsLookalikeAndPublicAddressesContainingLocalSubstrings() {
        assertFalse(LocalEndpointPolicy.isLocal("https://localhost.evil.com/v1"))
        assertFalse(LocalEndpointPolicy.isLocal("https://api.example.com/proxy?target=http://127.0.0.1"))
        assertFalse(LocalEndpointPolicy.isLocal("http://172.200.0.1/v1"))
        assertFalse(LocalEndpointPolicy.isLocal("http://172.2.0.1/v1"))
        assertFalse(LocalEndpointPolicy.isLocal("http://172.32.0.1/v1"))
        assertFalse(LocalEndpointPolicy.isLocal("not a url"))
    }

    @Test
    fun acceptsTrueLoopbackAndRfc1918Addresses() {
        assertTrue(LocalEndpointPolicy.isLocal("local://llama"))
        assertTrue(LocalEndpointPolicy.isLocal("http://localhost:11434/v1"))
        assertTrue(LocalEndpointPolicy.isLocal("http://127.0.0.1:8080/v1"))
        assertTrue(LocalEndpointPolicy.isLocal("http://[::1]:11434/v1"))
        assertTrue(LocalEndpointPolicy.isLocal("http://10.0.2.2:11434/v1"))
        assertTrue(LocalEndpointPolicy.isLocal("http://192.168.1.42:8000/v1"))
        assertTrue(LocalEndpointPolicy.isLocal("http://172.16.0.10:8000/v1"))
        assertTrue(LocalEndpointPolicy.isLocal("http://172.31.255.1:8000/v1"))
    }
}
