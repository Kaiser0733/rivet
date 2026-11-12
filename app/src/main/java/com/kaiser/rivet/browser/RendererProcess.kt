package com.kaiser.rivet.browser

/** A same-uid process PSS reading; the WebView renderer is one of these sandboxes. */
data class RendererProcess(val pid: Int, val uid: Int, val pssBytes: Long)

/** Selects the largest same-uid non-main process; never guesses a process name. */
fun selectRendererPss(processes: List<RendererProcess>, ownPid: Int, ownUid: Int): Long? =
    processes.asSequence()
        .filter { it.uid == ownUid && it.pid != ownPid }
        .maxOfOrNull { it.pssBytes.coerceAtLeast(0L) }
