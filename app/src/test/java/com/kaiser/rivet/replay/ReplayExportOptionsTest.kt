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

    @Test fun shareReusesThePreparedFormatOrStartsWithVideo() {
        assertEquals(ReplayFormat.Video, ReplayChoice.ShareSheet.format(null))
        assertEquals(ReplayFormat.Gif, ReplayChoice.ShareSheet.format(ReplayFormat.Gif))
        assertEquals(ReplayFormat.Video, ReplayChoice.Video.format(ReplayFormat.Gif))
        assertEquals(ReplayFormat.Gif, ReplayChoice.Gif.format(ReplayFormat.Video))
    }
}
