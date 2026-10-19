package com.xyon.empleados.data

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.http.*

interface Api {
    // Auth (public)
    @POST("auth/login") suspend fun login(@Body b: LoginRequest): AuthResponse
    @POST("auth/register") suspend fun register(@Body b: RegisterRequest): AuthResponse
    @POST("auth/forgot-password") suspend fun forgot(@Body b: EmailRequest): MessageResponse
    @POST("auth/reset-password") suspend fun reset(@Body b: ResetPasswordRequest): MessageResponse

    // Profile
    @GET("users/profile") suspend fun profile(): User
    @PUT("users/profile") suspend fun updateProfile(@Body b: UpdateProfileRequest): User
    /** Returns a new session: changing the password closes the other ones. */
    @POST("users/change-password") suspend fun changePassword(@Body b: ChangePasswordRequest): AuthResponse
    // Two-step verification and sessions
    @POST("auth/mfa/verify") suspend fun mfaVerify(@Body b: MfaVerifyRequest): AuthResponse
    @GET("auth/mfa/status") suspend fun mfaStatus(): MfaStatus
    @POST("auth/mfa/setup") suspend fun mfaSetup(): MfaSetupResponse
    @POST("auth/mfa/enable") suspend fun mfaEnable(@Body b: MfaCodeRequest): AuthResponse
    @POST("auth/mfa/disable") suspend fun mfaDisable(@Body b: MfaDisableRequest): AuthResponse
    @POST("auth/logout-all") suspend fun logoutAll(): MessageResponse
    @Multipart @POST("upload/avatar") suspend fun uploadAvatar(@Part file: MultipartBody.Part): UploadResult

    @Multipart @POST("upload/justification") suspend fun uploadJustification(@Part file: MultipartBody.Part): UploadResult

    // Content
    @GET("news") suspend fun news(): List<NewsItem>
    @GET("events") suspend fun events(): List<EventItem>
    @GET("holidays/next") suspend fun nextHoliday(): Holiday?
    @GET("notifications") suspend fun notifications(): NotificationsResponse
    @PUT("notifications/{id}/read") suspend fun readNotification(@Path("id") id: String): ResponseBody
    @PUT("notifications/read-all") suspend fun readAllNotifications(): ResponseBody
    @DELETE("notifications/{id}") suspend fun deleteNotification(@Path("id") id: String): ResponseBody

    // Payroll
    @GET("payrolls") suspend fun payrolls(): List<Payroll>
    @GET("payrolls/{id}/download") suspend fun payrollDownload(@Path("id") id: String): DownloadInfo
    @Streaming @GET suspend fun download(@Url url: String): ResponseBody
    @POST("payrolls/unlock") suspend fun payrollUnlock(@Body b: PayrollUnlockRequest): PayrollUnlockResponse
    /** Payroll files need the unlock token from [payrollUnlock] (server answers 403 PAYROLL_LOCKED otherwise). */
    @Streaming @GET suspend fun downloadPayrollFile(@Url url: String, @Header("X-Payroll-Unlock") unlock: String): ResponseBody

    // Vacations / absences
    @GET("vacations") suspend fun vacations(): List<VacationRequest>
    @POST("vacations") suspend fun createVacation(@Body b: CreateVacationRequest): VacationRequest
    @DELETE("vacations/{id}") suspend fun cancelVacation(@Path("id") id: String): OkResponse
    @GET("vacations/overlap") suspend fun vacationOverlap(@Query("startDate") start: String, @Query("endDate") end: String): OverlapResponse
    @GET("benefits/user") suspend fun myBalance(): UserBenefitsBalance
    @GET("benefits/department/{d}") suspend fun departmentBenefits(@Path("d") d: String): DepartmentBenefits

    // Fichajes
    @POST("fichajes") suspend fun fichar(@Body b: CreateFichajeRequest): CreateFichajeResponse
    @GET("fichajes/current") suspend fun fichajeCurrent(): FichajeStatus

