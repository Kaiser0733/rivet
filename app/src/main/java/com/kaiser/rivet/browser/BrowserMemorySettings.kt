package com.kaiser.rivet.browser

import android.content.Context

internal class BrowserMemorySettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("browser_memory", Context.MODE_PRIVATE)

    fun limit(): BrowserMemoryLimit = when (preferences.getInt("limit_mb", BrowserMemoryLimit.Gb2.megabytes)) {
        BrowserMemoryLimit.Mb250.megabytes -> BrowserMemoryLimit.Mb250
        else -> BrowserMemoryLimit.Gb2
    }

    fun setLimit(limit: BrowserMemoryLimit) {
        preferences.edit().putInt("limit_mb", limit.megabytes).apply()
    }
}
