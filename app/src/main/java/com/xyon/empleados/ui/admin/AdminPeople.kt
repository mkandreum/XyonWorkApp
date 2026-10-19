package com.xyon.empleados.ui.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.xyon.empleados.data.*
import com.xyon.empleados.data.admin.*
import com.xyon.empleados.ui.theme.*
import com.xyon.empleados.ui.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ───────────────────────── Users ─────────────────────────

@HiltViewModel
class AdminUsersVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory, private val deps: DepartmentsRepo) : AdminVm() {
    val users = lf.create<List<User>>(viewModelScope, "adm_users") { api.users() }
    private val _departments = MutableStateFlow<List<String>>(emptyList())
    val departments: StateFlow<List<String>> = _departments.asStateFlow()
    init { viewModelScope.launch { _departments.value = deps.names() } }

    fun create(r: CreateUserRequest) = act("Usuario creado", { api.createUser(r) }) { users.refresh() }
    fun update(id: String, r: UpdateUserRequest) = act("Usuario actualizado", { api.updateUser(id, r) }) { users.refresh() }
    fun delete(id: String) = act("Usuario dado de baja", { api.deleteUser(id) }) { users.refresh() }
    fun revokeSessions(id: String) = act("Sesiones cerradas", { api.revokeSessions(id) })
    fun resetMfa(id: String) = act("Verificación en dos pasos restablecida", { api.resetMfa(id) }) { users.refresh() }
    fun assign(id: String, department: String, position: String, role: String) =
        act("Alta completada", { api.assignUser(id, AssignUserRequest(department, position, role)) }) { users.refresh() }
    fun setActive(id: String, active: Boolean) =
        act(if (active) "Usuario reactivado" else "Usuario desactivado", { api.setActive(id, ActiveRequest(active)) }) { users.refresh() }
    fun resetPassword(email: String) = act("Enlace de restablecimiento enviado a $email", { api.forgot(ForgotRequest(email)) })
}

private val ROLES = listOf("EMPLOYEE" to "Empleado", "MANAGER" to "Responsable", "ADMIN" to "Administrador")

