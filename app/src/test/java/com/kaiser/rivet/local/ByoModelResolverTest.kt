package com.kaiser.rivet.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ByoModelResolverTest {
    @Test
    fun parsesUppercaseSchemeAndHostPreservingPlusAndBranch() {
        val parsed = ByoLinkParser.parse(
            "HTTPS://HUGGINGFACE.CO/user/model-repo/blob/v2.1/sub/model+Q4_K_M%20fast.gguf?download=true",
        )
        assertEquals("user/model-repo", parsed.repo)
        assertEquals("v2.1", parsed.revision)
        assertEquals("sub/model+Q4_K_M fast.gguf", parsed.file)
        assertEquals(
            "https://huggingface.co/user/model-repo/resolve/v2.1/sub/model+Q4_K_M%20fast.gguf?download=true",
            ByoLinkParser.downloadUrl(parsed.repo, parsed.revision, parsed.file!!),
        )
    }

    @Test
    fun doesNotConfuseOwnerOrRepoNamedBlobOrResolveWithPathDelimiter() {
        val repoNamedResolve = ByoLinkParser.parse(
            "https://huggingface.co/alice/resolve/blob/dev/quant+v1.gguf",
        )
        assertEquals("alice/resolve", repoNamedResolve.repo)
        assertEquals("dev", repoNamedResolve.revision)
        assertEquals("quant+v1.gguf", repoNamedResolve.file)

        val ownerNamedBlob = ByoLinkParser.parse(
            "https://huggingface.co/blob/my-model",
        )
        assertEquals("blob/my-model", ownerNamedBlob.repo)
        assertEquals("main", ownerNamedBlob.revision)
        assertNull(ownerNamedBlob.file)
    }

    @Test
    fun rejectsTraversalEvenWhenPercentEncoded() {
        val error = runCatching {
            ByoLinkParser.parse("https://huggingface.co/alice/model/blob/main/%2e%2e/secret.gguf")
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
