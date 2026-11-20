package com.kaiser.rivet.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayExportOptionsTest {
    @Test fun watermarkDefaultsOnAndCanBeDisabled() {
        assertTrue(replayWatermarkEnabled(null))
        assertFalse(replayWatermarkEnabled(false))
        assertTrue(replayWatermarkEnabled(true))
    }

    @Test fun galleryStartsAtApi29AndTheShareChoiceAlwaysShares() {
        listOf(ReplayChoice.Video, ReplayChoice.Gif).forEach { choice ->
            assertEquals(ReplayDestination.Share, choice.destination(26))
            assertEquals(ReplayDestination.Share, choice.destination(28))
            assertEquals(ReplayDestination.Gallery, choice.destination(29))
            assertEquals(ReplayDestination.Gallery, choice.destination(35))
        }
        assertEquals(ReplayDestination.Share, ReplayChoice.ShareSheet.destination(35))
    }

    @Test fun shareReusesThePreparedFormatOrStartsWithVideo() {
        assertEquals(ReplayFormat.Video, ReplayChoice.ShareSheet.format(null))
        assertEquals(ReplayFormat.Gif, ReplayChoice.ShareSheet.format(ReplayFormat.Gif))
        assertEquals(ReplayFormat.Video, ReplayChoice.Video.format(ReplayFormat.Gif))
        assertEquals(ReplayFormat.Gif, ReplayChoice.Gif.format(ReplayFormat.Video))
    }
}
