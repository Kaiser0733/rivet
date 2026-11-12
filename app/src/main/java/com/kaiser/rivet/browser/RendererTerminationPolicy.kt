package com.kaiser.rivet.browser

/** Pure gate for the AndroidX renderer-terminate call. */
enum class RendererTerminationOutcome {
    Unsupported,
    NoHandle,
    Requested,
    Refused,
}

object RendererTerminationPolicy {
    fun request(featureSupported: Boolean, handleAvailable: Boolean,
                terminate: () -> Boolean): RendererTerminationOutcome {
        if (!featureSupported) return RendererTerminationOutcome.Unsupported
        if (!handleAvailable) return RendererTerminationOutcome.NoHandle
        return if (runCatching(terminate).getOrDefault(false)) {
            RendererTerminationOutcome.Requested
        } else {
            RendererTerminationOutcome.Refused
        }
    }
}
