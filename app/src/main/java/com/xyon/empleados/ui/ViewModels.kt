package com.xyon.empleados.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xyon.empleados.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject

sealed interface AuthState {
    data object Loading : AuthState
    data object LoggedOut : AuthState
    data class Locked(val user: User?) : AuthState   // token present, biometric required
    data class LoggedIn(val user: User) : AuthState
}

val User.isManager get() = role == "MANAGER" || role == "ADMIN"
val User.isAdmin get() = role == "ADMIN"

/** App-wide session: login/register/recovery, biometric unlock, profile. */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val api: Api, private val store: SessionStore, private val cache: CacheStore,
    private val json: kotlinx.serialization.json.Json, private val reminders: ReminderScheduler,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appCtx: android.content.Context,
    private val offline: OfflineFichajes,
) : ViewModel() {
    private val _auth = MutableStateFlow<AuthState>(AuthState.Loading)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()
    val biometricEnabled = store.biometricEnabled
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _message = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val message: SharedFlow<String> = _message.asSharedFlow()
    private var pendingUser: User? = null

    /** True while a logged-in session is covered by the biometric lock (app came back from background). */
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private val _rememberedEmail = MutableStateFlow<String?>(null)
    val rememberedEmail: StateFlow<String?> = _rememberedEmail.asStateFlow()

    init {
        viewModelScope.launch { _rememberedEmail.value = store.rememberedEmail() }
        viewModelScope.launch {
            val has = store.load()
            if (!has) { _auth.value = AuthState.LoggedOut; return@launch }
            val cached = cache.get("me")?.let { runCatching { json.decodeFromString<User>(it) }.getOrNull() }
            pendingUser = cached
            if (store.biometricEnabled.first()) _auth.value = AuthState.Locked(cached)
            else enter(cached)
        }
        viewModelScope.launch { store.sessionExpired.collect { signOut("Tu sesión ha caducado") } }
        viewModelScope.launch { store.accountDisabled.collect { signOut(ACCOUNT_DISABLED_MSG) } }
    }

    /** Called after biometric success (or when biometrics disabled). */
    fun unlock() {
        if (_locked.value) { _locked.value = false; return }   // resume lock: the session underneath is intact
        enter(pendingUser)
    }

    /** App went to background: cover the session so returning requires biometrics again. */
    fun onAppBackground() = viewModelScope.launch {
        if (_auth.value is AuthState.LoggedIn && store.biometricEnabled.first()) _locked.value = true
    }

    private fun enter(cached: User?) {
        viewModelScope.launch {
            if (cached != null) _auth.value = AuthState.LoggedIn(cached)
            // Notices without Firebase + pending offline clock-ins (works also when offline at start)
            Background.startNotifications(appCtx)
            if (offline.all().isNotEmpty()) Background.syncFichajes(appCtx)
            safeCall { api.profile() }.onSuccess {
                cache.put("me", json.encodeToString(User.serializer(), it)); _auth.value = AuthState.LoggedIn(it)
                reminders.refresh()
            }.onFailure {
                if (cached == null) {
                    if (it.isOffline) _message.tryEmit(it.userMessage()) else signOut(null)
                } else if (it.isOffline) {
                    // Offline start: keep the previous plan if the API is unreachable.
                    reminders.refresh()
                }
            }
        }
    }

    /** Pending second step of the login (authenticator code); null = no challenge. */
    private val _mfaChallenge = MutableStateFlow<String?>(null)
    val mfaChallenge: StateFlow<String?> = _mfaChallenge.asStateFlow()

    private fun accept(r: AuthResponse) = viewModelScope.launch {
        val token = r.token ?: return@launch
        val user = r.user ?: return@launch
        store.saveToken(token)
        cache.put("me", json.encodeToString(User.serializer(), user))
        _mfaChallenge.value = null
        _auth.value = AuthState.LoggedIn(user)
        reminders.refresh()
        Background.startNotifications(appCtx)
    }

    /** Stores a renewed session (password change, MFA on/off) without leaving the current screen. */
    fun renewSession(r: AuthResponse) = viewModelScope.launch {
        r.token?.let { store.saveToken(it) }
        r.user?.let { u ->
            cache.put("me", json.encodeToString(User.serializer(), u))
            if (_auth.value is AuthState.LoggedIn) _auth.value = AuthState.LoggedIn(u)
        }
    }

    private fun run(block: suspend () -> AuthResponse) = viewModelScope.launch {
        _busy.value = true
        safeCall(block).onSuccess { r ->
            if (r.mfaRequired && r.mfaToken != null) _mfaChallenge.value = r.mfaToken else accept(r)
        }.onFailure { e ->
            val (code, message) = e.apiError()
            if (code == "MFA_EXPIRED") _mfaChallenge.value = null
            _message.tryEmit(if (code == "MFA_INVALID") "Código incorrecto" else message)
        }
        _busy.value = false
    }

    fun verifyMfa(code: String) {
        val challenge = _mfaChallenge.value ?: return
        run { api.mfaVerify(MfaVerifyRequest(challenge, code.trim())) }
    }

    fun cancelMfa() { _mfaChallenge.value = null }

    fun logoutAll() = viewModelScope.launch {
        safeCall { api.logoutAll() }
            .onSuccess { signOut("Sesión cerrada en todos los dispositivos") }
            .onFailure { _message.tryEmit(it.userMessage()) }
    }
    fun doLogin(email: String, pass: String, remember: Boolean = false) {
        viewModelScope.launch {
            val e = email.trim()
            store.setRememberedEmail(if (remember) e else null)
            _rememberedEmail.value = if (remember) e else null
        }
        run { api.login(LoginRequest(email.trim(), pass)) }
    }
    fun doRegister(r: RegisterRequest) = run { api.register(r) }

    fun forgot(email: String, done: () -> Unit) = viewModelScope.launch {
        _busy.value = true
        safeCall { api.forgot(EmailRequest(email.trim())) }
            .onSuccess { _message.tryEmit("Si el email existe, recibirás un enlace"); done() }
            .onFailure { _message.tryEmit(it.userMessage()) }
        _busy.value = false
    }

    fun reset(token: String, email: String, pass: String, done: () -> Unit) = viewModelScope.launch {
        _busy.value = true
        safeCall { api.reset(ResetPasswordRequest(token.trim(), email.trim(), pass)) }
            .onSuccess { _message.tryEmit("Contraseña actualizada"); done() }
            .onFailure { _message.tryEmit(it.userMessage()) }
        _busy.value = false
    }

    fun signOut(msg: String? = null) = viewModelScope.launch {
        // Clear the token first: an in-flight schedule refresh must not re-arm
        // alarms for a session that is already closed.
        store.clear(); cache.clear()
        reminders.cancel()
        Background.stop(appCtx)
        // Offline clock-ins belong to this session: never send them as another user
        val dropped = offline.all().size
        offline.clear()
        _locked.value = false; _auth.value = AuthState.LoggedOut
        msg?.let { _message.tryEmit(it) }
        if (dropped > 0) _message.tryEmit("Se han descartado $dropped fichajes sin conexión que no se habían enviado")
    }

    fun setBiometric(on: Boolean) = viewModelScope.launch { store.setBiometric(on) }

    val permissionsOnboarded = store.permissionsOnboarded
    fun finishPermissionsOnboarding() = viewModelScope.launch { store.setPermissionsOnboarded() }

    fun updateProfile(req: UpdateProfileRequest) = viewModelScope.launch {
        safeCall { api.updateProfile(req) }.onSuccess {
            cache.put("me", json.encodeToString(User.serializer(), it)); _auth.value = AuthState.LoggedIn(it)
            _message.tryEmit("Perfil actualizado")
        }.onFailure { _message.tryEmit(it.userMessage()) }
    }

    fun changePassword(cur: String, new: String) = viewModelScope.launch {
        safeCall { api.changePassword(ChangePasswordRequest(cur, new)) }
            // The server closes the other sessions and returns a new one for this device
            .onSuccess { renewSession(it); _message.tryEmit("Contraseña cambiada. Se ha cerrado la sesión en tus otros dispositivos.") }
            .onFailure { _message.tryEmit(it.userMessage()) }
    }

    fun uploadAvatar(bytes: ByteArray, mime: String) = viewModelScope.launch {
        val part = uploadFilePart("avatars", bytes, mime, "avatar")
        safeCall { api.uploadAvatar(part) }.onSuccess {
            val url = it.url ?: it.fileUrl ?: it.path
            if (url != null) updateProfile(UpdateProfileRequest(avatarUrl = url))
        }.onFailure { _message.tryEmit(it.userMessage()) }
    }
}

