package com.kaiser.rivet.browser

import kotlin.math.abs

/** Pure decision for a DOM sample plus a PixelCopy near-white or uniform-dark sample. */
enum class PaintVerdict {
    Painted,
    Stale,
    BlankWithDom,
    BlankNoDom,
    Unknown;

    companion object {
        private const val BLANK_NEAR_WHITE = 0.985f
        private const val BLANK_UNIFORM_DARK = 0.985f
        private const val DARK_CHANNEL_MAX = 48
        private const val DARK_CHANNEL_DELTA = 10

        fun decide(
            domTextLength: Int,
            sampled: Boolean,
            nearWhiteFraction: Float,
            domScrolled: Boolean = false,
            frameChanged: Boolean = true,
            uniformDarkFraction: Float = 0f,
        ): PaintVerdict {
            if (!sampled) return Unknown
            if (domScrolled && !frameChanged) return Stale
            if (nearWhiteFraction < BLANK_NEAR_WHITE && uniformDarkFraction < BLANK_UNIFORM_DARK) {
                return Painted
            }
            return if (domTextLength > 0) BlankWithDom else BlankNoDom
        }

        fun classifyPixels(domTextLength: Int, sampledRgbPixels: IntArray): PaintVerdict {
            if (sampledRgbPixels.isEmpty()) return Unknown
            val first = sampledRgbPixels[0]
            val baseR = (first shr 16) and 0xff
            val baseG = (first shr 8) and 0xff
            val baseB = first and 0xff
            val baseDark = baseR <= DARK_CHANNEL_MAX && baseG <= DARK_CHANNEL_MAX && baseB <= DARK_CHANNEL_MAX
            var nearWhite = 0
            var uniformDark = 0
            for (color in sampledRgbPixels) {
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                if (r >= 245 && g >= 245 && b >= 245) nearWhite++
                if (baseDark &&
                    r <= DARK_CHANNEL_MAX &&
                    g <= DARK_CHANNEL_MAX &&
                    b <= DARK_CHANNEL_MAX &&
                    abs(r - baseR) <= DARK_CHANNEL_DELTA &&
                    abs(g - baseG) <= DARK_CHANNEL_DELTA &&
                    abs(b - baseB) <= DARK_CHANNEL_DELTA
                ) {
                    uniformDark++
                }
            }
            val total = sampledRgbPixels.size.toFloat()
            return decide(
                domTextLength = domTextLength,
                sampled = true,
                nearWhiteFraction = nearWhite / total,
                uniformDarkFraction = uniformDark / total,
            )
        }
    }
}
