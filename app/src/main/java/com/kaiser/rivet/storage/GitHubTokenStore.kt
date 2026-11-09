package com.kaiser.rivet.storage

import android.content.Context
import android.system.Os
import java.io.File

private const val CREDENTIAL_MODE = 0b110_000_000 // 0600
private const val MAX_TOKEN_LENGTH = 512

/** The encrypted token slot; a seam so the credential-file lifecycle is testable off-device. */
internal interface GitHubTokenVault {
    fun read(): String?
    fun save(token: String): Boolean
    fun clear()
}

/**
 * Keeps git's store helper pointed at an app-private credential file. The token leaves the
 * Keystore only to be written there; it never enters an environment value, a command, the
 * model context, or a log. Disconnect clears the vault and the file together.
 */
class GitHubTokenStore internal constructor(
    private val vault: GitHubTokenVault,
    private val credentialFile: File,
    private val restrict: (File, Int) -> Unit = { file, mode -> Os.chmod(file.absolutePath, mode) },
) {
    constructor(context: Context) : this(
        vault = SecretStoreVault(SecretStore(context.applicationContext)),
        credentialFile = File(context.applicationContext.filesDir, "toolchain/git-credentials"),
    )

    fun isConnected(): Boolean = synchronized(lock) {
        vault.read()?.let(::validToken) == true
    }

    fun connect(token: String): Boolean = synchronized(lock) {
        val clean = token.trim()
        if (!validToken(clean)) return@synchronized false
        if (!vault.save(clean)) return@synchronized false
        if (refreshLocked() == null) {
            // Never leave a saved token that the runtime cannot actually use.
            vault.clear()
            false
        } else true
    }

    fun disconnect() = synchronized(lock) {
        try {
            vault.clear()
        } finally {
            wipe()
        }
    }

    /** Returns the credential file for this command, or null when no usable token exists. */
    fun refresh(): File? = synchronized(lock) { refreshLocked() }

    private fun refreshLocked(): File? {
        val token = vault.read()
        if (token == null || !validToken(token)) {
            wipe()
            return null
        }
        return try {
            val parent = credentialFile.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                wipe()
                return null
            }
            val line = credentialLine(token)
            if (!credentialFile.isFile || credentialFile.readText() != line) {
                credentialFile.writeText(line)
            }
            restrict(credentialFile, CREDENTIAL_MODE)
            credentialFile
        } catch (_: Exception) {
            wipe()
            null
        }
    }

    private fun wipe() {
        credentialFile.delete()
        if (credentialFile.exists()) {
            try {
                credentialFile.writeText("")
                restrict(credentialFile, CREDENTIAL_MODE)
            } catch (_: Exception) {
                // Best effort: the app-private directory is still unreadable to other apps.
            }
        }
    }

    /** GitHub tokens cannot contain whitespace or URL delimiters; rejecting them prevents
     * a pasted line from turning into a second host entry in the credential file. */
    private fun validToken(token: String): Boolean =
        token.isNotEmpty() && token.length <= MAX_TOKEN_LENGTH &&
            token.none { it.isWhitespace() || it.isISOControl() || it in "@:/\\" }

    private companion object {
        val lock = Any()

        fun credentialLine(token: String): String =
            "https://x-access-token:$token@github.com\n"
    }
}

private class SecretStoreVault(private val secrets: SecretStore) : GitHubTokenVault {
    override fun read(): String? = secrets.gitHubToken()
    override fun save(token: String): Boolean = secrets.saveGitHubToken(token)
    override fun clear() = secrets.clearGitHubToken()
}