/** Dashboard: fichaje status + summary widgets. */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val api: Api, lf: LoaderFactory, me: MeProvider, private val reminders: ReminderScheduler,
    private val offline: OfflineFichajes, private val network: NetworkMonitor,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appCtx: android.content.Context,
) : ViewModel() {
    /** Clock-ins made without connection that are waiting to be sent. */
    val pendingOffline = offline.pending
    val status = lf.create<FichajeStatus>(viewModelScope, "fichaje_current") { api.fichajeCurrent() }
    val vacations = lf.create<List<VacationRequest>>(viewModelScope, "vacations") { api.vacations() }
    val balance = lf.create<UserBenefitsBalance>(viewModelScope, "balance") { api.myBalance() }
    val news = lf.create<List<NewsItem>>(viewModelScope, "news") { api.news() }
    val week = lf.create<List<FichajeDayStats>>(viewModelScope, "week") { api.fichajeWeek(me.id()) }
    /** Attendance warnings from a manager; the unjustified ones are shown on Home. */
    val warnings = lf.create<List<AttendanceWarning>>(viewModelScope, "late_notifications") { api.lateNotifications() }

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()
    private val _justDone = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val justDone = _justDone.asSharedFlow()

    fun refreshAll() {
        status.refresh(); vacations.refresh(); balance.refresh(); news.refresh(); week.refresh(); warnings.refresh()
        viewModelScope.launch { reminders.refresh() }
    }

    fun justify(id: String, text: String) = viewModelScope.launch {
        safeCall { api.justifyLate(id, JustifyLateRequest(text.trim())) }
            .onSuccess { _events.tryEmit("Justificación enviada"); warnings.refresh() }
            .onFailure { _events.tryEmit(it.userMessage()) }
    }

    /** workMode REMOTE = teletrabajo: no location is sent (it would be the employee's home). */
    /**
     * Without network (or with older offline clock-ins still queued, to keep their order) the
     * clock-in is saved on the device with its time and sent as soon as there is connection.
     */
    private fun queueOffline(entering: Boolean, req: CreateFichajeRequest) {
        val now = java.time.Instant.now().toString()
        offline.add(PendingFichaje(java.util.UUID.randomUUID().toString(), req.tipo, req.workMode, now, req.latitude, req.longitude, req.accuracy))
        val current = status.state.value.data ?: FichajeStatus()
        status.set(current.copy(
            hasActiveEntry = entering,
            currentFichaje = if (entering) Fichaje(tipo = "ENTRADA", timestamp = now, workMode = req.workMode) else null,
        ))
        Background.syncFichajes(appCtx)
        _justDone.tryEmit(entering)
        _events.tryEmit("Sin conexión: ${if (entering) "entrada" else "salida"} guardada en el móvil. Se enviará al recuperar la red.")
    }

    fun fichar(entering: Boolean, lat: Double?, lon: Double?, acc: Float?, workMode: String = "OFFICE") = viewModelScope.launch {
        _busy.value = true
        val tipo = if (entering) "ENTRADA" else "SALIDA"
        val remote = workMode == "REMOTE"
        val req = if (remote) CreateFichajeRequest(tipo, workMode = "REMOTE") else CreateFichajeRequest(tipo, lat, lon, acc, "OFFICE")
        if (!network.online.value || offline.all().isNotEmpty()) {
            queueOffline(entering, req)
            _busy.value = false
            return@launch
        }
        safeCall { api.fichar(req) }.onFailure { e ->
            if (e.isOffline) { queueOffline(entering, req); _busy.value = false; return@launch }
        }.onSuccess {
            if (entering && remote) _events.tryEmit("Entrada registrada en teletrabajo")
            it.status?.let { s -> status.set(s) } ; status.refresh(); week.refresh()
            reminders.refresh()
            _justDone.tryEmit(entering)
            it.warning?.let { w -> _events.tryEmit(w) }
        }.onFailure { _events.tryEmit(it.userMessage()) }
        _busy.value = false
    }
}

