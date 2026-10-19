package com.xyon.empleados.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** A clock-in reminder ready to be handed to AlarmManager. */
@Serializable
data class PlannedReminder(
    val date: String,
    val time: String,
    val shiftName: String = "",
    val title: String,
    val text: String,
    /** ENTRY = five minutes before the start; EXIT = nudge after the expected end. */
    val kind: String = KIND_ENTRY,
    /** Local ISO start of the shift this reminder belongs to (used by overnight exits). */
    val shiftStart: String = "",
) {
    /** Local wall-clock time, so a timezone change can safely recompute the alarm. */
    fun atMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    fun requestCode(): Int = "$date|$time|$kind".hashCode()

    companion object {
        const val KIND_ENTRY = "ENTRY"
        const val KIND_EXIT = "EXIT"
    }
}

/**
 * Pure schedule resolver for clock-in reminders.
 *
 * The backend keeps three independent sources of truth:
 *  - [DepartmentShiftInfo]: shifts of the department and their active weekdays.
 *  - [ShiftAssignmentInfo]: the shift assigned to one employee on one date.
 *  - [ScheduleOverrideInfo]: day off / one-off hours for the department or employee.
 * Approved vacations and absences are removed as well.
 *
 * The planner never calls the network, which keeps it easy to test and avoids
 * duplicating timezone/date logic in workers and screens.
 */
object SchedulePlanner {
    private const val MINUTES_BEFORE = 5L
    private const val MINUTES_AFTER_END = 15L
    private const val HORIZON_DAYS = 14

    private val dayNames = mapOf(
        DayOfWeek.MONDAY to "LUNES",
        DayOfWeek.TUESDAY to "MARTES",
        DayOfWeek.WEDNESDAY to "MIERCOLES",
        DayOfWeek.THURSDAY to "JUEVES",
        DayOfWeek.FRIDAY to "VIERNES",
        DayOfWeek.SATURDAY to "SABADO",
        DayOfWeek.SUNDAY to "DOMINGO",
    )

