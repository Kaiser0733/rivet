package com.kaiser.rivet.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The view-model tests construct RuntimeController (and therefore Toolchain) under
 * Robolectric, where the context can expose no native library directory. Construction
 * must stay free of context/filesystem reads, and an unavailable path must behave
 * exactly like a missing binary: the legacy PATH, no farm, no crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ToolchainUnavailableTest {
    @Test fun constructionAndPathSurviveTheRobolectricApplication() {
        val toolchain = Toolchain(RuntimeEnvironment.getApplication())

        assertEquals(SYSTEM_PATH, toolchain.path())
        runBlocking { toolchain.ensurePrepared() }
        assertEquals(SYSTEM_PATH, toolchain.path())
    }

    @Test fun aContextWithoutANativeLibraryDirIsTreatedAsNoBinary() {
        val app = RuntimeEnvironment.getApplication()
        // A fresh ApplicationInfo has no nativeLibraryDir, the same shape the
        // Robolectric application exposes to the failing setup.
        val info = ApplicationInfo()
        val unavailable = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo(): ApplicationInfo = info
        }

        val toolchain = Toolchain(unavailable)

        assertNull(toolchain.nativeDir)
        assertNull(toolchain.busybox)
        assertEquals(SYSTEM_PATH, toolchain.path())
        runBlocking { toolchain.ensurePrepared() }
        assertEquals(SYSTEM_PATH, toolchain.path())
    }
}