@Composable
fun AdminUsersScreen(onBack: () -> Unit, snack: (String) -> Unit, onImport: () -> Unit, vm: AdminUsersVm = hiltViewModel()) {
    val st by vm.users.state.collectAsState()
    val deps by vm.departments.collectAsState()
    var q by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var dept by remember { mutableStateOf("") }
    var form by remember { mutableStateOf<User?>(null) }
    var creating by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<User?>(null) }
    var reset by remember { mutableStateOf<User?>(null) }
    var toggle by remember { mutableStateOf<User?>(null) }
    var revoke by remember { mutableStateOf<User?>(null) }
    var mfaReset by remember { mutableStateOf<User?>(null) }
    var assigning by remember { mutableStateOf<User?>(null) }
    var state by remember { mutableStateOf("") }

    AdminPage("Usuarios", "Alta, edición y roles", onBack, vm, snack,
        actions = {
            IconAction(Icons.Filled.UploadFile, "Importar CSV") { onImport() }
            IconAction(Icons.Filled.PersonAdd, "Nuevo usuario") { creating = true }
        }) {
        SearchField(q, { q = it }, "Buscar por nombre o email")
        ChipsRow(listOf("" to "Todos") + ROLES, role, { role = it })
        ChipsRow(listOf("" to "Todos los estados", "active" to "Activos", "inactive" to "Inactivos"), state, { state = it })
        if (deps.isNotEmpty()) ChipsRow(listOf("" to "Todos los dptos.") + deps.map { it to it }, dept, { dept = it })
        LoadScreen(st, { vm.users.refresh() }) { all ->
            val list = all.filter {
                (role.isBlank() || it.role == role) && (dept.isBlank() || it.department == dept) &&
                    (state.isBlank() || (state == "active") == it.active) &&
                    (q.isBlank() || it.name.contains(q, true) || it.email.contains(q, true))
            }
            ListScreenColumn {
                if (list.isEmpty()) item { EmptyState(Icons.Filled.People, "Sin usuarios que coincidan") }
                items(list, key = { it.id }) { u ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(u.name); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(u.name, style = MaterialTheme.typography.titleMedium)
                                Muted("${u.email} · ${u.department}")
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    StatusChip(ROLES.firstOrNull { it.first == u.role }?.second ?: u.role,
                                        when (u.role) { "ADMIN" -> Tone.Danger; "MANAGER" -> Tone.Warning; else -> Tone.Neutral })
                                    if (!u.active) StatusChip(if (u.deletedAt != null) "Baja" else "Inactivo", Tone.Danger)
                                    if (u.mfaEnabled) StatusChip("2FA", Tone.Success)
                                    if (u.pendingAssignment) StatusChip("Por asignar", Tone.Warning)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            if (u.pendingAssignment) IconAction(Icons.Filled.AssignmentInd, "Asignar departamento") { assigning = u }
                            IconAction(Icons.Filled.LockReset, "Restablecer contraseña") { reset = u }
                            IconAction(Icons.AutoMirrored.Filled.Logout, "Cerrar sus sesiones") { revoke = u }
                            if (u.mfaEnabled) IconAction(Icons.Filled.PhonelinkLock, "Restablecer 2FA") { mfaReset = u }
                            IconAction(if (u.active) Icons.Filled.PersonOff else Icons.Filled.HowToReg, if (u.active) "Desactivar" else "Reactivar") { toggle = u }
                            IconAction(Icons.Filled.Edit, "Editar") { form = u }
                            if (u.deletedAt == null) IconAction(Icons.Filled.Delete, "Dar de baja", danger = true) { del = u }
                        }
                    }
                }
            }
        }
    }

    if (creating || form != null) {
        val u = form
        var name by remember { mutableStateOf(u?.name ?: "") }
        var email by remember { mutableStateOf(u?.email ?: "") }
        var pass by remember { mutableStateOf("") }
        var r by remember { mutableStateOf(u?.role ?: "EMPLOYEE") }
        var d by remember { mutableStateOf(u?.department ?: "") }
        var pos by remember { mutableStateOf(u?.position ?: "") }
        var join by remember { mutableStateOf(u?.joinDate?.take(10) ?: "") }
        val joinOk = join.isBlank() || Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(join)
        val valid = name.trim().length >= 2 && EMAIL_RE.matches(email.trim()) && joinOk && (u != null || strongPassword(pass))
        val close = { creating = false; form = null }
        FormDialog(if (u == null) "Nuevo usuario" else "Editar usuario", enabled = valid, onDismiss = close, onConfirm = {
            val j = join.ifBlank { null }
            if (u == null) vm.create(CreateUserRequest(name.trim(), email.trim(), pass, r, d.trim().ifBlank { "General" }, pos.trim().ifBlank { "Employee" }, j))
            else vm.update(u.id, UpdateUserRequest(name.trim(), email.trim(), r, d.trim(), pos.trim(), j))
            close()
        }) {
            XField(name, { name = it }, "Nombre", error = if (name.isNotEmpty() && name.trim().length < 2) "Mínimo 2 caracteres" else null)
            XField(email, { email = it }, "Email", keyboard = androidx.compose.ui.text.input.KeyboardType.Email,
                error = if (email.isNotEmpty() && !EMAIL_RE.matches(email.trim())) "Email no válido" else null)
            if (u == null) XField(pass, { pass = it }, "Contraseña inicial", password = true,
                error = if (pass.isNotEmpty() && !strongPassword(pass)) "12+ caracteres con mayúscula, minúscula, número y símbolo" else null)
            Dropdown("Rol", r, ROLES, { r = it })
            XField(d, { d = it }, "Departamento")
            if (deps.isNotEmpty()) ChipsRow(deps.map { it to it }, d, { d = it })
            XField(pos, { pos = it }, "Puesto")
            XField(join, { join = it }, "Fecha de alta (AAAA-MM-DD)", error = if (!joinOk) "Formato AAAA-MM-DD" else null)
        }
    }
    del?.let { ConfirmDialog("¿Dar de baja a ${it.name}? Perderá el acceso y se borrarán sus datos de contacto. Sus fichajes, nóminas y solicitudes se conservan durante el plazo legal.", "Dar de baja", onConfirm = { vm.delete(it.id) }, onDismiss = { del = null }) }
    assigning?.let { u ->
        var d by remember(u.id) { mutableStateOf(deps.firstOrNull { it != "Sin asignar" } ?: "") }
        var pos by remember(u.id) { mutableStateOf("") }
        var r by remember(u.id) { mutableStateOf("EMPLOYEE") }
        FormDialog("Completar alta de ${u.name}", enabled = d.isNotBlank() && pos.isNotBlank(), onDismiss = { assigning = null }, onConfirm = {
            vm.assign(u.id, d.trim(), pos.trim(), r); assigning = null
        }) {
            XField(d, { d = it }, "Departamento")
            if (deps.isNotEmpty()) ChipsRow(deps.filter { it != "Sin asignar" }.map { it to it }, d, { d = it })
            XField(pos, { pos = it }, "Puesto")
            Dropdown("Rol", r, listOf("EMPLOYEE" to "Empleado", "MANAGER" to "Responsable del departamento"), { r = it })
        }
    }
    revoke?.let { ConfirmDialog("Se cerrará la sesión de ${it.name} en todos sus dispositivos.", "Cerrar sesiones", onConfirm = { vm.revokeSessions(it.id) }, onDismiss = { revoke = null }) }
    mfaReset?.let { ConfirmDialog("¿Restablecer la verificación en dos pasos de ${it.name}? Úsalo solo si ha perdido el móvil y los códigos de recuperación.", "Restablecer", onConfirm = { vm.resetMfa(it.id) }, onDismiss = { mfaReset = null }) }
    toggle?.let { u ->
        ConfirmDialog(
            if (u.active) "¿Desactivar a ${u.name}? No podrá iniciar sesión y su sesión actual se cerrará. Sus datos se conservan."
            else "¿Reactivar a ${u.name}? Podrá volver a iniciar sesión.",
            if (u.active) "Desactivar" else "Reactivar",
            onConfirm = { vm.setActive(u.id, !u.active) }, onDismiss = { toggle = null })
    }
    reset?.let { ConfirmDialog("Se enviará un email de restablecimiento de contraseña a ${it.email}.", "Enviar", onConfirm = { vm.resetPassword(it.email) }, onDismiss = { reset = null }) }
}

