package com.kaiser.rivet.runtime

import java.io.File

/**
 * Git network commands need a credential helper, but the helper path must not be in the
 * shared shell environment. It is prefixed onto the one approved git command instead.
 */
internal object GitCommandAuth {
    private val NETWORK = setOf("push", "pull", "fetch", "clone", "ls-remote")

    fun needsAuth(command: String): Boolean = networkHead(command) != null

    fun authenticated(command: String, credentialFile: File?): String {
        val head = networkHead(command) ?: return command
        if (credentialFile == null) return command
        val helper = "store --file=${credentialFile.absolutePath}"
        val prefix = "git -c credential.helper=${shellQuote(helper)} "
        return prefix + command.trimStart().removePrefix("git").trimStart()
    }

    private fun networkHead(command: String): String? {
        val parts = command.trimStart().split(Regex("\\s+"))
        if (parts.firstOrNull() != "git") return null
        var index = 1
        while (index < parts.size) {
            val part = parts[index]
            when {
                part == "-C" || part == "-c" || part == "--git-dir" || part == "--work-tree" -> index += 2
                part.startsWith("-") -> index++
                else -> return part.takeIf { it in NETWORK }
            }
        }
        return null
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
