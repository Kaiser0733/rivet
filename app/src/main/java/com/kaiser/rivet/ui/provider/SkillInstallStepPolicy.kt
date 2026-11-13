package com.kaiser.rivet.ui.provider

import com.kaiser.rivet.agent.SkillInstallFailure

internal object SkillInstallStepPolicy {
    const val KIND_LINK = "link"
    const val KIND_INSTALLED = "installed"
    const val KIND_FAILED = "failed"

    fun failureMessage(error: Exception): String = when (error) {
        is SkillInstallFailure -> error.message ?: "The skill couldn't be installed."
        is SecurityException -> "Rivet lost permission to read that file. Try sharing it again."
        is java.io.FileNotFoundException -> "That file couldn't be opened."
        else -> "The skill couldn't be installed."
    }

    fun shouldRunInitialZip(
        initialZipUri: String?,
        handledZipUri: String?,
        savedKind: String?,
    ): Boolean {
        if (initialZipUri == null) return false
        if (savedKind == KIND_INSTALLED || savedKind == KIND_FAILED) return false
        return handledZipUri != initialZipUri
    }
}
