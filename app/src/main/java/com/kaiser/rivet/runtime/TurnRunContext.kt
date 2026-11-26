package com.kaiser.rivet.runtime

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal class TurnRunContext(
    private val sessionId: String?,
    val workspaceId: String? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TurnRunContext>

    private val lock = Any()
    private val processes = mutableSetOf<() -> Unit>()
    private var stopped = false
    private val finishStarted = AtomicBoolean()
    val commandChangesPending = AtomicBoolean()

    fun registerProcess(kill: () -> Unit): () -> Unit {
        val killNow = synchronized(lock) {
            if (stopped) true else {
                processes.add(kill)
                false
            }
        }
        if (killNow) kill()
        return { synchronized(lock) { processes.remove(kill) } }
    }

    fun stopProcesses() {
        val owned = synchronized(lock) {
            stopped = true
            processes.toList().also { processes.clear() }
        }
        owned.forEach { kill -> try { kill() } catch (_: Exception) { } }
    }

    fun finishStart() {
        if (finishStarted.compareAndSet(false, true)) log("finish start")
    }

    fun finishEnd() {
        finishStart()
        log("finish end")
    }

    fun log(phase: String) {
        Log.d("RivetRun", "session=${sessionId ?: "unknown"} phase=$phase")
    }
}

internal suspend inline fun <T> runPhase(name: String, work: () -> T): T {
    val run = currentCoroutineContext()[TurnRunContext]
    run?.log("$name start")
    try {
        return work()
    } finally {
        run?.log("$name end")
    }
}
