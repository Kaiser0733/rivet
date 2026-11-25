package com.kaiser.rivet.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitCommandAuthTest {
    private val credential = File("/data/app/files/toolchain/git-credentials")

    @Test fun ordinaryCommandsKeepTheSharedEnvironmentUntouched() {
        assertEquals("env", GitCommandAuth.authenticated("env", credential))
        assertFalse(GitCommandAuth.needsAuth("env"))
        assertEquals("git status", GitCommandAuth.authenticated("git status", credential))
        assertFalse(GitCommandAuth.needsAuth("git status"))
    }

    @Test fun networkGitCommandsGetAPerCommandHelperButNoEnvironmentEntry() {
        val command = GitCommandAuth.authenticated("git push origin main", credential)
        assertTrue(command.startsWith("git -c credential.helper='store --file=${credential.absolutePath}' "))
        assertTrue(command.endsWith("push origin main"))
        assertTrue(GitCommandAuth.needsAuth("git -C project push"))
        assertTrue(GitCommandAuth.needsAuth("git fetch origin"))
    }
}
