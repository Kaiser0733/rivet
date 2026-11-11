package com.kaiser.rivet.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolchainTest {
    @get:Rule val temp = TemporaryFolder()

    private fun busybox() = temp.newFile("libbusybox.so")

    @Test fun systemPathIsExactlyThePreToolchainValue() {
        assertEquals("/system/bin:/system/xbin:/vendor/bin", SYSTEM_PATH)
    }

    @Test fun pathPrependsTheFarmWhenTheBinaryAndFarmExist() {
        val farm = temp.newFolder("bin")

        assertEquals("${farm.absolutePath}:$SYSTEM_PATH", commandPath(busybox(), farm))
    }

    @Test fun pathIsUnchangedWithoutTheBinary() {
        val farm = temp.newFolder("bin")

        assertEquals(SYSTEM_PATH, commandPath(File(temp.root, "libbusybox.so"), farm))
    }

    @Test fun pathIsUnchangedWithoutTheFarm() {
        assertEquals(SYSTEM_PATH, commandPath(busybox(), File(temp.root, "bin")))
    }

    @Test fun appletListHasNoDuplicatesOrPathSeparators() {
        assertEquals(BUSYBOX_APPLETS.size, BUSYBOX_APPLETS.toSet().size)
        assertTrue(BUSYBOX_APPLETS.all { it.isNotEmpty() && '/' !in it && '\\' !in it })
    }

    @Test fun appletListCoversTheToolsThePlanNames() {
        assertTrue(BUSYBOX_APPLETS.containsAll(
            listOf("sh", "ls", "cp", "grep", "sed", "awk", "find", "xargs", "tar", "gzip",
                "sort", "sha256sum")))
    }

    @Test fun farmPlanCreatesRepairsAndRemovesStaleLinks() {
        val plan = planFarm(
            wanted = listOf("ls", "cp", "grep"),
            existing = mapOf(
                "ls" to "/old/libbusybox.so",
                "cp" to "/new/libbusybox.so",
                "tee" to "/new/libbusybox.so",
                "stale" to "/old/libbusybox.so",
            ),
            target = "/new/libbusybox.so",
        )

        assertEquals(listOf("grep"), plan.create)
        assertEquals(listOf("ls"), plan.repair)
        assertEquals(listOf("stale", "tee"), plan.remove)
        assertFalse(plan.isEmpty)
    }

    @Test fun farmPlanIsEmptyWhenEveryLinkMatches() {
        val plan = planFarm(
            wanted = listOf("ls", "grep"),
            existing = mapOf("ls" to "/new/libbusybox.so", "grep" to "/new/libbusybox.so"),
            target = "/new/libbusybox.so",
        )

        assertTrue(plan.isEmpty)
    }

    @Test fun pythonScriptDirectoryStaysOffThePath() {
        val farm = temp.newFolder("bin")

        assertEquals("${farm.absolutePath}:$SYSTEM_PATH", commandPath(busybox(), farm))
    }
}
