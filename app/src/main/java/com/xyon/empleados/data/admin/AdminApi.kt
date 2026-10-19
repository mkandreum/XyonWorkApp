package com.xyon.empleados.data.admin

import com.xyon.empleados.data.*
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.http.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

interface AdminApi {
    // Users
    @GET("admin/users") suspend fun users(): List<User>
    @POST("admin/users/{id}/revoke-sessions") suspend fun revokeSessions(@Path("id") id: String): MessageResponse
    @POST("admin/users/{id}/mfa/reset") suspend fun resetMfa(@Path("id") id: String): MessageResponse
    @POST("admin/users") suspend fun createUser(@Body b: CreateUserRequest): User
    @PUT("admin/users/{id}") suspend fun updateUser(@Path("id") id: String, @Body b: UpdateUserRequest): User
    @DELETE("admin/users/{id}") suspend fun deleteUser(@Path("id") id: String): ResponseBody
    @PATCH("admin/users/{id}/active") suspend fun setActive(@Path("id") id: String, @Body b: ActiveRequest): User
    @POST("admin/users/import") suspend fun importUsers(@Body b: ImportUsersRequest): ImportResponse
    @POST("auth/forgot-password") suspend fun forgot(@Body b: ForgotRequest): MessageResponse

    // Settings / departments
    @GET("admin/settings") suspend fun settings(): Map<String, String>
    @PUT("admin/settings") suspend fun updateSettings(@Body b: Map<String, String>): ResponseBody
    @GET("admin/office-locations") suspend fun officeLocations(): List<OfficeLocation>
    @POST("admin/office-locations/{d}") suspend fun setOffice(@Path("d") d: String, @Body b: OfficeLocationRequest): ResponseBody
    @DELETE("admin/office-locations/{d}") suspend fun deleteOffice(@Path("d") d: String): ResponseBody
    @GET("admin/email-templates") suspend fun emailTemplates(): List<EmailTemplate>
    @PUT("admin/email-templates/{id}") suspend fun updateTemplate(@Path("id") id: String, @Body b: EmailTemplateUpdate): EmailTemplate

    // Requests
    @GET("admin/vacations") suspend fun vacations(): List<VacationRequest>
    @PUT("admin/vacations/{id}/status") suspend fun vacationStatus(@Path("id") id: String, @Body b: StatusRequest): VacationRequest
    @GET("manager/team-vacations") suspend fun teamVacations(): List<VacationRequest>
    @PATCH("manager/vacations/{id}/approve") suspend fun managerApprove(@Path("id") id: String): VacationRequest
    @PATCH("manager/vacations/{id}/reject") suspend fun managerReject(@Path("id") id: String): VacationRequest

    // Fichaje adjustments
    @GET("fichaje-adjustments/pending") suspend fun pendingAdjustments(): List<FichajeAdjustment>
    @GET("fichaje-adjustments/all") suspend fun allAdjustments(): List<FichajeAdjustment>
    @PATCH("fichaje-adjustments/{id}/approve") suspend fun approveAdjustment(@Path("id") id: String): ResponseBody
    @PATCH("fichaje-adjustments/{id}/reject") suspend fun rejectAdjustment(@Path("id") id: String, @Body b: RejectRequest): ResponseBody

    // Fichajes
    @GET("manager/team-members") suspend fun teamMembers(): List<User>
    @GET("fichajes/history") suspend fun history(@Query("userId") userId: String, @Query("startDate") start: String, @Query("endDate") end: String): List<Fichaje>
    @GET("fichajes/week/{id}") suspend fun week(@Path("id") id: String): List<FichajeDayStats>
    @GET("fichajes/month/{id}") suspend fun month(@Path("id") id: String): List<FichajeDayStats>
    @GET("fichajes/report") suspend fun report(@Query("month") m: Int, @Query("year") y: Int, @Query("userId") userId: String?, @Query("department") department: String?): ResponseBody
    @POST("fichajes/manual") suspend fun manualCreate(@Body b: ManualFichajeRequest): ManualFichajeResponse
    @PUT("fichajes/manual/{id}") suspend fun manualUpdate(@Path("id") id: String, @Body b: ManualFichajeEdit): ManualFichajeResponse
    @DELETE("fichajes/manual/{id}") suspend fun manualDelete(@Path("id") id: String, @Query("reason") reason: String): ResponseBody
    @GET("schedule-overrides") suspend fun overrides(@Query("department") department: String?, @Query("userId") userId: String?, @Query("from") from: String?, @Query("to") to: String?): List<ScheduleOverride>
    @PUT("schedule-overrides") suspend fun setOverride(@Body b: ScheduleOverrideRequest): ScheduleOverride
    @DELETE("schedule-overrides/{id}") suspend fun deleteOverride(@Path("id") id: String): ResponseBody
    @GET("team/week") suspend fun teamWeek(@Query("department") department: String?, @Query("weekStart") weekStart: String?): TeamWeek
    @POST("late-notifications") suspend fun lateNotify(@Body b: LateNotificationRequest): ResponseBody
    @GET("late-notifications/sent") suspend fun lateSent(): List<LateNotification>

