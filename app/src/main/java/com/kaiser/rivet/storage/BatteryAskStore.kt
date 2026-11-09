package com.kaiser.rivet.storage

import android.content.Context

/** The battery exemption prompt is a one-time door, not a recurring nag. */
class BatteryAskStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("battery_ask", Context.MODE_PRIVATE)

    fun shouldAsk(): Boolean = !preferences.getBoolean(ASKED, false)

    fun markAsked() {
        preferences.edit().putBoolean(ASKED, true).apply()
    }

    companion object { private const val ASKED = "asked" }
}
