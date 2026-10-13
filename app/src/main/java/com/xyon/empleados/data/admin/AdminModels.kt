package com.xyon.empleados.data.admin

import com.xyon.empleados.data.User
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// NOTE: request classes intentionally have NO default values for fields that may legitimately be
// false/0/"" so they are always serialized (AppJson uses encodeDefaults = false).

@Serializable
data class CreateUserRequest(
    val name: String, val email: String, val password: String, val role: String,
    val department: String, val position: String, val joinDate: String? = null,
)

@Serializable
data class UpdateUserRequest(
    val name: String, val email: String, val role: String,
    val department: String, val position: String, val joinDate: String? = null,
)

@Serializable data class ForgotRequest(val email: String)

@Serializable
data class DepartmentShift(
    val id: String = "", val department: String = "", val name: String = "",
    val activeDays: String = "", val horaEntrada: String = "", val horaSalida: String = "",
    val horaEntradaTarde: String? = null,
    @SerialName("horaSalidaMañana") val horaSalidaManana: String? = null,
    val toleranciaMinutos: Int = 10, val flexibleSchedule: Boolean = false,
)

@Serializable
data class ShiftRequest(
    val department: String, val name: String, val activeDays: String,
    val horaEntrada: String, val horaSalida: String, val horaEntradaTarde: String,
    @SerialName("horaSalidaMañana") val horaSalidaManana: String,
    val toleranciaMinutos: Int, val flexibleSchedule: Boolean,
)

@Serializable
data class ShiftUpdateRequest(
    val name: String, val activeDays: String, val horaEntrada: String, val horaSalida: String,
    val horaEntradaTarde: String, @SerialName("horaSalidaMañana") val horaSalidaManana: String,
    val toleranciaMinutos: Int, val flexibleSchedule: Boolean,
)

@Serializable
data class ShiftAssignment(
    val id: String = "", val userId: String = "", val shiftId: String = "", val date: String = "",
    val shift: DepartmentShift? = null,
)
@Serializable data class AssignShiftRequest(val userId: String, val shiftId: String, val date: String)

@Serializable
data class BenefitsRequest(
    val department: String, val vacationDays: Int, val overtimeHoursBank: Int,
    val sickLeaveHours: Int, val paidAbsenceHours: Int,
)
@Serializable
data class BenefitsRow(
    val department: String = "", val vacationDays: Double = 22.0, val overtimeHoursBank: Double = 40.0,
    val sickLeaveHours: Double = 15.0, val paidAbsenceHours: Double = 20.0,
)

@Serializable data class CreateEventRequest(val title: String, val description: String, val date: String, val location: String)

@Serializable
data class UpdateNewsRequest(
    val title: String, val summary: String, val content: String, val category: String, val imageUrl: String? = null,
)

@Serializable
data class CreatePayrollRequest(val userId: String, val month: String, val year: Int, val amount: Double, val pdfUrl: String)

@Serializable
data class OfficeLocation(
    val department: String = "", val latitude: Double = 0.0, val longitude: Double = 0.0,
    val maxDistance: Double = 500.0, val required: Boolean = false, val name: String? = null,
)
@Serializable
data class OfficeLocationRequest(
    val latitude: Double, val longitude: Double, val maxDistance: Int, val required: Boolean, val name: String,
)

@Serializable
data class EmailTemplate(
    val id: String = "", val type: String = "", val name: String = "", val subject: String = "",
    val htmlBody: String = "", val isActive: Boolean = true, val variables: String? = null,
)
@Serializable
data class EmailTemplateUpdate(val name: String, val subject: String, val htmlBody: String, val isActive: Boolean)

@Serializable
data class LateNotification(
    val id: String = "", val userId: String = "", val user: User? = null, val fichajeId: String = "",
    val fecha: String = "", val justificado: Boolean = false, val justificacionTexto: String? = null,
    val leido: Boolean = false, val createdAt: String? = null,
)
@Serializable data class LateNotificationRequest(val userId: String, val fichajeId: String, val fecha: String)

@Serializable
data class DashWidget(
    val id: String = "", val layoutId: String = "", val type: String = "", val label: String = "",
    val isActive: Boolean = true, val order: Int = 0, val scope: String = "ALL", val department: String? = null,
)
@Serializable
data class DashLayout(
    val id: String = "", val name: String = "", val description: String? = null, val isActive: Boolean = false,
    val startDate: String? = null, val endDate: String? = null, val widgets: List<DashWidget> = emptyList(),
)
@Serializable
data class CreateLayoutRequest(val name: String, val description: String? = null, val isActive: Boolean)
@Serializable
data class UpdateLayoutRequest(val name: String? = null, val description: String? = null, val isActive: Boolean? = null)
@Serializable
data class AddWidgetRequest(val type: String, val label: String, val isActive: Boolean, val scope: String)
@Serializable
data class UpdateWidgetRequest(val label: String? = null, val isActive: Boolean? = null, val scope: String? = null)
@Serializable data class WidgetOrder(val id: String, val order: Int)
@Serializable data class ReorderRequest(val widgetOrders: List<WidgetOrder>)

@Serializable data class OkResponse(val message: String? = null, val success: Boolean? = null)
