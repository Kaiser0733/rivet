package com.kaiser.rivet.replay

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReplayFramesTest {
    private val palette = ReplayFramePalette(
        0xFF151517.toInt(), 0xFF232324.toInt(), 0xFFF9FAFB.toInt(),
        0xFFADB2B8.toInt(), 0xFF7AAAFF.toInt(), 0x0FFFFFFF,
    )
    private val story = ReplayStory("Demo", null, emptyList(), 30_000)
    private val frames = ReplayFrames(story, palette)
    private val prompt = ReplayBeat.PromptCard("Restore the toolbar", 3000)

    @Test fun frameIsVerticalFullHdAndWatermarkIsOptional() {
        val off = frames.render(prompt, watermark = false)
        try {
            assertEquals(1080, off.width)
            assertEquals(1920, off.height)
            val unmarked = footer(off)
            assertTrue(unmarked.all { it == palette.background })
            off.recycle()
            val on = frames.render(prompt, watermark = true)
            try {
                assertFalse(footer(on).contentEquals(unmarked))
            } finally { on.recycle() }
        } finally { if (!off.isRecycled) off.recycle() }
    }

    @Test fun missingShotUsesAPlainPlaceholder() {
        val frame = frames.render(ReplayBeat.PreviewCard("/missing.webp", "Browser screenshot", 3000), false)
        try {
            val placeholder = frames.render(ReplayBeat.StepCard(
                "Browser screenshot", "Screenshot unavailable.", 3000,
            ), false)
            try {
                assertTrue(frame.sameAs(placeholder))
            } finally { placeholder.recycle() }
        } finally { frame.recycle() }
    }

    @Test fun wideShotKeepsItsFullWidthWithLetterboxing() {
        val file = File.createTempFile("replay-shot", ".png", RuntimeEnvironment.getApplication().cacheDir)
        try {
            val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.GREEN)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
            val frame = frames.render(ReplayBeat.PreviewCard(file.path, "Phone screenshot", 3000), false)
            try {
                assertEquals(Color.GREEN, frame.getPixel(90, 930))
                assertEquals(Color.GREEN, frame.getPixel(990, 930))
                assertEquals(palette.surface, frame.getPixel(540, 600))
            } finally { frame.recycle() }
        } finally { file.delete() }
    }

    private fun footer(bitmap: Bitmap): IntArray = IntArray(960 * 60).also { pixels ->
        bitmap.getPixels(pixels, 0, 960, 60, 1790, 960, 60)
    }
}
