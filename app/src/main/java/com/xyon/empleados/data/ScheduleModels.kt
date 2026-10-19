package com.xyon.empleados.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Read-only schedule models used by the clock-in reminders.
 *
 * The web backend exposes the department shifts, the per-day assignments and the
 * schedule exceptions separately; the app combines them to know the real start
 * time of each employee. Field names mirror the API on purpose.
 */
@Serializable
data class DepartmentShiftInfo(
    val id: String = "",
    val department: String = "",
    val name: String = "",
    val activeDays: String = "",
    val horaEntrada: String = "",
    val horaSalida: String = "",
    val horaEntradaTarde: String? = null,
    @SerialName("horaSalidaMañana") val horaSalidaManana: String? = null,
    val toleranciaMinutos: Int = 10,
    val flexibleSchedule: Boolean = false,
    /** Legacy per-day overrides stored inside the shift: {"2026-10-20": {...}} */
    val scheduleOverrides: JsonObject? = null,
)

@Serializable
data class ShiftAssignmentInfo(
    val id: String = "",
    val userId: String = "",
    val shiftId: String = "",
    val date: String = "",
    val shift: DepartmentShiftInfo? = null,
)

@Serializable
data class ScheduleOverrideInfo(
    val id: String = "",
    val department: String = "",
    val userId: String? = null,
    val scope: String = "DEPARTMENT",
    val date: String = "",
    val dayOff: Boolean = false,
    val horaEntrada: String? = null,
    val horaSalida: String? = null,
    val toleranciaMinutos: Int? = null,
    val note: String? = null,
)
