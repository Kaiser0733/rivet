package com.kaiser.rivet.replay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

internal data class ReplayFramePalette(
    val background: Int,
    val surface: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val hairline: Int,
)

internal class ReplayFrames(
    private val story: ReplayStory,
    private val palette: ReplayFramePalette,
) {
    /** The caller owns the returned frame. Screenshot decoding finishes before allocation. */
    fun render(
        beat: ReplayBeat,
        watermark: Boolean,
        width: Int = WIDTH,
        height: Int = HEIGHT,
    ): Bitmap {
        require(width > 0 && width * 16 == height * 9)
        val shot = (beat as? ReplayBeat.PreviewCard)?.let { decodeShot(it.shotPath, width, height) }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(palette.background)
            canvas.scale(width / WIDTH.toFloat(), height / HEIGHT.toFloat())
            line(canvas, story.projectName, 72f, 94f, 936, 32f, palette.text, mono = true)
            story.handle?.takeIf(String::isNotBlank)?.let {
                line(canvas, it, 72f, 150f, 936, 28f, palette.muted, mono = true)
            }
            canvas.drawRect(72f, 211f, 1008f, 213f, Paint().apply { color = palette.hairline })

            when (beat) {
                is ReplayBeat.PromptCard -> textCard(canvas, "Prompt", beat.text)
                is ReplayBeat.StepCard -> textCard(canvas, "Work", beat.title, beat.detail)
                is ReplayBeat.ResultCard -> textCard(canvas, "Result", beat.text)
                is ReplayBeat.PreviewCard ->
                    if (shot == null) textCard(canvas, "Work", beat.caption, "Screenshot unavailable.")
                    else shotCard(canvas, shot, beat.caption)
            }
            if (watermark) {
                line(canvas, "built with Rivet", 72f, 1795f, 936, 27f, palette.muted, mono = true,
                    align = Layout.Alignment.ALIGN_OPPOSITE)
            }
            return bitmap
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
    }

    private fun textCard(canvas: Canvas, label: String, text: String, detail: String? = null) {
        val eyebrow = layout(label, 808, 32f, palette.accent, mono = true)
        val heading = layout(text, 808, if (text.length > 220) 53f else 62f,
            palette.text, maxLines = if (detail == null) 14 else 8, bold = true)
        val description = detail?.let { layout(it, 808, 38f, palette.muted, maxLines = 6, mono = true) }
        val cardHeight = 128f + eyebrow.height + 30f + heading.height +
            (description?.let { 32f + it.height } ?: 0f)
        val top = 960f - cardHeight / 2
        panel(canvas, RectF(72f, top, 1008f, top + cardHeight))
        val labelY = top + 64f
        draw(canvas, eyebrow, 136f, labelY)
        val headingY = labelY + eyebrow.height + 30f
        draw(canvas, heading, 136f, headingY)
        if (description != null) draw(canvas, description, 136f, headingY + heading.height + 32f)
    }

    private data class ShotPixels(val width: Int, val height: Int, val colors: IntArray)

    private fun decodeShot(path: String, width: Int, height: Int): ShotPixels? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample / 2 >= width || bounds.outHeight / sample / 2 >= height) sample *= 2
        val shot = BitmapFactory.decodeFile(path,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        try {
            val pixels = IntArray(shot.width * shot.height)
            shot.getPixels(pixels, 0, shot.width, 0, 0, shot.width, shot.height)
            return ShotPixels(shot.width, shot.height, pixels)
        } finally { shot.recycle() }
    }

    // The pixel-array overload keeps the decoded Bitmap and output Bitmap from overlapping.
    @Suppress("DEPRECATION")
    private fun shotCard(canvas: Canvas, shot: ShotPixels, caption: String) {
        val viewport = RectF(88f, 330f, 992f, 1530f)
        val scale = minOf(viewport.width() / shot.width, viewport.height() / shot.height)
        val left = viewport.centerX() - shot.width * scale / 2
        val top = viewport.centerY() - shot.height * scale / 2
        panel(canvas, RectF(72f, 314f, 1008f, 1546f))
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        canvas.drawBitmap(shot.colors, 0, shot.width, 0f, 0f, shot.width, shot.height, true,
            Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        line(canvas, caption, 72f, 1582f, 936, 32f, palette.muted, mono = true)
    }

    private fun panel(canvas: Canvas, rect: RectF) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = palette.surface }
        canvas.drawRoundRect(rect, 24f, 24f, paint)
        paint.color = palette.hairline
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(rect, 24f, 24f, paint)
    }

    private fun line(
        canvas: Canvas, text: String, x: Float, y: Float, width: Int, size: Float, color: Int,
        mono: Boolean = false, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ) = draw(canvas, layout(text, width, size, color, mono = mono, align = align), x, y)

    private fun layout(
        text: String, width: Int, size: Float, color: Int, maxLines: Int = 1,
        mono: Boolean = false, bold: Boolean = false,
        align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    ): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.DEFAULT_BOLD
                else -> Typeface.DEFAULT
            }
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(align)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
    }

    private fun draw(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
        canvas.save()
        canvas.translate(x, y)
        layout.draw(canvas)
        canvas.restore()
    }

    companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1920
    }
}
