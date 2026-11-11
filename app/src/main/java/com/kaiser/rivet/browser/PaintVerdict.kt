package com.kaiser.rivet.browser

/** Pure decision for a DOM sample plus a PixelCopy near-white sample. */
enum class PaintVerdict {
    Painted,
    BlankWithDom,
    BlankNoDom,
    Unknown;

    companion object {
        private const val BLANK_NEAR_WHITE = 0.985f

        fun decide(domTextLength: Int, sampled: Boolean, nearWhiteFraction: Float): PaintVerdict {
            if (!sampled) return Unknown
            if (nearWhiteFraction < BLANK_NEAR_WHITE) return Painted
            return if (domTextLength > 0) BlankWithDom else BlankNoDom
        }
    }
}