// ───────────────────────── Departments ─────────────────────────

@HiltViewModel
class AdminDepartmentsVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory) : AdminVm() {
    val settings = lf.create<Map<String, String>>(viewModelScope, "adm_settings_dep") { api.settings() }
    val users = lf.create<List<User>>(viewModelScope, "adm_users") { api.users() }
    val offices = lf.create<List<OfficeLocation>>(viewModelScope, "adm_offices") { api.officeLocations() }

    private fun saved(): List<String> =
        runCatching { AppJson.decodeFromString<List<String>>(settings.state.value.data?.get("DEPARTMENTS") ?: "[]") }.getOrDefault(emptyList())

    fun save(list: List<String>, ok: String) = act(ok, { api.updateSettings(mapOf("DEPARTMENTS" to AppJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()), list))) }) { settings.refresh() }
    fun add(name: String, current: List<String>) = save((current + name).distinct(), "Departamento añadido")
    fun remove(name: String, current: List<String>) = save(current - name, "Departamento eliminado")
    fun setOffice(d: String, r: OfficeLocationRequest) = act("Ubicación guardada", { api.setOffice(d, r) }) { offices.refresh() }
    fun deleteOffice(d: String) = act("Ubicación eliminada", { api.deleteOffice(d) }) { offices.refresh() }
    @Suppress("unused") fun current() = saved()
}

