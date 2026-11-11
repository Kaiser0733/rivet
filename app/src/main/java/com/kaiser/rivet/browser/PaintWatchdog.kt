package com.kaiser.rivet.browser

/** One restart per page after a blank post-load sample; a second blank becomes an error. */
class PaintWatchdog {
    enum class Directive { None, Arm, Restart, Stop }

    private val armed = mutableSetOf<String>()
    private val restarted = mutableSetOf<String>()

    fun onPageFinished(url: String): Directive {
        if (url.isBlank() || url == "about:blank") return Directive.None
        armed += url
        return Directive.Arm
    }

    fun onSample(url: String, verdict: PaintVerdict): Directive {
        if (url !in armed) return Directive.None
        if (verdict == PaintVerdict.Painted || verdict == PaintVerdict.Unknown) return Directive.None
        return if (restarted.add(url)) Directive.Restart else Directive.Stop
    }
}