@HiltViewModel
class VacationsViewModel @Inject constructor(private val api: Api, lf: LoaderFactory) : ViewModel() {
    val items = lf.create<List<VacationRequest>>(viewModelScope, "vacations") { api.vacations() }
    val balance = lf.create<UserBenefitsBalance>(viewModelScope, "balance") { api.myBalance() }
    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val msg = _msg.asSharedFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun create(r: CreateVacationRequest, file: Pair<ByteArray, String>? = null, ok: () -> Unit) = viewModelScope.launch {
        _busy.value = true
        safeCall {
            var req = r
            if (file != null) {
                val part = uploadFilePart("justifications", file.first, file.second, "justificante")
                val up = api.uploadJustification(part)
                req = r.copy(justificationUrl = up.url ?: up.fileUrl ?: up.path)
            }
            api.createVacation(req)
        }.onSuccess { _msg.tryEmit("Solicitud enviada"); items.refresh(); balance.refresh(); ok() }
            .onFailure { _msg.tryEmit(it.userMessage()) }
        _busy.value = false
    }

    /** Cancel one of my own requests while it is still pending. */
    fun cancel(id: String) = viewModelScope.launch {
        safeCall { api.cancelVacation(id) }
            .onSuccess { _msg.tryEmit("Solicitud cancelada"); items.refresh(); balance.refresh() }
            .onFailure { _msg.tryEmit(it.userMessage()) }
    }