@Composable
fun AdminDepartmentsScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminDepartmentsVm = hiltViewModel()) {
    val st by vm.settings.state.collectAsState()
    val users by vm.users.state.collectAsState()
    val offices by vm.offices.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var del by remember { mutableStateOf<String?>(null) }
    var gps by remember { mutableStateOf<String?>(null) }
    AdminPage("Departamentos", "Equipos y ubicación de oficina", onBack, vm, snack,
        actions = { IconAction(Icons.Filled.Add, "Añadir") { adding = true } }) {
        LoadScreen(st, { vm.settings.refresh(); vm.users.refresh(); vm.offices.refresh() }) { s ->
            val saved = runCatching { AppJson.decodeFromString<List<String>>(s["DEPARTMENTS"] ?: "[]") }.getOrDefault(emptyList())
            val ul = users.data ?: emptyList()
            val names = (saved + ul.map { it.department }).filter { it.isNotBlank() }.distinct().sorted()
            ListScreenColumn {
                if (names.isEmpty()) item { EmptyState(Icons.Filled.Apartment, "Aún no hay departamentos") }
                items(names, key = { it }) { d ->
                    val count = ul.count { it.department == d }
                    val office = offices.data?.firstOrNull { it.department == d }
                    GlassCard(Modifier.fillMaxWidth()) {
                        Text(d, style = MaterialTheme.typography.titleMedium)
                        Muted("$count empleados" + (office?.let { " · GPS ${it.maxDistance.toInt()} m" + if (it.required) " (obligatorio)" else "" } ?: " · sin ubicación GPS"))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            IconAction(Icons.Filled.LocationOn, "Ubicación de oficina") { gps = d }
                            IconAction(Icons.Filled.Delete, "Eliminar", danger = true) {
                                if (count > 0) vm.say("No se puede eliminar: tiene $count empleados") else del = d
                            }
                        }
                    }
                }
            }
        }
    }
    val current = run {
        val saved = runCatching { AppJson.decodeFromString<List<String>>(st.data?.get("DEPARTMENTS") ?: "[]") }.getOrDefault(emptyList())
        saved
    }
    if (adding) {
        var n by remember { mutableStateOf("") }
        FormDialog("Nuevo departamento", "Añadir", n.trim().length >= 2, { vm.add(n.trim(), current); adding = false }, { adding = false }) {
            XField(n, { n = it }, "Nombre")
        }
    }
    del?.let { d -> ConfirmDialog("¿Eliminar el departamento $d?", onConfirm = { vm.remove(d, current) }, onDismiss = { del = null }) }
    gps?.let { d ->
        val o = offices.data?.firstOrNull { it.department == d }
        var lat by remember { mutableStateOf(o?.latitude?.toString() ?: "") }
        var lng by remember { mutableStateOf(o?.longitude?.toString() ?: "") }
        var dist by remember { mutableStateOf(o?.maxDistance?.toInt()?.toString() ?: "500") }
        var req by remember { mutableStateOf(o?.required ?: false) }
        var nm by remember { mutableStateOf(o?.name ?: "Oficina $d") }
        val la = lat.replace(',', '.').toDoubleOrNull(); val lo = lng.replace(',', '.').toDoubleOrNull(); val di = dist.toIntOrNull()
        val ok = la != null && lo != null && di != null && la in -90.0..90.0 && lo in -180.0..180.0 && di > 0
        FormDialog("Oficina · $d", enabled = ok, onDismiss = { gps = null }, onConfirm = {
            vm.setOffice(d, OfficeLocationRequest(la!!, lo!!, di!!, req, nm.trim().ifBlank { "Oficina $d" })); gps = null
        }) {
            XField(nm, { nm = it }, "Nombre")
            XField(lat, { lat = it }, "Latitud", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            XField(lng, { lng = it }, "Longitud", keyboard = androidx.compose.ui.text.input.KeyboardType.Decimal)
            XField(dist, { dist = it }, "Distancia máxima (m)", keyboard = androidx.compose.ui.text.input.KeyboardType.Number)
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(req, { req = it }); Spacer(Modifier.width(8.dp)); Text("GPS obligatorio para fichar") }
            if (o != null) TextButton({ vm.deleteOffice(d); gps = null }) { Text("Quitar ubicación", color = com.xyon.empleados.ui.theme.Danger) }
        }
    }
}

// ───────────────────────── Vacation / absence requests ─────────────────────────

@HiltViewModel
class AdminRequestsVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory) : AdminVm() {
    val admin = lf.create<List<VacationRequest>>(viewModelScope, "adm_vacations") { api.vacations() }
    val team = lf.create<List<VacationRequest>>(viewModelScope, "adm_team_vacations") { api.teamVacations() }
    fun setAdmin(id: String, status: String) = act(if (status == "APPROVED") "Solicitud aprobada" else "Solicitud rechazada", { api.vacationStatus(id, StatusRequest(status)) }) { admin.refresh() }
    fun managerApprove(id: String) = act("Aprobada (pasa a administración)", { api.managerApprove(id) }) { team.refresh() }
    fun managerReject(id: String) = act("Solicitud rechazada", { api.managerReject(id) }) { team.refresh() }

