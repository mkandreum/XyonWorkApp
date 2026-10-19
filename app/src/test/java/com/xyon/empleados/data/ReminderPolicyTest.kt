package com.xyon.empleados.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderPolicyTest {

    private fun millis(dateTime: LocalDateTime): Long =
        dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val shiftStart = millis(LocalDateTime.of(2026, 10, 19, 22, 0))

    @Test
    fun `entry is skipped when already clocked in`() {
        assertFalse(ReminderPolicy.shouldNotifyEntry(clockedIn = true))
        assertTrue(ReminderPolicy.shouldNotifyEntry(clockedIn = false))
    }

    @Test
    fun `exit is skipped when not clocked in`() {
        assertFalse(ReminderPolicy.shouldNotifyExit(clockedIn = false, entryAt = shiftStart, shiftStart = shiftStart))
    }

    @Test
    fun `overnight exit keeps the entry from the previous day`() {
        // 22:00 entry, 06:15 exit next day: the entry is still the current one.
        assertTrue(ReminderPolicy.shouldNotifyExit(clockedIn = true, entryAt = shiftStart, shiftStart = shiftStart))
    }

    @Test
    fun `exit ignores an entry left open from an older shift`() {
        val twoDaysEarlier = shiftStart - 48L * 60 * 60 * 1000
        assertFalse(ReminderPolicy.shouldNotifyExit(clockedIn = true, entryAt = twoDaysEarlier, shiftStart = shiftStart))
    }

    @Test
    fun `exit allows an entry a few minutes before the shift`() {
        val fiveMinutesEarly = shiftStart - 5L * 60 * 1000
        assertTrue(ReminderPolicy.shouldNotifyExit(clockedIn = true, entryAt = fiveMinutesEarly, shiftStart = shiftStart))
    }

    @Test
    fun `exit falls back to the open-entry flag when timestamps are missing`() {
        assertTrue(ReminderPolicy.shouldNotifyExit(clockedIn = true, entryAt = 0L, shiftStart = 0L))
    }
}
