package com.kaiser.rivet.runtime

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import java.nio.file.Files

// Plan §5 rule 7: a tree may use at most clamp(deviceRAM - reserve, 256 MB, 768 MB) of PSS.
internal const val MEMORY_CAP_MIN_BYTES = 256L * 1024 * 1024
internal const val MEMORY_CAP_MAX_BYTES = 768L * 1024 * 1024
internal const val MEMORY_BREACH_SAMPLES = 2

// Plan §5 rules 11/12: "long" is a command expected to run over a minute.
internal const val LONG_COMMAND_MS = 60_000L
internal const val BATTERY_LOW_PERCENT = 15
internal const val THERMAL_SERIOUS = 4
internal const val THERMAL_SEVERE = 5

// Plan §5 rule 10. The plan writes MB/GB; decimal units keep the labels honest.
internal const val STORAGE_WARN_BYTES = 800_000_000L
internal const val STORAGE_CAP_BYTES = 1_000_000_000L
private const val STORAGE_MEASURE_TTL_MS = 30_000L

/** The platform readings a gate decision needs; injected whole so the decision is testable. */
internal data class GateReadings(
    // 0 is PowerManager.THERMAL_STATUS_NONE; the literal keeps the constant off pre-Q lint.
    val thermalStatus: Int = 0,
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
)

internal fun isLongCommand(timeoutMs: Long): Boolean = timeoutMs > LONG_COMMAND_MS

/** Null when the command may start, else the runtime error code. Only long commands are refused. */
internal fun commandGate(timeoutMs: Long, readings: GateReadings): String? {
    if (!isLongCommand(timeoutMs)) return null
    if (readings.thermalStatus >= THERMAL_SEVERE) return "thermal_blocked"
    val battery = readings.batteryPercent
    if (!readings.charging && battery != null && battery < BATTERY_LOW_PERCENT) return "battery_low"
    return null
}

/** Plan §5 rule 12: SERIOUS thermal throttles the rule-1 budget to one active process. */
internal fun effectiveSlots(thermalStatus: Int): Int =
    if (thermalStatus >= THERMAL_SERIOUS) 1 else ManagedProcesses.MAX_ACTIVE_PROCESSES

internal fun memoryCapBytes(deviceRamBytes: Long, reserveBytes: Long = 0L): Long =
    (deviceRamBytes - reserveBytes).coerceIn(MEMORY_CAP_MIN_BYTES, MEMORY_CAP_MAX_BYTES)

/** Consecutive samples over the cap; a single spike never kills a healthy command. */
internal fun memoryBreachSamples(previousSamples: Int, treePssBytes: Long, capBytes: Long): Int =
    if (treePssBytes > capBytes) previousSamples + 1 else 0

internal fun memoryLimitReached(samples: Int): Boolean = samples >= MEMORY_BREACH_SAMPLES

internal enum class StorageLevel { Ready, Warn, Full }

internal fun storageLevel(usedBytes: Long): StorageLevel = when {
    usedBytes >= STORAGE_CAP_BYTES -> StorageLevel.Full
    usedBytes >= STORAGE_WARN_BYTES -> StorageLevel.Warn
    else -> StorageLevel.Ready
}

/** Never true at the cap, so a full Zone D accepts no further bytes. */
internal fun storageAdmits(usedBytes: Long, incomingBytes: Long): Boolean =
    usedBytes < STORAGE_CAP_BYTES && incomingBytes >= 0 &&
        incomingBytes <= STORAGE_CAP_BYTES - usedBytes

private fun storageMegabytes(bytes: Long): Long = bytes / 1_000_000L

/**
 * Plan §5 rule 14: the one plain state sentence shown in the Tools/Processes surface and
 * given to the agent. Priorities are device safety, then the last governor stop, then work.
 */