    /** Approve or reject several requests at once; reports how many failed. */
    fun bulk(ids: List<String>, approve: Boolean, manager: Boolean, done: () -> Unit) = act(
        if (approve) "${ids.size} aprobadas" else "${ids.size} rechazadas",
        {
            val failed = ids.count { id ->
                safeCall {
                    when {
                        manager && approve -> api.managerApprove(id)
                        manager -> api.managerReject(id)
                        else -> api.vacationStatus(id, StatusRequest(if (approve) "APPROVED" else "REJECTED"))
                    }
                }.isFailure
            }
            if (failed > 0) error("$failed no se pudieron actualizar")
        },
    ) { if (manager) team.refresh() else admin.refresh(); done() }
}

/** kind: "vac" (only VACATION), "abs" (everything else), "all". manager=true uses the department-limited endpoints. */
@Composable
fun AdminRequestsScreen(kind: String, manager: Boolean, onBack: () -> Unit, snack: (String) -> Unit, vm: AdminRequestsVm = hiltViewModel()) {
    val st by (if (manager) vm.team else vm.admin).state.collectAsState()
    var status by remember { mutableStateOf("PENDING") }
    var q by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf<VacationRequest?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmBulkReject by remember { mutableStateOf(false) }
    val busy by vm.busy.collectAsState()
    val title = when (kind) { "vac" -> "Vacaciones"; "abs" -> "Ausencias"; else -> "Solicitudes" }
    fun canAct(v: VacationRequest) = if (manager) v.status == "PENDING_MANAGER" || v.status == "PENDING" else v.status.startsWith("PENDING")
    AdminPage(title, if (manager) "Tu equipo" else "Aprobación de solicitudes", onBack, vm, snack) {
        SearchField(q, { q = it }, "Buscar empleado")
        ChipsRow(listOf("PENDING" to "Pendientes", "APPROVED" to "Aprobadas", "REJECTED" to "Rechazadas", "" to "Todas"), status, { status = it })
        LoadScreen(st, { (if (manager) vm.team else vm.admin).refresh() }) { all ->
            val list = all.filter {
                (kind == "all" || (kind == "vac") == (it.type == "VACATION")) &&
                    (status.isBlank() || (status == "PENDING" && it.status.startsWith("PENDING")) || it.status == status) &&
                    (q.isBlank() || (it.user?.name ?: "").contains(q, true))
            }.sortedByDescending { it.createdAt ?: it.startDate }
            val actionable = list.filter(::canAct)
            Box(Modifier.fillMaxSize()) {
            ListScreenColumn {
                if (list.isEmpty()) item { EmptyState(Icons.Filled.Inbox, "Sin solicitudes") }
                if (actionable.size > 1) item {
                    val all = actionable.all { it.id in selected }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(all, { selected = if (all) emptySet() else actionable.map { it.id }.toSet() })
                        Text("Seleccionar las ${actionable.size} pendientes", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    }
                }
                items(list, key = { it.id }) { v ->
                    XyonCard(Modifier.fillMaxWidth(), accent = if (v.id in selected) Lime else null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (canAct(v)) Checkbox(v.id in selected, { on -> selected = if (on) selected + v.id else selected - v.id })
                            Column(Modifier.weight(1f)) {
                                Text(v.user?.name ?: "—", style = MaterialTheme.typography.titleMedium)
                                Muted("${typeLabel(v.type)}${v.subtype?.let { " · $it" } ?: ""} · ${v.user?.department ?: ""}")
                                Muted("${fmtDate(v.startDate)} → ${fmtDate(v.endDate)} · ${v.days.toInt()} d" + (v.hours?.let { " · ${it.toInt()} h" } ?: ""))
                            }
                            StatusChip(statusLabel(v.status), statusTone(v.status))
                        }
                        if (canAct(v) && v.id !in selected) ApproveRejectRow(
                            onApprove = { if (manager) vm.managerApprove(v.id) else vm.setAdmin(v.id, "APPROVED") },
                            onReject = { confirm = v })
                    }
                }
            }
            // Bulk action bar while something is selected
            androidx.compose.animation.AnimatedVisibility(
                selected.isNotEmpty(), Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp, start = 16.dp, end = 16.dp),
                enter = androidx.compose.animation.slideInVertically { it } + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut(),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    XyonButton("Aprobar ${selected.size}", { vm.bulk(selected.toList(), true, manager) { selected = emptySet() } },
                        Modifier.weight(1f), style = XyonButtonStyle.Lime, loading = busy, icon = Icons.Filled.Check)
                    XyonButton("Rechazar", { confirmBulkReject = true }, Modifier.weight(1f), style = XyonButtonStyle.Ink, enabled = !busy, icon = Icons.Filled.Close)
                }
            }
            }
        }
    }
    if (confirmBulkReject) {
        ConfirmDialog("¿Rechazar ${selected.size} ${if (selected.size == 1) "solicitud" else "solicitudes"}?", "Rechazar",
            onConfirm = { vm.bulk(selected.toList(), false, manager) { selected = emptySet() }; confirmBulkReject = false },
            onDismiss = { confirmBulkReject = false })
    }
    confirm?.let { v ->
        ConfirmDialog("¿Rechazar la solicitud de ${v.user?.name ?: "este empleado"}?", "Rechazar",
            onConfirm = { if (manager) vm.managerReject(v.id) else vm.setAdmin(v.id, "REJECTED") }, onDismiss = { confirm = null })
    }
}

