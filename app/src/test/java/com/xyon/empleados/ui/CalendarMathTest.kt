package com.xyon.empleados.ui

import com.xyon.empleados.data.DaySchedule
import com.xyon.empleados.data.Fichaje
import com.xyon.empleados.data.FichajeAdjustment
import com.xyon.empleados.data.FichajeDayStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class CalendarMathTest {
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun f(id: String, tipo: String, iso: String) = Fichaje(id = id, tipo = tipo, timestamp = iso)
    private fun day(vararg fichajes: Fichaje, hours: Double = 0.0, complete: Boolean = true, late: Boolean = false, early: Boolean = false) =
        FichajeDayStats(
            date = "2026-10-14", fichajes = fichajes.toList(), horasTrabajadas = hours,
            isComplete = complete, isLate = late, isEarlyDeparture = early,
        )

    @Test fun scheduleHoursContinuousAndSplit() {
        assertEquals(8.0, CalendarMath.scheduleHours(DaySchedule("07:00", "15:00")), 0.0001)
        // 09:00-14:00 and 16:00-19:00 → 8 h, the 2 h lunch gap is not counted
        assertEquals(8.0, CalendarMath.scheduleHours(DaySchedule("09:00", "19:00", "16:00", "14:00")), 0.0001)
        assertEquals(0.0, CalendarMath.scheduleHours(null), 0.0)
        assertEquals(0.0, CalendarMath.scheduleHours(DaySchedule("xx", "15:00")), 0.0)
    }

    @Test fun scheduleThatCrossesMidnightCountsTheNight() {
        assertEquals(8.0, CalendarMath.scheduleHours(DaySchedule("22:00", "06:00")), 0.0001)
    }

    @Test fun expectedHoursUsesServerScheduleOtherwiseWeekdayDefault() {
        val monday = LocalDate.of(2026, 10, 19)
        val saturday = LocalDate.of(2026, 10, 24)
        assertEquals(8.0, CalendarMath.expectedHours(monday, null), 0.0)
        assertEquals(0.0, CalendarMath.expectedHours(saturday, null), 0.0)
        val short = day().copy(daySchedule = DaySchedule("08:00", "14:00"))
        assertEquals(6.0, CalendarMath.expectedHours(monday, short), 0.0001)
    }

    @Test fun todaysOpenEntryCountsUpToNow() {
        val stats = day(f("1", "ENTRADA", "2026-10-20T05:00:00Z"), hours = 0.0)
        val now = at("2026-10-20T07:30:00Z")
        assertEquals(2.5, CalendarMath.workedHours(stats, now, isToday = true), 0.0001)
        // On a past day the open entry is not extended
        assertEquals(0.0, CalendarMath.workedHours(stats, now, isToday = false), 0.0)
    }

    @Test fun workedHoursAddsOpenEntryToClosedPairs() {
        val stats = day(
            f("1", "ENTRADA", "2026-10-20T05:00:00Z"), f("2", "SALIDA", "2026-10-20T09:00:00Z"),
            f("3", "ENTRADA", "2026-10-20T10:00:00Z"), hours = 4.0,
        )
        assertEquals(5.0, CalendarMath.workedHours(stats, at("2026-10-20T11:00:00Z"), isToday = true), 0.0001)
    }

    @Test fun formatting() {
        assertEquals("8:34", CalendarMath.formatHM(8.5667))
        assertEquals("0:05", CalendarMath.formatHM(0.0834))
        assertEquals("111h 52m", CalendarMath.formatHoursMinutes(111.8667))
        assertEquals("8h", CalendarMath.formatHoursMinutes(8.0))
        assertEquals("0h", CalendarMath.formatHoursMinutes(-1.0))
    }

    @Test fun pairsKeepOrderAndLeaveTheOpenEntryOpen() {
        val list = listOf(
            f("3", "ENTRADA", "2026-10-20T10:00:00Z"),
            f("1", "ENTRADA", "2026-10-20T05:00:00Z"),
            f("2", "SALIDA", "2026-10-20T09:00:00Z"),
        )
        val pairs = CalendarMath.pairs(list, at("2026-10-20T11:00:00Z"))
        assertEquals(2, pairs.size)
        assertEquals("2", pairs[0].salida?.id)
        assertEquals(4.0, pairs[0].hours, 0.0001)
        assertNull(pairs[1].salida)
        assertEquals(1.0, pairs[1].hours, 0.0001)
    }

    @Test fun lateDayIsUnresolvedUntilAnAdjustmentCoversIt() {
        val stats = day(f("a", "ENTRADA", "2026-10-14T07:40:00Z"), f("b", "SALIDA", "2026-10-14T15:00:00Z"), late = true)
        assertTrue(CalendarMath.hasUnresolvedIssue(stats, emptyList(), isToday = false))
        val pending = FichajeAdjustment(id = "r1", fichajeId = "b", status = "PENDING")
        assertFalse(CalendarMath.hasUnresolvedIssue(stats, listOf(pending), isToday = false))
        val rejected = FichajeAdjustment(id = "r2", fichajeId = "b", status = "REJECTED")
        assertTrue(CalendarMath.hasUnresolvedIssue(stats, listOf(rejected), isToday = false))
    }

    @Test fun openDayIsOnlyAnIssueOnceItIsPast() {
        val stats = day(f("a", "ENTRADA", "2026-10-14T07:00:00Z"), complete = false)
        assertFalse(CalendarMath.hasUnresolvedIssue(stats, emptyList(), isToday = true))
        assertTrue(CalendarMath.hasUnresolvedIssue(stats, emptyList(), isToday = false))
        assertFalse(CalendarMath.hasUnresolvedIssue(null, emptyList(), isToday = false))
    }

    @Test fun overtimeNeedsMoreThanFifteenMinutes() {
        assertTrue(CalendarMath.isOvertime(8.5, 8.0))
        assertFalse(CalendarMath.isOvertime(8.2, 8.0))
        assertFalse(CalendarMath.isOvertime(10.0, 0.0))
    }
}
