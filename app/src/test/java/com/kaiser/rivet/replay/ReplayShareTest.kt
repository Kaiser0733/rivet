package com.kaiser.rivet.replay

import android.content.Intent
import android.net.Uri
import com.kaiser.rivet.runtime.PhoneIntents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ReplayShareTest {
    @Test fun bothFormatsUseTheExistingSendIntentWithAReadGrant() {
        ReplayFormat.entries.forEach { format ->
            val uri = Uri.parse("content://com.kaiser.rivet.shared-files/replays/run.${format.extension}")
            val send = PhoneIntents.sharedFileIntent(PhoneIntents.ContentFile(uri, format.mime))
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals(format.mime, send.type)
            assertEquals(uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            assertEquals(uri, send.clipData!!.getItemAt(0).uri)
            assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        }
    }
}
