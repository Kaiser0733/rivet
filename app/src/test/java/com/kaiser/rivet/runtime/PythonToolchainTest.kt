package com.kaiser.rivet.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonToolchainTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun manifestSkipsCommentsAndBlankLinesAndResolvesTargets() {
        val text = """
            # Rivet CPython 3.12.3 two-zone manifest
            #
            lib/python3.12/lib-dynload/_ssl.cpython-312.so libpyext__ssl.so

            lib/python3.12/lib-dynload/zlib.cpython-312.so libpyext_zlib.so  # inline note
        """.trimIndent()
        val links = parsePythonManifest(text)

        assertEquals(listOf(
            PythonLink("lib/python3.12/lib-dynload/_ssl.cpython-312.so", "libpyext__ssl.so"),
            PythonLink("lib/python3.12/lib-dynload/zlib.cpython-312.so", "libpyext_zlib.so"),
        ), links)
        val native = temp.newFolder("native")
        val targets = pythonLinkTargets(links, native)
        assertEquals(File(native, "libpyext__ssl.so").absolutePath, targets[links[0].path])
        assertEquals(2, targets.size)
    }

    @Test fun manifestDropsEscapingAbsoluteAndMalformedLines() {
        val links = parsePythonManifest("""
            ../outside.so libpyext_x.so
            /abs/path.so libpyext_y.so
            lib/python3.12/lib-dynload/a.so /abs/libpyext_z.so
            lib/python3.12/lib-dynload/b.so ../libpyext_w.so
            oneword
            lib/python3.12/lib-dynload/c.so libpyext_ok.so
        """.trimIndent())

        assertEquals(listOf(PythonLink("lib/python3.12/lib-dynload/c.so", "libpyext_ok.so")), links)
    }

    @Test fun extractionDecisionUsesMarkerAndPresence() {
        assertEquals(VersionedExtraction.Extract, versionedExtraction(null, "3.12.3-rivet1", true))
        assertEquals(VersionedExtraction.Current,
            versionedExtraction(PYTHON_STDLIB_VERSION, PYTHON_STDLIB_VERSION, true))
        assertEquals(VersionedExtraction.Extract,
            versionedExtraction("3.12.3-rivet0", PYTHON_STDLIB_VERSION, true))
        assertEquals(VersionedExtraction.Extract,
            versionedExtraction(PYTHON_STDLIB_VERSION, PYTHON_STDLIB_VERSION, false))
    }

    @Test fun farmAddsPythonEntriesOnlyWhenTheInterpreterIsPresent() {
        val targets = farmTargets(listOf("ls", "grep"), "/native/libbusybox.so",
            "/native/libpython3.so")

        assertEquals("/native/libbusybox.so", targets["ls"])
        assertEquals("/native/libpython3.so", targets["python3"])
        assertEquals("/native/libpython3.so", targets["python"])

        val absent = farmTargets(listOf("ls", "grep"), "/native/libbusybox.so")
        assertEquals(setOf("ls", "grep"), absent.keys)
        assertFalse("python3" in absent)
        assertFalse("python" in absent)
    }

    @Test fun farmPlanRepairsPythonLinksWhenTheNativeDirMoves() {
        val plan = planFarm(
            wanted = listOf("ls"),
            existing = mapOf(
                "ls" to "/old/libbusybox.so",
                "python3" to "/old/libpython3.so",
                "python" to "/new/libpython3.so",
                "oldapplet" to "/new/libbusybox.so",
            ),
            target = "/new/libbusybox.so",
            python = "/new/libpython3.so",
        )

        assertEquals(listOf("ls", "python3"), plan.repair)
        assertEquals(listOf("oldapplet"), plan.remove)
        assertTrue(plan.create.isEmpty())
        assertFalse("python" in plan.repair)
    }

    @Test fun pathPrependsTheFarmWhenOnlyPythonExists() {
        val farm = temp.newFolder("bin")
        val python = temp.newFile("libpython3.so")

        assertEquals("${farm.absolutePath}:$SYSTEM_PATH",
            commandPath(null, farm, SYSTEM_PATH, python))
        assertEquals(SYSTEM_PATH, commandPath(null, farm))
    }

    @Test fun environmentIsByteIdenticalWithoutPython() {
        val base = listOf("HOME=/h", "PATH=/p", "TMPDIR=/mirror/tmp", "PWD=/w",
            "LANG=C.UTF-8", "TERM=xterm-256color")

        assertEquals(base, pythonEnvironment(base, null, null))
        // Half a prepared pair must not half-apply the Python environment.
        assertEquals(base, pythonEnvironment(base, File("/zone/python"), null))
    }

    @Test fun environmentAddsPythonVarsAndReplacesTmpdirOnce() {
        val base = listOf("HOME=/h", "PATH=/p", "TMPDIR=/mirror/tmp", "PWD=/w",
            "LANG=C.UTF-8", "TERM=xterm-256color")
        val env = pythonEnvironment(base, File("/zone/python"), File("/zone/tmp"))

        assertTrue("PYTHONHOME=/zone/python" in env)
        assertTrue("SSL_CERT_FILE=/system/etc/security/cacerts" in env)
        assertTrue("TMPDIR=/zone/tmp" in env)
        assertEquals(1, env.count { it.startsWith("TMPDIR=") })
        assertFalse("TMPDIR=/mirror/tmp" in env)
        assertEquals(base.size + 2, env.size)
    }
}
