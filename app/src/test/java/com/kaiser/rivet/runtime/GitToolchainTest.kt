package com.kaiser.rivet.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GitToolchainTest {
    @get:Rule val temp = TemporaryFolder()

    private val base = listOf("HOME=/h", "PATH=/p", "TMPDIR=/mirror/tmp", "PWD=/w",
        "LANG=C.UTF-8", "TERM=xterm-256color")

    @Test fun baseEnvironmentIsAppOwnedWithoutDeveloperLeftovers() {
        val env = baseEnvironment(File("/data/app/home"), SYSTEM_PATH,
            File("/data/app/tmp"), File("/data/app/work"))

        assertEquals(listOf(
            "HOME=/data/app/home",
            "PATH=$SYSTEM_PATH",
            "TMPDIR=/data/app/tmp",
            "PWD=/data/app/work",
            "LANG=C.UTF-8",
            "TERM=xterm-256color",
        ), env)
        val joined = env.joinToString("\n")
        assertFalse(joined.contains("termux"))
        assertFalse(joined.contains("/usr/local"))
        assertFalse(joined.contains("HOME=/root"))
    }

    @Test fun environmentIsByteIdenticalWithoutGit() {
        assertEquals(base, gitEnvironment(base, null, null, null))
        // A credential file must never add variables on its own.
        assertEquals(base, gitEnvironment(base, null, null, File("/creds")))
    }

    @Test fun environmentAddsTheFixedGitContract() {
        val env = gitEnvironment(base, File("/zone/exec"), File("/zone/templates"), null)

        assertTrue("GIT_EXEC_PATH=/zone/exec" in env)
        assertTrue("GIT_TEMPLATE_DIR=/zone/templates" in env)
        assertTrue("GIT_SSL_CAPATH=/system/etc/security/cacerts" in env)
        assertTrue("GIT_CONFIG_NOSYSTEM=1" in env)
        assertFalse(env.any { it.startsWith("GIT_CONFIG_COUNT=") })
    }

    @Test fun credentialInjectionNamesOnlyTheHelperAndFile() {
        val credential = File("/zone/git-credentials")
        val env = gitEnvironment(base, File("/zone/exec"), File("/zone/templates"), credential)

        assertEquals(1, env.count { it.startsWith("GIT_CONFIG_COUNT=") })
        assertTrue("GIT_CONFIG_KEY_0=credential.helper" in env)
        assertTrue("GIT_CONFIG_VALUE_0=store --file=${credential.absolutePath}" in env)
        assertFalse(env.joinToString("\n").contains("ghp_"))
    }

    @Test fun execFarmCreatesRepairsAndRemovesLinks() {
        val targets = gitExecTargets("/native/libgit.so", "/native/libgit-remote-https.so")
        assertEquals("/native/libgit-remote-https.so", targets["git-remote-https"])
        assertEquals("/native/libgit-remote-https.so", targets["git-remote-http"])
        assertEquals("/native/libgit.so", targets["git-upload-pack"])
        assertEquals("/native/libgit.so", targets["git-receive-pack"])
        assertEquals("/native/libgit.so", targets["git-upload-archive"])

        val plan = planLinks(targets, mapOf(
            "git-remote-https" to "/old/libgit-remote-https.so",
            "git-remote-http" to "/native/libgit-remote-https.so",
            "git-upload-pack" to "/native/libgit.so",
            "git-http-push" to "/native/libgit-http-push.so",
        ))
        assertEquals(listOf("git-remote-https"), plan.repair)
        assertEquals(listOf("git-http-push"), plan.remove)
        assertEquals(listOf("git-receive-pack", "git-upload-archive"), plan.create)
    }

    @Test fun execTargetsOmitMissingBinaries() {
        assertTrue(gitExecTargets(null, null).isEmpty())
        assertEquals(setOf("git-remote-https", "git-remote-http"),
            gitExecTargets(null, "/native/libgit-remote-https.so").keys)
    }

    @Test fun farmAddsGitOnlyWhenTheInterpreterIsPresent() {
        val with = farmTargets(listOf("ls"), "/native/libbusybox.so", null, "/native/libgit.so")
        assertEquals("/native/libgit.so", with["git"])
        assertFalse("git" in farmTargets(listOf("ls"), "/native/libbusybox.so"))
    }

    @Test fun pathPrependsTheFarmWhenOnlyGitExists() {
        val farm = temp.newFolder("bin")
        val git = temp.newFile("libgit.so")

        assertEquals("${farm.absolutePath}:$SYSTEM_PATH",
            commandPath(null, farm, SYSTEM_PATH, null, git))
        assertEquals(SYSTEM_PATH, commandPath(null, farm))
    }
}
