package com.kaiser.rivet.browser

/** Pure gate for the AndroidX renderer-terminate call. */
enum class RendererTerminationOutcome {
    Unsupported,
    NoHandle,
    Requested,
    Refused,
}

enum class RendererTeardownStep {
    RemoveFromParent,
    StopLoading,
    ClearHistory,
    TerminateRenderer,
    Destroy,
}

object RendererTerminationPolicy {
    /** The one strict order used when a renderer is released. */
    fun teardownSteps(featureSupported: Boolean, handleAvailable: Boolean): List<RendererTeardownStep> =
        buildList {
            add(RendererTeardownStep.RemoveFromParent)
            add(RendererTeardownStep.StopLoading)
            add(RendererTeardownStep.ClearHistory)
            if (featureSupported && handleAvailable) add(RendererTeardownStep.TerminateRenderer)
            add(RendererTeardownStep.Destroy)
        }

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
