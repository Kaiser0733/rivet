package com.kaiser.rivet.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitHubTokenStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeVault(var token: String? = null) : GitHubTokenVault {
        override fun read(): String? = token
        override fun save(token: String): Boolean {
            this.token = token
            return true
        }
        override fun clear() {
            token = null
        }
    }

    private val token = "ghp_sentinel123"
    private var restrictedFile: File? = null
    private var restrictedMode = -1

    private fun store(vault: FakeVault, file: File): GitHubTokenStore =
        GitHubTokenStore(vault, file) { target, mode ->
            restrictedFile = target
            restrictedMode = mode
        }

    @Test fun connectLeavesNoCredentialFileAndARefreshWritesTheOwnerOnlyLine() {
        val vault = FakeVault()
        val file = File(temp.root, "toolchain/git-credentials")
        val store = store(vault, file)

        assertTrue(store.connect(token))
        assertEquals(token, vault.token)
        assertFalse(file.exists())

        assertEquals(file, store.refresh())
        assertEquals("https://x-access-token:$token@github.com\n", file.readText())
        assertEquals(file, restrictedFile)
        assertEquals(0b110_000_000, restrictedMode)

        store.clearCredentialFile()
        assertFalse(file.exists())
    }

    @Test fun connectRejectsTokensThatCouldBreakTheCredentialLine() {
        val vault = FakeVault()
        val file = File(temp.root, "git-credentials")

        assertFalse(store(vault, file).connect("two words"))
        assertFalse(store(vault, file).connect("line\nbreak"))
        assertFalse(store(vault, file).connect("host/escape"))
        assertNull(vault.token)
        assertFalse(file.exists())
    }

    @Test fun disconnectWipesTheVaultAndTheFile() {
        val vault = FakeVault()
        val file = File(temp.root, "git-credentials")
        val store = store(vault, file)
        assertTrue(store.connect(token))
        store.refresh()
        assertTrue(file.isFile)

        store.disconnect()

        assertNull(vault.token)
        assertFalse(file.exists())
        assertNull(store.refresh())
    }

    @Test fun refreshWipesAStaleFileWhenNoTokenIsStored() {
        val vault = FakeVault()
        val file = File(temp.root, "git-credentials")
        file.writeText("https://x-access-token:$token@github.com\n")

        assertNull(store(vault, file).refresh())
        assertFalse(file.exists())
    }

    @Test fun refreshRewritesWhenTheStoredTokenChanges() {
        val vault = FakeVault()
        val file = File(temp.root, "git-credentials")
        val store = store(vault, file)
        assertTrue(store.connect(token))

        vault.token = "ghp_replacement456"
        assertEquals(file, store.refresh())
        assertTrue(file.readText().contains("ghp_replacement456"))
        assertFalse(file.readText().contains(token))
    }

    @Test fun theSharedShellEnvironmentNeverNamesTheCredentialFileOrToken() {
        val vault = FakeVault()
        val file = File(temp.root, "git-credentials")
        val store = store(vault, file)
        assertTrue(store.connect(token))
        assertTrue(store.refresh() != null)
        val base = listOf("HOME=/h", "PATH=/p", "TMPDIR=/tmp", "PWD=/w",
            "LANG=C.UTF-8", "TERM=xterm-256color")

        val env = com.kaiser.rivet.runtime.gitEnvironment(base,
            File("/zone/exec"), File("/zone/templates"), null)

        assertFalse(env.joinToString("\n").contains(file.absolutePath))
        assertFalse(env.joinToString("\n").contains(token))
        val joined = env.joinToString("\n")
        assertFalse(joined.contains("GIT_CONFIG_COUNT="))
        assertFalse(joined.contains("GIT_CONFIG_KEY_"))
        assertFalse(joined.contains("GIT_CONFIG_VALUE_"))
    }
}
