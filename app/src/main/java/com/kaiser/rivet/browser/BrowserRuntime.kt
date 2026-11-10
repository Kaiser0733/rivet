package com.kaiser.rivet.browser

import android.app.Application
import com.kaiser.rivet.local.LocalModelManager

/** Process-wide owner for the single browser session used by the foreground chat. */
object BrowserRuntime {
    @Volatile private var current: BrowserSession? = null

    fun forApp(app: Application, models: LocalModelManager?): BrowserSession =
        current ?: synchronized(this) {
            current ?: BrowserSession(app.applicationContext as Application, models).also { current = it }
        }

    fun peek(): BrowserSession? = current
}