    // Shifts
    @GET("department-schedules") suspend fun allShifts(): List<DepartmentShift>
    @GET("department-shifts/{d}") suspend fun shiftsOf(@Path("d") d: String): List<DepartmentShift>
    @POST("department-shifts") suspend fun createShift(@Body b: ShiftRequest): DepartmentShift
    @PUT("department-shifts/{id}") suspend fun updateShift(@Path("id") id: String, @Body b: ShiftUpdateRequest): DepartmentShift
    @DELETE("department-shifts/{id}") suspend fun deleteShift(@Path("id") id: String): ResponseBody
    @GET("user-shift-assignments") suspend fun assignments(@Query("userId") u: String, @Query("month") m: Int, @Query("year") y: Int): List<ShiftAssignment>
    @POST("user-shift-assignments") suspend fun assign(@Body b: AssignShiftRequest): ResponseBody

    // Content
    @GET("events") suspend fun events(): List<EventItem>
    @POST("admin/events") suspend fun createEvent(@Body b: CreateEventRequest): EventItem
    @DELETE("admin/events/{id}") suspend fun deleteEvent(@Path("id") id: String): ResponseBody
    @GET("news") suspend fun news(): List<NewsItem>
    @POST("admin/news") suspend fun createNews(@Body b: CreateNewsRequest): NewsItem
    @PUT("admin/news/{id}") suspend fun updateNews(@Path("id") id: String, @Body b: UpdateNewsRequest): NewsItem
    @DELETE("admin/news/{id}") suspend fun deleteNews(@Path("id") id: String): ResponseBody
    @Multipart @POST("upload/news") suspend fun uploadNews(@Part f: MultipartBody.Part): UploadResult
    @Multipart @POST("upload/logo") suspend fun uploadLogo(@Part f: MultipartBody.Part): UploadResult
    @Multipart @POST("upload/avatar") suspend fun uploadAvatar(@Part f: MultipartBody.Part): UploadResult
    @Multipart @POST("upload/payroll") suspend fun uploadPayroll(@Part f: MultipartBody.Part): UploadResult

    // Benefits / payroll / invites
    @GET("admin/benefits") suspend fun benefits(): List<BenefitsRow>
    @POST("admin/benefits") suspend fun saveBenefits(@Body b: BenefitsRequest): ResponseBody
    @GET("admin/payrolls") suspend fun payrolls(): List<Payroll>
    @POST("admin/payrolls") suspend fun createPayroll(@Body b: CreatePayrollRequest): ResponseBody
    @DELETE("admin/payrolls/{id}") suspend fun deletePayroll(@Path("id") id: String): ResponseBody
    @GET("admin/invites") suspend fun invites(): List<InvitationCode>
    @POST("admin/invites") suspend fun createInvite(): InvitationCode
    @DELETE("admin/invites/{id}") suspend fun deleteInvite(@Path("id") id: String): ResponseBody
    @GET("admin/stats") suspend fun stats(): AdminStats

    // Dashboard layouts
    @GET("dashboard-layouts") suspend fun layouts(): List<DashLayout>
    @POST("dashboard-layouts") suspend fun createLayout(@Body b: CreateLayoutRequest): DashLayout
    @PUT("dashboard-layouts/{id}") suspend fun updateLayout(@Path("id") id: String, @Body b: UpdateLayoutRequest): DashLayout
    @DELETE("dashboard-layouts/{id}") suspend fun deleteLayout(@Path("id") id: String): ResponseBody
    @POST("dashboard-layouts/{id}/widgets") suspend fun addWidget(@Path("id") id: String, @Body b: AddWidgetRequest): DashWidget
    @PUT("dashboard-layouts/widgets/{wid}") suspend fun updateWidget(@Path("wid") id: String, @Body b: UpdateWidgetRequest): DashWidget
    @DELETE("dashboard-layouts/widgets/{wid}") suspend fun deleteWidget(@Path("wid") id: String): ResponseBody
    @PUT("dashboard-layouts/{id}/reorder") suspend fun reorder(@Path("id") id: String, @Body b: ReorderRequest): ResponseBody
}

@Module
@InstallIn(SingletonComponent::class)
object AdminModule {
    @Provides @Singleton fun adminApi(r: Retrofit): AdminApi = r.create(AdminApi::class.java)
}

/** Departments = settings.DEPARTMENTS (JSON array) merged with those used by users. */
@Singleton
class DepartmentsRepo @Inject constructor(private val api: AdminApi) {
    suspend fun names(): List<String> {
        val fromSettings = runCatching {
            AppJson.decodeFromString<List<String>>(api.settings()["DEPARTMENTS"] ?: "[]")
        }.getOrDefault(emptyList())
        val fromUsers = runCatching { api.users().map { it.department } }.getOrDefault(emptyList())
        return (fromSettings + fromUsers).filter { it.isNotBlank() }.distinct().sorted()
    }
}
