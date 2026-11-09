package com.kaiser.rivet.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CaBundleToolchainTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun bundledCertificatesAreExtractedAndComposedIntoTheEnvironment() {
        val target = File(temp.newFolder("etc", "ssl"), "cert.pem")
        repoAsset().inputStream().use { input ->
            assertTrue(extractCaBundle(input, target))
        }
        assertTrue(target.readText().contains("BEGIN CERTIFICATE"))

        val base = listOf("HOME=/h", "PATH=/p", "TMPDIR=/mirror/tmp", "PWD=/w",
            "LANG=C.UTF-8", "TERM=xterm-256color")
        val python = pythonEnvironment(base, File("/zone/python"), File("/zone/tmp"), target)
        assertTrue("SSL_CERT_FILE=${target.absolutePath}" in python)
        val git = gitEnvironment(base, File("/zone/exec"), File("/zone/templates"), null, target)
        assertTrue("SSL_CERT_FILE=${target.absolutePath}" in git)
        assertTrue("GIT_SSL_CAINFO=${target.absolutePath}" in git)
    }

    // The unit-test working directory is not stable across Gradle versions; locate the
    // checked-out asset rather than assuming the module directory.
    private fun repoAsset(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidate = File(directory, "toolchain/src/main/assets/ca-certificates.pem")
            if (candidate.isFile) return candidate
            directory = directory.parentFile
        }
        throw AssertionError("ca-certificates.pem not found above ${File("").absolutePath}")
    }
}
