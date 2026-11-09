package com.kaiser.rivet.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalGovernorTest {
    private val mb = 1L shl 20
    private val gb = 1L shl 30

    @Test fun memoryCapClampsDeviceRamMinusReserve() {
        assertEquals(768 * mb, memoryCapBytes(12 * gb))
        assertEquals(512 * mb, memoryCapBytes(512 * mb))
        assertEquals(256 * mb, memoryCapBytes(128 * mb))
        assertEquals(512 * mb, memoryCapBytes(2 * gb, 1536 * mb))
        assertEquals(256 * mb, memoryCapBytes(1 * gb, 1 * gb))
    }

    @Test fun breachNeedsTwoConsecutiveSamples() {
        val cap = 100L
        val first = memoryBreachSamples(0, 101, cap)
        assertEquals(1, first)
        assertFalse(memoryLimitReached(first))

        val second = memoryBreachSamples(first, 120, cap)
        assertEquals(2, second)
        assertTrue(memoryLimitReached(second))

        assertEquals(0, memoryBreachSamples(1, 99, cap))
    }

    @Test fun severeThermalAndLowBatteryBlockOnlyLongCommands() {
        val severe = GateReadings(thermalStatus = THERMAL_SEVERE, batteryPercent = 80)
        assertEquals("thermal_blocked", commandGate(LONG_COMMAND_MS + 1, severe))
        assertNull(commandGate(LONG_COMMAND_MS, severe))
        assertNull(commandGate(30_000, GateReadings(thermalStatus = THERMAL_SERIOUS)))

        val low = GateReadings(batteryPercent = BATTERY_LOW_PERCENT - 1)
        assertEquals("battery_low", commandGate(300_000, low))
        assertNull(commandGate(300_000, low.copy(charging = true)))
        assertNull(commandGate(300_000, low.copy(batteryPercent = BATTERY_LOW_PERCENT)))
        assertNull(commandGate(300_000, low.copy(batteryPercent = null)))
        assertNull(commandGate(30_000, low))
    }

    @Test fun seriousThermalThrottlesSlotsToOne() {
        assertEquals(ManagedProcesses.MAX_ACTIVE_PROCESSES, effectiveSlots(0))
        assertEquals(ManagedProcesses.MAX_ACTIVE_PROCESSES, effectiveSlots(THERMAL_SERIOUS - 1))
        assertEquals(1, effectiveSlots(THERMAL_SERIOUS))
        assertEquals(1, effectiveSlots(THERMAL_SEVERE))
    }

    @Test fun storageWarnsAtEightHundredMegabytesAndStopsAtOneGigabyte() {
        assertEquals(StorageLevel.Ready, storageLevel(STORAGE_WARN_BYTES - 1))
        assertEquals(StorageLevel.Warn, storageLevel(STORAGE_WARN_BYTES))
        assertEquals(StorageLevel.Warn, storageLevel(STORAGE_CAP_BYTES - 1))
        assertEquals(StorageLevel.Full, storageLevel(STORAGE_CAP_BYTES))

        assertTrue(storageAdmits(STORAGE_CAP_BYTES - 10, 10))
        assertFalse(storageAdmits(STORAGE_CAP_BYTES - 10, 11))
        assertFalse(storageAdmits(STORAGE_CAP_BYTES, 0))
        assertFalse(storageAdmits(0, -1))
    }

    @Test fun stateLineNamesTheGoverningCondition() {
        assertEquals("Terminal: ready", terminalStateLine(GateReadings(), 0, 0, null))
        assertEquals("Terminal: busy (2 of 4 slots)",
            terminalStateLine(GateReadings(), 2, 0, null))
        assertEquals("Terminal: paused — phone is too hot",
            terminalStateLine(GateReadings(thermalStatus = THERMAL_SEVERE), 0, 0, null))
        assertEquals("Terminal: paused — battery low",
            terminalStateLine(GateReadings(batteryPercent = 10), 0, 0, null))
        assertEquals("Terminal: paused — storage full",
            terminalStateLine(GateReadings(), 0, STORAGE_CAP_BYTES, null))
        assertEquals("Terminal: stopped a command — memory limit",
            terminalStateLine(GateReadings(), 0, 0, "memory limit"))
        assertEquals("Terminal: ready · storage 812 MB of 1 GB",
            terminalStateLine(GateReadings(), 0, 812_000_000, null))
        assertEquals("Terminal: busy (1 of 1 slot)",
            terminalStateLine(GateReadings(thermalStatus = THERMAL_SERIOUS), 1, 0, null))
        assertEquals("Terminal: ready — phone is warm",
            terminalStateLine(GateReadings(thermalStatus = THERMAL_SERIOUS), 0, 0, null))
    }
}
