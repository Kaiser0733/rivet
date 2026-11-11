package com.kaiser.rivet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolchainEntryAuditTest {
    private val native = "/data/app/com.kaiser.rivet/lib/arm64"
    private val writable = "/data/user/0/com.kaiser.rivet/files/toolchain"

    @Test fun theParentPipScriptsAreWritableViolations() {
        val report = ToolchainEntryAudit.audit(
            farmLinks = mapOf("python3" to "$native/libpython3.so"),
            pythonBinLinks = mapOf(
                "python3" to "$native/libpython3.so",
                "pip" to "$writable/python/bin/pip",
                "pip3" to "$writable/python/bin/pip3",
            ),
            pythonBinOnPath = true,
            gitExecLinks = mapOf("git" to "$native/libgit.so"),
            nativeDir = native,
        )
        assertEquals(listOf("pip", "pip3"), report.violations.map { it.name })
    }

    @Test fun aFarmWithNativePipAndPythonBinOffPathIsClean() {
        val report = ToolchainEntryAudit.audit(
            farmLinks = mapOf(
                "python3" to "$native/libpython3.so",
                "pip" to "$native/libpip.so",
                "pip3" to "$native/libpip.so",
            ),
            pythonBinLinks = mapOf(
                "pip" to "$writable/python/bin/pip",
                "pip3" to "$writable/python/bin/pip3",
            ),
            pythonBinOnPath = false,
            gitExecLinks = mapOf(
                "git" to "$native/libgit.so",
                "git-remote-https" to "$native/libgit-remote-https.so",
            ),
            nativeDir = native,
        )
        assertTrue(report.ok)
        assertTrue(report.entries.none { it.userInvocable && it.kind == ToolchainEntryKind.WritableFile })
    }

    @Test fun gitLibexecWritableFilesAreClassifiedButNotUserInvocable() {
        val report = ToolchainEntryAudit.audit(
            farmLinks = emptyMap(),
            pythonBinLinks = emptyMap(),
            pythonBinOnPath = false,
            gitExecLinks = mapOf("git-shell" to "$writable/libexec/git-core/git-shell"),
            nativeDir = native,
        )
        assertEquals(ToolchainEntryKind.WritableFile, report.entries.single().kind)
        assertTrue(report.ok)
    }

    @Test fun thePipFarmLinksPointAtTheNativeLauncher() {
        val targets = farmTargets(
            applets = listOf("ls"),
            busybox = "$native/libbusybox.so",
            python = "$native/libpython3.so",
            git = "$native/libgit.so",
            pip = "$native/libpip.so",
        )
        assertEquals("$native/libpip.so", targets["pip"])
        assertEquals("$native/libpip.so", targets["pip3"])
    }
}