    fun plan(
        from: LocalDate,
        shifts: List<DepartmentShiftInfo>,
        assignments: List<ShiftAssignmentInfo>,
        overrides: List<ScheduleOverrideInfo>,
        vacations: List<VacationRequest>,
        now: LocalDateTime = LocalDateTime.now(),
        days: Int = HORIZON_DAYS,
    ): List<PlannedReminder> {
        if (days <= 0) return emptyList()

        val assignmentByDate = assignments
            .mapNotNull { a -> dateKey(a.date)?.let { it to a } }
            .toMap()
        val userOverrides = overrides
            .filter { it.scope.equals("USER", ignoreCase = true) }
            .mapNotNull { o -> dateKey(o.date)?.let { it to o } }
            .toMap()
        val departmentOverrides = overrides
            .filterNot { it.scope.equals("USER", ignoreCase = true) }
            .mapNotNull { o -> dateKey(o.date)?.let { it to o } }
            .toMap()
        val approvedAbsences = vacations.filter { it.status.equals("APPROVED", ignoreCase = true) }

        val result = LinkedHashMap<String, PlannedReminder>()

        for (offset in 0 until days) {
            val date = from.plusDays(offset.toLong())

            // Vacation / absence approved by a manager or admin: no reminder that day.
            if (approvedAbsences.any { covers(it, date) }) continue

            val override = userOverrides[date] ?: departmentOverrides[date]
            if (override?.dayOff == true) continue

            val assigned = assignmentByDate[date]?.shift?.takeIf { it.horaEntrada.isNotBlank() }
            val active = if (assigned != null) {
                listOf(assigned)
            } else {
                shifts.filter { isActiveOn(it, date.dayOfWeek) }
            }

            // Several shifts active and no per-day assignment: we cannot know which
            // one applies, so we prefer staying silent over sending a wrong reminder.
            if (active.isEmpty() || (assigned == null && active.size > 1)) continue

            for (shift in active) {
                val legacy = shift.scheduleOverrides?.get(date.toString()) as? JsonObject
                if (legacy?.get("dayOff")?.jsonPrimitive?.booleanOrNull == true) continue

                val overrideStart = override?.horaEntrada?.takeIf { it.isNotBlank() }
                val legacyStart = legacy?.get("horaEntrada")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val starts = when {
                    overrideStart != null -> listOf(overrideStart)
                    legacyStart != null -> listOf(legacyStart)
                    else -> buildList {
                        add(shift.horaEntrada)
                        shift.horaEntradaTarde?.takeIf { it.isNotBlank() }?.let(::add)
                    }
                }

                for (rawStart in starts) {
                    val start = parseTime(rawStart) ?: continue
                    val shiftName = shift.name.trim()
                    val startLabel = start.toString().take(5)
                    val shiftStart = date.atTime(start).toString()

                    // Entry: exactly five minutes before the shift starts.
                    val entryAt = date.atTime(start).minusMinutes(MINUTES_BEFORE)
                    if (entryAt.isAfter(now)) {
                        val text = if (shiftName.isEmpty()) {
                            "Tu turno empieza a las $startLabel. No olvides fichar."
                        } else {
                            "Tu turno «$shiftName» empieza a las $startLabel. No olvides fichar."
                        }
                        add(
                            result,
                            PlannedReminder(
                                date = entryAt.toLocalDate().toString(),
                                time = entryAt.toLocalTime().toString().take(5),
                                shiftName = shiftName,
                                title = "Fichaje en 5 minutos",
                                text = text,
                                kind = PlannedReminder.KIND_ENTRY,
                                shiftStart = shiftStart,
                            ),
                        )
                    }

                    // Exit: same nudge as before, 15 minutes after the expected end.
                    val end = expectedEnd(shift, override, legacy, startLabel) ?: continue
                    var exitAt = date.atTime(end).plusMinutes(MINUTES_AFTER_END)
                    if (!end.isAfter(start)) exitAt = exitAt.plusDays(1) // night shift
                    if (!exitAt.isAfter(now)) continue

                    val endLabel = end.toString().take(5)
                    val exitText = if (shiftName.isEmpty()) {
                        "Tu turno acababa a las $endLabel. No olvides fichar la salida."
                    } else {
                        "Tu turno «$shiftName» acababa a las $endLabel. No olvides fichar la salida."
                    }
                    add(
                        result,
                        PlannedReminder(
                            date = exitAt.toLocalDate().toString(),
                            time = exitAt.toLocalTime().toString().take(5),
                            shiftName = shiftName,
                            title = "¿Ya has terminado?",
                            text = exitText,
                            kind = PlannedReminder.KIND_EXIT,
                            shiftStart = shiftStart,
                        ),
                    )
                }
            }
        }

        return result.values.sortedWith(compareBy({ it.date }, { it.time }))
    }

    /** Expected end for one concrete start (morning, afternoon or continuous shift). */
    private fun expectedEnd(
        shift: DepartmentShiftInfo,
        override: ScheduleOverrideInfo?,
        legacy: JsonObject?,
        startLabel: String,
    ): LocalTime? {
        val overrideEnd = override?.horaSalida?.takeIf { it.isNotBlank() }
        val legacyEnd = legacy?.get("horaSalida")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        val raw = when {
            overrideEnd != null -> overrideEnd
            legacyEnd != null -> legacyEnd
            startLabel == shift.horaEntradaTarde?.take(5) -> shift.horaSalida
            startLabel == shift.horaEntrada.take(5) && !shift.horaSalidaManana.isNullOrBlank() -> shift.horaSalidaManana
            else -> shift.horaSalida
        }
        return parseTime(raw)
    }

    private fun add(map: MutableMap<String, PlannedReminder>, reminder: PlannedReminder) {
        map.putIfAbsent("${reminder.date}|${reminder.time}|${reminder.kind}", reminder)
    }

    private fun isActiveOn(shift: DepartmentShiftInfo, day: DayOfWeek): Boolean {
        val wanted = dayNames[day] ?: return false
        return shift.activeDays
            .split(',')
            .asSequence()
            .map(::normalizeDay)
            .filter { it.isNotEmpty() }
            .any { it == wanted }
    }

    private fun normalizeDay(value: String): String =
        Normalizer.normalize(value.trim().uppercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    private fun parseTime(value: String?): LocalTime? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalTime.parse(value.take(5)) }.getOrNull()
    }

    private fun dateKey(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
    }

    private fun covers(vacation: VacationRequest, date: LocalDate): Boolean {
        val start = dateKey(vacation.startDate) ?: return false
        val end = dateKey(vacation.endDate) ?: return false
        return !date.isBefore(start) && !date.isAfter(end)
    }
}
