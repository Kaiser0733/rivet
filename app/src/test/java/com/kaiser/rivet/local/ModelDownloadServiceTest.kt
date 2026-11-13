package com.kaiser.rivet.local

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ModelDownloadServiceTest {
    private val previousDownloads = LocalRuntime.downloads

    @After
    fun restoreLocalRuntime() {
        LocalRuntime.downloads = previousDownloads
    }

    @Test
    fun startingWithoutDownloadManagerPromotesToForegroundBeforeStoppingSelf() {
        LocalRuntime.downloads = null
        val intent = Intent().setAction(ModelDownloadService.ACTION_START)
        val controller = Robolectric.buildService(ModelDownloadService::class.java, intent).create()
        val service = controller.get()

        controller.startCommand(0, 1)

        assertTrue(
            "ModelDownloadService must call startForeground before stopSelf to avoid ForegroundServiceDidNotStartInTimeException",
            service.foregroundStartCount > 0,
        )
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }
}
