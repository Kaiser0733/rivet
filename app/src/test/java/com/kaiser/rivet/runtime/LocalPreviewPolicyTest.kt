package com.kaiser.rivet.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPreviewPolicyTest {
    @Test fun gitSegmentsAreRejectedCaseInsensitively() {
        assertTrue(LocalPreviewPolicy.isDisallowedGitSegment(".git"))
        assertTrue(LocalPreviewPolicy.isDisallowedGitSegment(".GIT"))
        assertTrue(LocalPreviewPolicy.isDisallowedGitSegment(".Git"))
        assertFalse(LocalPreviewPolicy.isDisallowedGitSegment(".github"))
    }

    @Test fun hostHeaderAllowsLoopbackOnlyForActivePortOrBareHost() {
        assertTrue(LocalPreviewPolicy.isLoopbackHostHeader("127.0.0.1", 8080))
        assertTrue(LocalPreviewPolicy.isLoopbackHostHeader("127.0.0.1:8080", 8080))
        assertTrue(LocalPreviewPolicy.isLoopbackHostHeader("localhost:8080", 8080))
        assertTrue(LocalPreviewPolicy.isLoopbackHostHeader("[::1]:8080", 8080))
        assertFalse(LocalPreviewPolicy.isLoopbackHostHeader("evil.com", 8080))
        assertFalse(LocalPreviewPolicy.isLoopbackHostHeader("127.0.0.1.evil.com:8080", 8080))
        assertFalse(LocalPreviewPolicy.isLoopbackHostHeader("127.0.0.1:9999", 8080))
        assertFalse(LocalPreviewPolicy.isLoopbackHostHeader(null, 8080))
    }
}
