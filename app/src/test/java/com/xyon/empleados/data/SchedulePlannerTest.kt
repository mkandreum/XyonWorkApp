package com.xyon.empleados.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class SchedulePlannerTest {

    private val monday = LocalDate.of(2026, 10, 19)
    private val now = LocalDateTime.of(2026, 10, 19, 7, 0)

    private fun shift(
        name: String = "Mañana",
        start: String = "08:00",
        end: String = "16:00",
        activeDays: String = "LUNES,MARTES,MIERCOLES,JUEVES,VIERNES,SABADO,DOMINGO",
        afternoonStart: String? = null,
    ) = DepartmentShiftInfo(
        id = name,
        department = "IT",
        name = name,
        activeDays = activeDays,
        horaEntrada = start,
        horaSalida = end,
        horaEntradaTarde = afternoonStart,
        horaSalidaManana = afternoonStart?.let { "13:00" },
    )

    private fun plan(
        shifts: List<DepartmentShiftInfo>,
        assignments: List<ShiftAssignmentInfo> = emptyList(),
        overrides: List<ScheduleOverrideInfo> = emptyList(),
        vacations: List<VacationRequest> = emptyList(),
        at: LocalDateTime = now,
    ) = SchedulePlanner.plan(
        from = monday,
        shifts = shifts,
        assignments = assignments,
        overrides = overrides,
        vacations = vacations,
        now = at,
        days = 1,
    )

    private fun List<PlannedReminder>.entries() = filter { it.kind == PlannedReminder.KIND_ENTRY }
    private fun List<PlannedReminder>.exits() = filter { it.kind == PlannedReminder.KIND_EXIT }

    @Test
    fun `schedules five minutes before the assigned shift`() {
        val result = plan(
            shifts = listOf(shift()),
            assignments = listOf(ShiftAssignmentInfo(date = "2026-10-19T00:00:00.000Z", shift = shift())),
        )

        assertEquals(1, result.entries().size)
        assertEquals("2026-10-19", result.entries()[0].date)
        assertEquals("07:55", result.entries()[0].time)
        // The existing "¿Ya has terminado?" nudge is preserved.
        assertEquals(1, result.exits().size)
        assertEquals("16:15", result.exits()[0].time)
    }

    @Test
    fun `uses the only active department shift when there is no assignment`() {
        val result = plan(shifts = listOf(shift()))

        assertEquals(1, result.entries().size)
        assertEquals("07:55", result.entries()[0].time)
    }

    @Test
    fun `does not guess when several shifts are active and none is assigned`() {
        val result = plan(shifts = listOf(shift(name = "Mañana"), shift(name = "Tarde", start = "15:00")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `ignores inactive weekdays`() {
        val result = plan(shifts = listOf(shift(activeDays = "MARTES")))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `skips approved vacations and absences`() {
        val vacation = VacationRequest(
            startDate = "2026-10-19",
            endDate = "2026-10-19",
            status = "APPROVED",
            type = "VACATION",
        )

        assertTrue(plan(shifts = listOf(shift()), vacations = listOf(vacation)).isEmpty())
    }

    @Test
    fun `keeps the reminder while a request is still pending`() {
        val pending = VacationRequest(
            startDate = "2026-10-19",
            endDate = "2026-10-19",
            status = "PENDING",
            type = "VACATION",
        )

        assertEquals(1, plan(shifts = listOf(shift()), vacations = listOf(pending)).entries().size)
    }

    @Test
    fun `honours a day off override`() {
        val dayOff = ScheduleOverrideInfo(
            date = "2026-10-19",
            scope = "USER",
            dayOff = true,
        )

        assertTrue(plan(shifts = listOf(shift()), overrides = listOf(dayOff)).isEmpty())
    }

    @Test
    fun `user override wins over the department override`() {
        val department = ScheduleOverrideInfo(date = "2026-10-19", scope = "DEPARTMENT", horaEntrada = "09:00")
        val user = ScheduleOverrideInfo(date = "2026-10-19", scope = "USER", horaEntrada = "10:00")

        val result = plan(shifts = listOf(shift()), overrides = listOf(department, user))

        assertEquals(1, result.entries().size)
        assertEquals("09:55", result.entries()[0].time)
    }

    @Test
    fun `an override with custom hours replaces both split starts`() {
        val override = ScheduleOverrideInfo(date = "2026-10-19", scope = "USER", horaEntrada = "09:00")

        val result = plan(
            shifts = listOf(shift(afternoonStart = "14:00")),
            overrides = listOf(override),
        )

        assertEquals(1, result.entries().size)
        assertEquals("08:55", result.entries()[0].time)
    }

    @Test
    fun `split shift produces a reminder for each start and each end`() {
        val result = plan(shifts = listOf(shift(afternoonStart = "14:00")))

        assertEquals(listOf("07:55", "13:55"), result.entries().map { it.time })
        assertEquals(listOf("13:15", "16:15"), result.exits().map { it.time })
    }

    @Test
    fun `does not schedule a start that is already in the past`() {
        val result = plan(
            shifts = listOf(shift()),
            at = LocalDateTime.of(2026, 10, 19, 8, 0),
        )

        assertTrue(result.entries().isEmpty())
        assertEquals(1, result.exits().size)
    }

    @Test
    fun `a department day off also cancels the reminder`() {
        val dayOff = ScheduleOverrideInfo(date = "2026-10-19", scope = "DEPARTMENT", dayOff = true)

        assertTrue(plan(shifts = listOf(shift()), overrides = listOf(dayOff)).isEmpty())
    }

    @Test
    fun `night shift schedules its exit after midnight`() {
        val night = shift(start = "22:00", end = "06:00")
        val result = plan(shifts = listOf(night), at = LocalDateTime.of(2026, 10, 19, 12, 0))

        assertEquals(1, result.entries().size)
        assertEquals("21:55", result.entries()[0].time)
        assertEquals(1, result.exits().size)
        assertEquals("2026-10-20", result.exits()[0].date)
        assertEquals("06:15", result.exits()[0].time)
    }

    @Test
    fun `a still-pending overnight exit survives a replan after midnight`() {
        val night = shift(start = "22:00", end = "06:00")
        val result = plan(
            shifts = listOf(night),
            at = LocalDateTime.of(2026, 10, 20, 1, 0),
        )

        // The 06:15 exit from Monday's 22:00 shift must still be in the plan.
        assertTrue(result.exits().any { it.date == "2026-10-20" && it.time == "06:15" })
        assertTrue(result.entries().isEmpty())
    }

    @Test
    fun `legacy per-shift override can cancel a day`() {
        val legacy = shift().copy(
            scheduleOverrides = buildJsonObject {
                putJsonObject("2026-10-19") { put("dayOff", true) }
            },
        )

        assertTrue(plan(shifts = listOf(legacy)).isEmpty())
    }
}
