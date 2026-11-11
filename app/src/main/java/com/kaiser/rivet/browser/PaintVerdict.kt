package com.kaiser.rivet.browser

/** Pure decision for a DOM sample plus a PixelCopy near-white sample. */
enum class PaintVerdict {
    Painted,
    Stale,
    BlankWithDom,
    BlankNoDom,
    Unknown;

    companion object {
        private const val BLANK_NEAR_WHITE = 0.985f

        fun decide(domTextLength: Int, sampled: Boolean, nearWhiteFraction: Float,
                   domScrolled: Boolean = false, frameChanged: Boolean = true): PaintVerdict {
            if (!sampled) return Unknown
            if (domScrolled && !frameChanged) return Stale
            if (nearWhiteFraction < BLANK_NEAR_WHITE) return Painted
            return if (domTextLength > 0) BlankWithDom else BlankNoDom
        }
    }
}
