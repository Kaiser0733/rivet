package com.kaiser.rivet.browser

import java.lang.ref.WeakReference

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

/**
 * Tracks live Chromium render process handles across navigations, error transitions, and
 * detached views using weak references so a later Close terminates every surviving process.
 */
class RendererProcessRegistry {
    private class TrackedEntry(
        val ref: WeakReference<Any>,
        val terminate: (Any) -> Boolean,
        val viewRef: WeakReference<Any>?,
    )

    private val entries = mutableListOf<TrackedEntry>()

    @Synchronized
    fun <T : Any> record(process: T?, ownerView: Any? = null, terminate: (T) -> Boolean) {
        pruneClearedLocked()
        if (process == null) return
        if (entries.any { entry ->
                entry.viewRef?.get() === ownerView && entry.ref.get() == process
            }) return
        @Suppress("UNCHECKED_CAST")
        entries += TrackedEntry(
            ref = WeakReference(process),
            terminate = { obj -> terminate(obj as T) },
            viewRef = ownerView?.let(::WeakReference),
        )
    }

    @Synchronized
    fun onProcessGoneForView(ownerView: Any) {
        entries.removeAll { entry ->
            val p = entry.ref.get()
            val v = entry.viewRef?.get()
            p == null || v === ownerView
        }
    }

    fun terminateForView(ownerView: Any, supported: Boolean): Int {
        if (!supported) return 0
        val owned = synchronized(this) {
            pruneClearedLocked()
            val targets = entries.filter { it.viewRef?.get() === ownerView }
            entries.removeAll(targets.toSet())
            targets.mapNotNull { entry ->
                val process = entry.ref.get() ?: return@mapNotNull null
                if (entries.any { it.ref.get() == process }) null else process to entry.terminate
            }
        }
        return owned.count { (process, terminate) ->
            runCatching { terminate(process) }.getOrDefault(false)
        }
    }

    @Synchronized
    fun liveCount(): Int {
        pruneClearedLocked()
        return entries.size
    }

    private fun pruneClearedLocked() {
        entries.removeAll { it.ref.get() == null }
    }
}
