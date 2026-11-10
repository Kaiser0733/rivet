package com.kaiser.rivet.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class PipPermissionsTest {
    @Test fun theToolchainUnpackMakesPipLaunchersExecutable() {
        val home = Files.createTempDirectory("rivet-pip").toFile()
        try {
            val bin = File(home, "bin").apply { mkdirs() }
            val pip = File(bin, "pip").apply { writeText("#!/bin/sh\n") }
            val pip3 = File(bin, "pip3").apply { writeText("#!/bin/sh\n") }
            assertTrue(pip.setExecutable(false, false))
            assertTrue(pip3.setExecutable(false, false))
            markPipEntryPoints(home)
            assertTrue("pip must be executable", pip.canExecute())
            assertTrue("pip3 must be executable", pip3.canExecute())
        } finally {
            home.deleteRecursively()
        }
    }
}
