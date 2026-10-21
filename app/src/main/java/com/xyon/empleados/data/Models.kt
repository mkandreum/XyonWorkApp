package com.xyon.empleados.data

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String = "",
    val name: String = "",
    val email: String = "",
    val role: String = "EMPLOYEE",
    val department: String = "",
    val position: String = "",
    val avatarUrl: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val emergencyContact: String? = null,
    val joinDate: String? = null,
    val active: Boolean = true,
    val deletedAt: String? = null,
    val mfaEnabled: Boolean = false,
    /** Self sign-up waiting for HR to assign department, job title and role. */
    val pendingAssignment: Boolean = false,
    /** ADMIN without two-step verification: the server blocks admin routes until it is enabled. */
    val mfaSetupRequired: Boolean = false,
)

@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class RegisterRequest(
    val name: String, val email: String, val password: String,
    val department: String? = null, val position: String? = null, val invitationCode: String,
)
/** Session (token + user) or, for accounts with two-step verification, the challenge for the code. */
@Serializable data class AuthResponse(
    val token: String? = null, val user: User? = null,
    val mfaRequired: Boolean = false, val mfaToken: String? = null,
    val recoveryCodes: List<String>? = null,
)
@Serializable data class MfaVerifyRequest(val mfaToken: String, val code: String)
@Serializable data class MfaCodeRequest(val code: String)
@Serializable data class AssignUserRequest(val department: String, val position: String, val role: String)
@Serializable data class MfaDisableRequest(val password: String, val code: String)
@Serializable data class MfaStatus(val enabled: Boolean = false, val required: Boolean = false, val recoveryCodesLeft: Int = 0)
@Serializable data class MfaSetupResponse(val secret: String, val otpauthUri: String)
@Serializable data class EmailRequest(val email: String)
@Serializable data class ResetPasswordRequest(val token: String, val email: String, val newPassword: String)
@Serializable data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)
@Serializable data class MessageResponse(val message: String? = null, val error: String? = null, val code: String? = null)
@Serializable data class UpdateProfileRequest(
    val phone: String? = null, val address: String? = null,
    val emergencyContact: String? = null, val avatarUrl: String? = null,
)

@Serializable
data class Payroll(
    val id: String = "", val month: String = "", val year: Int = 0, val amount: Double = 0.0,
    val pdfUrl: String? = null, val signedPdfUrl: String? = null, val status: String = "PENDING",
    val user: User? = null,
)
@Serializable data class DownloadInfo(val pdfUrl: String? = null, val filename: String? = null)
/** POST payrolls/unlock: the account password unlocks payroll files for a few minutes. */
@Serializable data class PayrollUnlockRequest(val password: String)
@Serializable data class PayrollUnlockResponse(val unlockToken: String, val expiresIn: Int = 300)

@Serializable
data class VacationRequest(
    val id: String = "", val startDate: String = "", val endDate: String = "",
    val days: Double = 0.0, val hours: Double? = null, val status: String = "PENDING",
    val type: String = "VACATION", val subtype: String? = null,
    val justificationUrl: String? = null, val user: User? = null, val createdAt: String? = null,
)
@Serializable
data class CreateVacationRequest(
    val startDate: String, val endDate: String, val days: Int, val hours: Int? = null,
    val type: String, val subtype: String? = null, val justificationUrl: String? = null,
)
@Serializable data class StatusRequest(val status: String)

@Serializable
data class NewsItem(
    val id: String = "", val title: String = "", val summary: String = "", val content: String = "",
    val date: String = "", val category: String = "CORPORATE", val imageUrl: String? = null,
)

@Serializable
data class AppNotification(
    val id: String = "", val title: String = "", val message: String = "",
    val read: Boolean = false, val date: String? = null, val createdAt: String? = null,
)
@Serializable
data class NotificationsResponse(val notifications: List<AppNotification> = emptyList(), val unreadCount: Int = 0)

@Serializable
data class EventItem(
    val id: String = "", val title: String = "", val description: String? = null,
    val date: String = "", val location: String? = null,
)
/** Monthly closing of my time record (the worker confirms the month is right). */
@Serializable
data class MonthlyClosing(
    val year: Int = 0, val month: Int = 0, val status: String = "NO_RECORDS", val canConfirm: Boolean = false,
    val records: Int = 0, val daysWorked: Int = 0, val totalHours: Double = 0.0, val acknowledgedAt: String? = null,
)
@Serializable data class ClosingRequest(val year: Int, val month: Int)

@Serializable data class Holiday(val id: String = "", val name: String = "", val date: String = "", val year: Int = 0)

