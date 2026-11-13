package com.kaiser.rivet.runtime

import java.io.File

enum class ToolchainEntryKind { NativeLinked, WritableFile }

data class ToolchainEntry(
    val name: String,
    val location: String,
    val kind: ToolchainEntryKind,
    val userInvocable: Boolean,
)

data class ToolchainEntryAuditReport(val entries: List<ToolchainEntry>) {
    val violations: List<ToolchainEntry>
        get() = entries.filter { it.userInvocable && it.kind == ToolchainEntryKind.WritableFile }
    val ok: Boolean get() = violations.isEmpty()
}

/**
 * Classifies every user-invocable entry point the toolchain publishes. A native link can
 * be exec'd at targetSdk 35; a real file under data storage cannot, regardless of mode.
 */
object ToolchainEntryAudit {
    fun audit(
        farmLinks: Map<String, String>,
        pythonBinLinks: Map<String, String>,
        pythonBinOnPath: Boolean,
        gitExecLinks: Map<String, String>,
        nativeDir: String,
    ): ToolchainEntryAuditReport {
        val trimmedNative = nativeDir.trim().trimEnd(File.separatorChar)
        val nativePrefix = if (trimmedNative.isEmpty()) null else trimmedNative + File.separator
        fun kind(target: String): ToolchainEntryKind =
            if (nativePrefix != null && target.startsWith(nativePrefix)) ToolchainEntryKind.NativeLinked
            else ToolchainEntryKind.WritableFile
        val entries = ArrayList<ToolchainEntry>()
        for ((name, target) in farmLinks) {
            entries += ToolchainEntry(name, "farm", kind(target), userInvocable = true)
        }
        for ((name, target) in pythonBinLinks) {
            entries += ToolchainEntry(name, "python/bin", kind(target), pythonBinOnPath)
        }
        for ((name, target) in gitExecLinks) {
            // Git invokes these itself; keep their class visible without treating them as PATH.
            entries += ToolchainEntry(name, "git/libexec", kind(target), userInvocable = false)
        }
        return ToolchainEntryAuditReport(entries)
    }
}
