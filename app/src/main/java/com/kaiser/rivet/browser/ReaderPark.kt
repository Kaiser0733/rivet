package com.kaiser.rivet.browser

/** The WebView calls the session can make; a fake records them in unit tests. */
interface RendererSurface {
    fun pause()
    fun setVisible(visible: Boolean)
    fun resume()
}

object ReaderPark {
    enum class Mode { ReaderOn, ReaderOff }

    /** The one exit operation used by navigation, window switch, close, and the toggle. */
    fun unpark(surface: RendererSurface) {
        apply(surface, Mode.ReaderOff)
    }

    fun apply(surface: RendererSurface, mode: Mode) {
        when (mode) {
            Mode.ReaderOn -> {
                surface.pause()
                surface.setVisible(false)
            }
            Mode.ReaderOff -> {
                surface.setVisible(true)
                surface.resume()
            }
        }
    }
}