// ───────────────────────── Fichaje adjustments ─────────────────────────

@HiltViewModel
class AdminAdjustmentsVm @Inject constructor(private val api: AdminApi, lf: LoaderFactory) : AdminVm() {
    val pending = lf.create<List<FichajeAdjustment>>(viewModelScope, "adm_adj_pending") { api.pendingAdjustments() }
    val all = lf.create<List<FichajeAdjustment>>(viewModelScope, "adm_adj_all") { api.allAdjustments() }
    private fun refresh() { pending.refresh(); all.refresh() }
    fun approve(id: String) = act("Ajuste aprobado", { api.approveAdjustment(id) }) { refresh() }
    fun reject(id: String, reason: String) = act("Ajuste rechazado", { api.rejectAdjustment(id, RejectRequest(reason)) }) { refresh() }
}

@Composable
fun AdminAdjustmentsScreen(onBack: () -> Unit, snack: (String) -> Unit, vm: AdminAdjustmentsVm = hiltViewModel()) {
    var tab by remember { mutableStateOf("p") }
    val st by (if (tab == "p") vm.pending else vm.all).state.collectAsState()
    var rej by remember { mutableStateOf<FichajeAdjustment?>(null) }
    AdminPage("Ajustes de fichaje", "Aprobar o rechazar correcciones", onBack, vm, snack) {
        ChipsRow(listOf("p" to "Pendientes", "h" to "Historial"), tab, { tab = it })
        LoadScreen(st, { (if (tab == "p") vm.pending else vm.all).refresh() }) { list ->
            ListScreenColumn {
                if (list.isEmpty()) item { EmptyState(Icons.Filled.Inbox, "Sin solicitudes de ajuste") }
                items(list, key = { it.id }) { a ->
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.user?.name ?: "—", style = MaterialTheme.typography.titleMedium)
                                Muted("${fmtDate(a.originalTimestamp)} ${fmtTimeOf(a.originalTimestamp)} → ${fmtTimeOf(a.requestedTimestamp)}")
                                Muted(a.reason)
                                a.rejectionReason?.let { Muted("Rechazo: $it") }
                            }
                            StatusChip(statusLabel(a.status), statusTone(a.status))
                        }
                        if (a.status == "PENDING") ApproveRejectRow({ vm.approve(a.id) }, { rej = a })
                    }
                }
            }
        }
    }
    rej?.let { a ->
        var reason by remember { mutableStateOf("") }
        FormDialog("Motivo del rechazo", "Rechazar", reason.trim().length >= 3, { vm.reject(a.id, reason.trim()); rej = null }, { rej = null }) {
            XField(reason, { reason = it }, "Motivo", singleLine = false, minLines = 2)
        }
    }
}
