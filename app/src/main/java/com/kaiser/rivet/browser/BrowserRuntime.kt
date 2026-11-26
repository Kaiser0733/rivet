package com.kaiser.rivet.browser

import android.app.Application
import com.kaiser.rivet.local.LocalModelManager

/** Process-wide owner for the single browser session used by the foreground chat. */
object BrowserRuntime {
    @Volatile private var current: BrowserSession? = null
    private val projects = mutableMapOf<String, BrowserSession>()

    fun forApp(app: Application, models: LocalModelManager?): BrowserSession =
        current ?: synchronized(this) {
            current ?: BrowserSession(app.applicationContext as Application, models).also { current = it }
        }

    /**
     * One session per project. Each session owns its URL, tabs, reader cache, and
     * renderer handshake; only the viewed project's session is attached to the UI.
     */
    fun forProject(app: Application, models: LocalModelManager?, key: String): BrowserSession {
        val identity = key.ifBlank { "unbound" }
        if (identity == "unbound") return forApp(app, models)
        synchronized(this) {
            projects[identity]?.let { return it }
            return BrowserSession(app.applicationContext as Application, models)
                .also { it.bindProject(identity); projects[identity] = it }
        }
    }

    fun peek(): BrowserSession? = current
}