    /** Colleagues of my department already off in that range (null if it could not be checked). */
    suspend fun overlap(start: String, end: String): OverlapResponse? = safeCall { api.vacationOverlap(start, end) }.getOrNull()
}

@HiltViewModel
class CalendarViewModel @Inject constructor(private val api: Api, lf: LoaderFactory, private val me: MeProvider) : ViewModel() {
    val events = lf.create<List<EventItem>>(viewModelScope, "events") { api.events() }
    val mine = lf.create<List<VacationRequest>>(viewModelScope, "vacations") { api.vacations() }
    val team = lf.create<List<VacationRequest>>(viewModelScope, "team_vacations") { api.teamVacations() }
    val adjustments = lf.create<List<FichajeAdjustment>>(viewModelScope, "my_adjustments") { api.myAdjustments() }

    /** My clock-ins per day, loaded per visible month (the calendar is where "Mis fichajes" lives). */
    private val _days = MutableStateFlow<Map<java.time.YearMonth, List<FichajeDayStats>>>(emptyMap())
    val days = _days.asStateFlow()
    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val msg = _msg.asSharedFlow()

    fun loadMonth(ym: java.time.YearMonth, force: Boolean = false) = viewModelScope.launch {
        if (!force && _days.value.containsKey(ym)) return@launch
        safeCall { api.fichajeMonth(me.id(), ym.year, ym.monthValue) }
            .onSuccess { list -> _days.update { it + (ym to list) } }
    }

    fun requestAdjustment(r: CreateAdjustmentRequest) = viewModelScope.launch {
        safeCall { api.createAdjustment(r) }.onSuccess { _msg.tryEmit("Solicitud de ajuste enviada"); adjustments.refresh() }
            .onFailure { _msg.tryEmit(it.userMessage()) }
    }

    fun refresh(current: java.time.YearMonth? = null) {
        events.refresh(); mine.refresh(); team.refresh(); adjustments.refresh()
        current?.let { loadMonth(it, force = true) }
    }
}

@HiltViewModel
class NewsViewModel @Inject constructor(api: Api, lf: LoaderFactory) : ViewModel() {
    val news = lf.create<List<NewsItem>>(viewModelScope, "news") { api.news() }
}

@HiltViewModel
class PayrollViewModel @Inject constructor(private val api: Api, lf: LoaderFactory) : ViewModel() {
    val items = lf.create<List<Payroll>>(viewModelScope, "payrolls") { api.payrolls() }

    /** A downloaded payslip and what to do with it: open in a viewer or save to Downloads. */
    data class PayslipFile(val bytes: ByteArray, val name: String, val save: Boolean)