    // Schedule data for the local clock-in reminders. The backend only lets an
    // employee read their own department/assignments.
    @GET("department-schedules/{department}") suspend fun departmentShifts(@Path("department") department: String): List<DepartmentShiftInfo>
    @GET("user-shift-assignments") suspend fun shiftAssignments(
        @Query("userId") userId: String, @Query("month") month: Int, @Query("year") year: Int,
    ): List<ShiftAssignmentInfo>
    @GET("schedule-overrides") suspend fun scheduleOverrides(
        @Query("userId") userId: String?, @Query("from") from: String?, @Query("to") to: String?,
    ): List<ScheduleOverrideInfo>
    @GET("fichajes/history") suspend fun fichajeHistory(
        @Query("userId") userId: String? = null, @Query("startDate") start: String? = null,
        @Query("endDate") end: String? = null, @Query("department") dept: String? = null,
    ): List<Fichaje>
    @GET("fichajes/week/{id}") suspend fun fichajeWeek(@Path("id") id: String): List<FichajeDayStats>
    @GET("fichajes/month/{id}") suspend fun fichajeMonth(
        @Path("id") id: String, @Query("year") year: Int? = null, @Query("month") month: Int? = null,
    ): List<FichajeDayStats>

    // Attendance warnings (late arrival / early departure) sent by a manager
    @GET("late-notifications") suspend fun lateNotifications(): List<AttendanceWarning>
    @POST("late-notifications/{id}/justify") suspend fun justifyLate(@Path("id") id: String, @Body b: JustifyLateRequest): AttendanceWarning
    @POST("fichaje-adjustments") suspend fun createAdjustment(@Body b: CreateAdjustmentRequest): FichajeAdjustment
    @GET("fichaje-adjustments") suspend fun myAdjustments(): List<FichajeAdjustment>

    // Manager
    @GET("manager/team-vacations") suspend fun teamVacations(): List<VacationRequest>
    @GET("manager/team-members") suspend fun teamMembers(): List<User>
    @PATCH("manager/vacations/{id}/approve") suspend fun managerApprove(@Path("id") id: String): VacationRequest
    @PATCH("manager/vacations/{id}/reject") suspend fun managerReject(@Path("id") id: String): VacationRequest
    @GET("fichaje-adjustments/pending") suspend fun pendingAdjustments(): List<FichajeAdjustment>
    @PATCH("fichaje-adjustments/{id}/approve") suspend fun approveAdjustment(@Path("id") id: String): FichajeAdjustment
    @PATCH("fichaje-adjustments/{id}/reject") suspend fun rejectAdjustment(@Path("id") id: String, @Body b: RejectRequest): FichajeAdjustment

    // Admin
    @GET("admin/stats") suspend fun adminStats(): AdminStats
    @GET("admin/users") suspend fun adminUsers(): List<User>
    @DELETE("admin/users/{id}") suspend fun adminDeleteUser(@Path("id") id: String): ResponseBody
    @GET("admin/vacations") suspend fun adminVacations(): List<VacationRequest>
    @PUT("admin/vacations/{id}/status") suspend fun adminVacationStatus(@Path("id") id: String, @Body b: StatusRequest): VacationRequest
    @GET("admin/invites") suspend fun invites(): List<InvitationCode>
    @POST("admin/invites") suspend fun createInvite(): InvitationCode
    @DELETE("admin/invites/{id}") suspend fun deleteInvite(@Path("id") id: String): ResponseBody
    @POST("admin/news") suspend fun createNews(@Body b: CreateNewsRequest): NewsItem
    @DELETE("admin/news/{id}") suspend fun deleteNews(@Path("id") id: String): ResponseBody
    @GET("admin/payrolls") suspend fun adminPayrolls(): List<Payroll>
    @DELETE("admin/payrolls/{id}") suspend fun adminDeletePayroll(@Path("id") id: String): ResponseBody
}