@Serializable
data class Fichaje(
    val id: String = "", val userId: String = "", val tipo: String = "ENTRADA",
    val timestamp: String = "", val department: String? = null, val user: User? = null,
    /** OFFICE | REMOTE (teletrabajo); null on records from before the switch existed. */
    val workMode: String? = null,
)
@Serializable
data class TurnoInfo(
    val turno: String? = null, val expectedEntry: String? = null,
    val expectedExit: String? = null, val label: String? = null,
)
@Serializable
data class FichajeStatus(
    val hasActiveEntry: Boolean = false, val currentFichaje: Fichaje? = null, val turno: TurnoInfo? = null,
    /** Entry from a previous day with no exit afterwards (forgot to clock out). */
    val missingExit: Fichaje? = null,
)
@Serializable
data class CreateFichajeRequest(
    val tipo: String, val latitude: Double? = null, val longitude: Double? = null, val accuracy: Float? = null,
    val workMode: String = "OFFICE",
    /** Made without connection: the server accepts [clientTimestamp] within a limit (APP_OFFLINE). */
    val offline: Boolean = false, val clientTimestamp: String? = null,
)
@Serializable
data class CreateFichajeResponse(
    val fichaje: Fichaje? = null, val status: FichajeStatus? = null, val warning: String? = null,
)
@Serializable
data class FichajeDayStats(
    val date: String = "", val fichajes: List<Fichaje> = emptyList(), val horasTrabajadas: Double = 0.0,
    val isComplete: Boolean = false, val isLate: Boolean = false, val isEarlyDeparture: Boolean = false,
    /** Ids of the punches that cause the incident (a late notice must point at the late entry). */
    val lateFichajeIds: List<String> = emptyList(), val earlyFichajeIds: List<String> = emptyList(),
    val validationMessage: String? = null,
    val turno: TurnoInfo? = null,
    /** Schedule the server resolved for that day (shift, per-day override or one-off exception). */
    val daySchedule: DaySchedule? = null,
)
/** Start/end of a (possibly split) working day, as "HH:mm". */
@Serializable
data class DaySchedule(
    val horaEntrada: String? = null, val horaSalida: String? = null,
    val horaEntradaTarde: String? = null,
    @kotlinx.serialization.SerialName("horaSalidaMañana") val horaSalidaManana: String? = null,
)

@Serializable
data class FichajeAdjustment(
    val id: String = "", val fichajeId: String? = null, val userId: String = "", val user: User? = null,
    val originalTimestamp: String = "", val requestedTimestamp: String = "", val requestedExitTimestamp: String? = null,
    val kind: String? = null, val reason: String = "",
    val status: String = "PENDING", val rejectionReason: String? = null, val createdAt: String? = null,
)
/** "09:00 → 17:00" style summary: the day's own entry/exit for ADD_DAY, original → requested otherwise. */
fun FichajeAdjustment.timesLabel(fmt: (String) -> String): String =
    if (kind == "ADD_DAY" && requestedExitTimestamp != null) "${fmt(requestedTimestamp)} → ${fmt(requestedExitTimestamp)}"
    else "${fmt(originalTimestamp)} → ${fmt(requestedTimestamp)}"

@Serializable
data class CreateAdjustmentRequest(
    val fichajeId: String? = null, val requestedTimestamp: String, val reason: String, val kind: String? = null,
    val requestedExitTimestamp: String? = null,
)
@Serializable data class RejectRequest(val rejectionReason: String)

@Serializable
data class UserBenefitsBalance(
    val vacationDaysUsed: Double = 0.0, val overtimeHoursUsed: Double = 0.0,
    val sickLeaveHoursUsed: Double = 0.0, val paidAbsenceHoursUsed: Double = 0.0, val year: Int = 0,
)
@Serializable
data class DepartmentBenefits(
    val department: String = "", val vacationDays: Double = 0.0, val overtimeHoursBank: Double = 0.0,
    val sickLeaveHours: Double = 0.0, val paidAbsenceHours: Double = 0.0,
)

@Serializable
data class AdminStats(
    // Same keys as GET /admin/stats (statsController.getAdminStats)
    val totalEmployees: Int = 0, val pendingRequests: Int = 0, val pendingAssignments: Int = 0, val approvedThisMonth: Int = 0,
    val payrollsProcessed: Int = 0,
)
@Serializable
data class InvitationCode(
    val id: String = "", val code: String = "", val isUsed: Boolean = false, val createdAt: String? = null,
)
@Serializable
data class CreateNewsRequest(
    val title: String, val summary: String, val content: String, val category: String, val imageUrl: String? = null,
)
@Serializable
data class UploadResult(val url: String? = null, val fileUrl: String? = null, val path: String? = null)

@Serializable
data class AttendanceWarning(
    val id: String = "", val fecha: String = "", val justificado: Boolean = false,
    val justificacionTexto: String? = null, val leido: Boolean = false, val manager: User? = null,
    val createdAt: String? = null,
)

@Serializable
data class JustifyLateRequest(val justificacion: String)

@Serializable
data class OverlapPerson(val name: String = "", val approved: Boolean = false)

@Serializable
data class OverlapResponse(val count: Int = 0, val people: List<OverlapPerson> = emptyList())

@Serializable
data class OkResponse(val ok: Boolean = true)

// ── Attendance report (GET fichajes/report?format=json), same data as the web PDF/CSV ──
@Serializable data class ReportPeriod(val from: String = "", val to: String = "", val label: String = "")
@Serializable data class ReportUser(val id: String = "", val name: String = "", val email: String = "", val department: String = "", val position: String? = null)
@Serializable data class ReportDay(
    val date: String = "", val dayName: String = "", val firstEntry: String? = null, val lastExit: String? = null,
    val hours: Double? = null, val status: String = "", val kind: String = "none", val observations: String = "", val workMode: String? = null,
)
@Serializable data class ReportSummary(
    val daysWorked: Int = 0, val daysVacation: Int = 0, val daysAbsent: Int = 0, val daysLate: Int = 0,
    val daysIncident: Int = 0, val daysRemote: Int = 0, val totalHours: Double = 0.0,
)
@Serializable data class UserReport(val user: ReportUser = ReportUser(), val days: List<ReportDay> = emptyList(), val summary: ReportSummary = ReportSummary())
@Serializable data class AttendanceReportData(
    val period: ReportPeriod = ReportPeriod(), val generatedAt: String = "", val fileBase: String = "informe", val reports: List<UserReport> = emptyList(),
)


/** GET /api/plan: modules and limits of the contracted plan (basico | profesional | empresa). */
@Serializable data class PlanInfo(
    val code: String = "profesional",
    val label: String = "",
    val maxUsers: Int = 0,
    val features: List<String> = emptyList(),
    val usedUsers: Int = 0,
)
