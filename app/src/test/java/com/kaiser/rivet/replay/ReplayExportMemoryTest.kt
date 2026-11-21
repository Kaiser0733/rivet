package com.kaiser.rivet.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayExportMemoryTest {
    private val gif = LastReplayExport(ReplayFormat.Gif, "/cache/Rivet-replay-1.gif", watermark = true)
    private val video = LastReplayExport(ReplayFormat.Video, "/cache/Rivet-replay-2.mp4", watermark = true)

    @Test fun shareUsesTheFormatExportedLastForThisRun() {
        assertEquals(ReplayFormat.Gif, replayFormatFor(ReplayChoice.ShareSheet, null, gif))
        assertEquals(ReplayFormat.Video, replayFormatFor(ReplayChoice.ShareSheet, null, video))
    }

    @Test fun shareKeepsRenderingAVideoWhenNothingWasExportedYet() {
        assertEquals(ReplayFormat.Video, replayFormatFor(ReplayChoice.ShareSheet, null, null))
    }

    @Test fun explicitChoicesIgnoreWhatWasRemembered() {
        assertEquals(ReplayFormat.Video, replayFormatFor(ReplayChoice.Video, ReplayFormat.Gif, gif))
        assertEquals(ReplayFormat.Gif, replayFormatFor(ReplayChoice.Gif, ReplayFormat.Video, video))
    }

    @Test fun reusableExportMustMatchFormatAndWatermarkAndStillExist() {
        val reused = reusableReplay(ReplayChoice.ShareSheet, null, gif, watermark = true, exists = { true })
        assertEquals(gif, reused)
        assertNull(reusableReplay(ReplayChoice.ShareSheet, null, gif, watermark = false, exists = { true }))
        assertNull(reusableReplay(ReplayChoice.ShareSheet, null, gif, watermark = true, exists = { false }))
    }

    @Test fun shareRightAfterAGifReopensAndSharesThatGif() {
        // The reported bug: a fresh sheet forgot the GIF export and made an mp4 to share.
        val reused = reusableReplay(
            choice = ReplayChoice.ShareSheet,
            prepared = null,
            last = gif,
            watermark = true,
            exists = { it.endsWith(".gif") },
        )
        assertEquals(gif, reused)
        assertEquals(ReplayFormat.Gif, replayFormatFor(ReplayChoice.ShareSheet, null, gif))
        assertEquals(ReplayFormat.Video, replayFormatFor(ReplayChoice.ShareSheet, video.format, gif))
    }

    @Test fun textColumnGrowsPastTheOldFixedWidth() {
        assertTrue(frameTextWidth() > 808)
        assertTrue("right edge stays inside the panel",
            frameTextRight() < FRAME_PANEL_RIGHT)
        assertEquals(FRAME_CONTENT_INSET, FRAME_TEXT_LEFT - FRAME_PANEL_LEFT)
        assertEquals(FRAME_CONTENT_INSET, FRAME_PANEL_RIGHT - frameTextRight())
    }
}