    private val _file = MutableSharedFlow<PayslipFile>(extraBufferCapacity = 1)
    val file = _file.asSharedFlow()
    private val _msg = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val msg = _msg.asSharedFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy = _busy.asStateFlow()

    /** Payslip waiting for the account password; null = no dialog. */
    data class PasswordPrompt(val payroll: Payroll, val save: Boolean, val error: String? = null, val loading: Boolean = false)
    private val _password = MutableStateFlow<PasswordPrompt?>(null)
    val password = _password.asStateFlow()

    // Unlock issued by the server (POST payrolls/unlock). Memory only: never persisted.
    private var unlock: String? = null
    private var unlockUntil = 0L

    fun open(p: Payroll, save: Boolean = false) {
        val token = unlock?.takeIf { System.currentTimeMillis() < unlockUntil }
        if (token == null) _password.value = PasswordPrompt(p, save) else download(p, save, token)
    }

    fun cancelPassword() { _password.value = null }

    fun submitPassword(pw: String) {
        val prompt = _password.value ?: return
        if (prompt.loading || pw.isEmpty()) return
        _password.value = prompt.copy(loading = true, error = null)
        viewModelScope.launch {
            safeCall { api.payrollUnlock(PayrollUnlockRequest(pw)) }
                .onSuccess { r ->
                    unlock = r.unlockToken
                    // Small margin so a download never leaves with a token about to expire
                    unlockUntil = System.currentTimeMillis() + (r.expiresIn - 15).coerceAtLeast(0) * 1000L
                    _password.value = null
                    download(prompt.payroll, prompt.save, r.unlockToken)
                }
                .onFailure { e ->
                    val (code, message) = e.apiError()
                    _password.value = prompt.copy(loading = false, error = if (code == "WRONG_PASSWORD") "Contraseña incorrecta" else message)
                }
        }
    }

    private fun download(p: Payroll, save: Boolean, token: String) = viewModelScope.launch {
        _busy.value = p.id
        safeCall {
            val info = api.payrollDownload(p.id)
            val url = info.pdfUrl ?: error("Sin archivo")
            val full = ServerConfig.absolute(url) ?: error("URL no segura")
            // Only download from the current company server over HTTPS
            val uri = android.net.Uri.parse(full)
            require(uri.scheme == "https" && uri.host == ServerConfig.host) { "URL no segura" }
            val bytes = api.downloadPayrollFile(full, token).bytes()
            PayslipFile(bytes, info.filename ?: "nomina_${p.month}_${p.year}.pdf", save)
        }.onSuccess { _file.tryEmit(it) }.onFailure { e ->
            val (code, message) = e.apiError()
            if (code == "PAYROLL_LOCKED") {
                // Unlock expired on the server: ask for the password again
                unlock = null
                _password.value = PasswordPrompt(p, save)
            } else _msg.tryEmit(message)
        }
        _busy.value = null
    }
}

@HiltViewModel
class NotificationsViewModel @Inject constructor(private val api: Api, lf: LoaderFactory) : ViewModel() {
    val items = lf.create<NotificationsResponse>(viewModelScope, "notifications") { api.notifications() }
    fun read(id: String) = viewModelScope.launch { safeCall { api.readNotification(id) }; items.refresh() }
    fun readAll() = viewModelScope.launch { safeCall { api.readAllNotifications() }; items.refresh() }
    fun delete(id: String) = viewModelScope.launch { safeCall { api.deleteNotification(id) }; items.refresh() }
}

@javax.inject.Singleton
class MeProvider @Inject constructor(private val cache: CacheStore, private val api: Api, private val json: kotlinx.serialization.json.Json) {
    suspend fun id(): String = user().id

    /**
     * Cached profile first; refreshes it from the API when the cache is empty.
     * [forceRefresh] is used by the background reminder worker so a department
     * change made by an admin is picked up without opening the app.
     */
    suspend fun user(forceRefresh: Boolean = false): User {
        if (!forceRefresh) {
            cache.get("me")?.let { raw ->
                runCatching { json.decodeFromString<User>(raw) }.getOrNull()?.let { if (it.id.isNotBlank()) return it }
            }
        }
        val fresh = api.profile()
        cache.put("me", json.encodeToString(User.serializer(), fresh))
        return fresh
    }
}
