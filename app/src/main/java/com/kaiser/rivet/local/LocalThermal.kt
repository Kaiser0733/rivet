package com.kaiser.rivet.local

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager

/** Local inference is a burst activity. A hot phone gets an honest pause, not a crash. */
object LocalThermal {
    fun pauseReason(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val power = context.getSystemService(PowerManager::class.java)
            val status = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
                return "The phone is hot, so Rivet paused this local run. Let it cool for a minute, then try again."
            }
        }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        if (level >= 0 && scale > 0 && plugged == 0 && level * 100 / scale <= 10) {
            return "The battery is low, so Rivet paused this local run. Charge the phone or pick a cloud model."
        }
        return null
    }
}
