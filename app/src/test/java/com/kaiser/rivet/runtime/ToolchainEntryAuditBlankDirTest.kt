package com.kaiser.rivet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolchainEntryAuditBlankDirTest {
    @Test
    fun blankNativeDirDoesNotClassifyWritablePathsAsNativeLinked() {
        val report = ToolchainEntryAudit.audit(
            farmLinks = mapOf("python3" to "/data/user/0/com.kaiser.rivet/files/toolchain/bin/python3"),
            pythonBinLinks = emptyMap(),
            pythonBinOnPath = false,
            gitExecLinks = emptyMap(),
            nativeDir = "",
        )
        assertEquals(ToolchainEntryKind.WritableFile, report.entries.single().kind)
        assertFalse(report.ok)
    }
}
