package com.kaiser.rivet.browser

/**
 * Ordered ownership hand-off for the single WebView.
 *
 * Session: Live -> DetachRequested. Composition removes the view. Host: Released.
 * Only then may the host destroy it: Released -> Destroyed.
 */
class RendererTeardown {
    enum class State { Live, DetachRequested, Released, Destroyed }

    var state: State = State.Live
        private set

    fun requestDetach(): Boolean {
        if (state != State.Live) return false
        state = State.DetachRequested
        return true
    }

    fun release(): Boolean {
        if (state != State.DetachRequested) return false
        state = State.Released
        return true
    }

    fun destroy(): Boolean {
        if (state != State.Released) return false
        state = State.Destroyed
        return true
    }
}