internal fun terminalStateLine(
    readings: GateReadings,
    activeCount: Int,
    storageUsedBytes: Long,
    lastStop: String?,
): String {
    val storage = storageLevel(storageUsedBytes)
    val slots = effectiveSlots(readings.thermalStatus)
    val battery = readings.batteryPercent
    return when {
        readings.thermalStatus >= THERMAL_SEVERE -> "Terminal: paused — phone is too hot"
        !readings.charging && battery != null && battery < BATTERY_LOW_PERCENT ->
            "Terminal: paused — battery low"
        storage == StorageLevel.Full -> "Terminal: paused — storage full"
        lastStop != null -> "Terminal: stopped a command — $lastStop"
        activeCount > 0 -> "Terminal: busy ($activeCount of $slots ${if (slots == 1) "slot" else "slots"})"
        readings.thermalStatus >= THERMAL_SERIOUS -> "Terminal: ready — phone is warm"
        storage == StorageLevel.Warn ->
            "Terminal: ready · storage ${storageMegabytes(storageUsedBytes)} MB of 1 GB"
        else -> "Terminal: ready"
    }
}

/**
 * Zone D (`filesDir/toolchain`) measurement and admission. Pure thresholds live above;
 * this is the thin impure shell over the directory tree.
 */
internal object ToolchainStorage {
    fun usedBytes(root: File?): Long {
        if (root == null || !root.isDirectory) return 0L
        var total = 0L
        val pending = ArrayDeque<File>()
        pending += root
        while (pending.isNotEmpty()) {
            val directory = pending.removeLast()
            val children = try { directory.listFiles() } catch (_: Exception) { null } ?: continue
            for (child in children) {
                val symlink = try { Files.isSymbolicLink(child.toPath()) }
                    catch (_: Exception) { true }
                if (symlink) continue
                if (child.isDirectory) pending += child
                else if (child.isFile) total += child.length()
            }
        }
        return total
    }

    fun admits(root: File?, incomingBytes: Long): Boolean =
        storageAdmits(usedBytes(root), incomingBytes)
}

/**
 * Reads the device gates. Everything is best-effort: an unavailable reading must behave
 * as "no gate", never as a block or a kill, and no read may throw into a command.
 */
internal class TerminalGovernor(context: Context) {
    private val app = context.applicationContext
    @Volatile private var lastStop: String? = null
    // Zone D can hold tens of thousands of site-packages files later; a turn start must
    // not walk them. The state line and storage decisions share one 30 s measurement.
    private val storageLock = Any()
    private var storageMeasuredAt = 0L
    private var storageBytes = 0L

    fun readings(): GateReadings {
        val thermal = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                    ?.currentThermalStatus ?: 0
            } else 0
        } catch (_: Exception) { 0 }
        val battery = try {
            app.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        } catch (_: Exception) { null }
        val percent = try {
            battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        } catch (_: Exception) { null }
        val charging = try { battery?.isCharging == true } catch (_: Exception) { false }
        return GateReadings(thermal, percent, charging)
    }

    /** Null when the platform will not report RAM: the watchdog stays off, never guesses. */
    fun memoryCapBytes(): Long? = try {
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        if (manager != null) manager.getMemoryInfo(info)
        // No local-model runtime ships in Rivet yet, so the reserve is zero. When one
        // lands, its loaded footprint is subtracted here (plan §5 rule 7).
        if (manager != null && info.totalMem > 0) memoryCapBytes(info.totalMem, 0L) else null
    } catch (_: Exception) { null }

    fun storageUsedBytes(): Long = synchronized(storageLock) {
        val now = SystemClock.elapsedRealtime()
        if (storageMeasuredAt == 0L || now - storageMeasuredAt >= STORAGE_MEASURE_TTL_MS) {
            storageBytes = ToolchainStorage.usedBytes(zoneD())
            storageMeasuredAt = now
        }
        storageBytes
    }

    fun stateLine(activeCount: Int): String =
        terminalStateLine(readings(), activeCount, storageUsedBytes(), lastStop)

    fun recordStop(reason: String) { lastStop = reason }

    fun commandStarted() { lastStop = null }

    private fun zoneD(): File? = try { File(app.filesDir, "toolchain") } catch (_: Exception) { null }
}
